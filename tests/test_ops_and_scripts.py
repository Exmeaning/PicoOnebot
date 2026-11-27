#!/usr/bin/env python3
"""Standard-library test suite for PicoOnebot operational scripts, shell filters, and YAML configs.

Zero third-party dependencies required; YAML validation uses PyYAML when installed
and skips gracefully otherwise.
"""

from __future__ import annotations

import ast
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.parse import urlparse

try:
    import yaml  # type: ignore[import-untyped]
except ImportError:  # pragma: no cover
    yaml = None

REPO_ROOT = Path(__file__).resolve().parent.parent
PICOQR_PATH = REPO_ROOT / "scripts" / "picoqr.py"
PICOCTL_PATH = REPO_ROOT / "scripts" / "picoctl.sh"
OBCHECK_PATH = REPO_ROOT / "tools" / "obcheck.py"

SHA40_RE = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
AWK_17D3_RE = re.compile(r"awk\s+'([^']*17\[Dd\]3[^']*)'")
AWK_PORT_HEX_RE = re.compile(r"awk\s+-v\s+port_hex=\"\$[^\"]+\"\s+'([^']*port_hex[^']*)'")


class _MockWebApiState:
    def __init__(self, mode: str) -> None:
        self.mode = mode
        self.login_requests: list[dict] = []
        self.qr_requests: list[dict] = []
        self.qr_call_count = 0
        self.lock = threading.Lock()


def _start_mock_webapi(mode: str) -> tuple[ThreadingHTTPServer, _MockWebApiState, str]:
    state = _MockWebApiState(mode)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args) -> None:  # noqa: A002
            return

        def _send_json(self, status: int, body: dict) -> None:
            raw = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_POST(self) -> None:  # noqa: N802
            parsed = urlparse(self.path)
            length = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(length) if length > 0 else b""
            payload = json.loads(raw_body.decode("utf-8")) if raw_body else {}

            if parsed.path == "/api/auth/login":
                with state.lock:
                    state.login_requests.append({
                        "content_type": self.headers.get("Content-Type", ""),
                        "payload": payload,
                        "query": parsed.query,
                    })
                if state.mode == "401":
                    self._send_json(401, {"message": "invalid credentials"})
                    return
                if state.mode == "428_login":
                    self._send_json(200, {"token": "temp-init-token", "mustChangePassword": True})
                    return
                if state.mode == "flow_success":
                    if payload.get("token") == "correct-console-pass":
                        self._send_json(200, {"token": "session-bearer-xyz", "mustChangePassword": False})
                    else:
                        self._send_json(401, {"message": "wrong password"})
                    return

            if parsed.path == "/api/qr/refresh":
                if state.mode == "expired_once_refresh":
                    self._send_json(
                        200,
                        {
                            "status": "ok",
                            "url": "https://xui.ptlogin2.qq.com/qr?sig=refreshed-once-02",
                            "expiresIn": 120,
                            "scanned": False,
                        },
                    )
                    return

            self._send_json(404, {"message": "not found"})

        def do_GET(self) -> None:  # noqa: N802
            parsed = urlparse(self.path)
            if parsed.path != "/api/qr":
                self._send_json(404, {"message": "not found"})
                return

            with state.lock:
                state.qr_call_count += 1
                call_num = state.qr_call_count
                state.qr_requests.append({
                    "authorization": self.headers.get("Authorization", ""),
                    "query": parsed.query,
                })

            if state.mode == "428_qr":
                self._send_json(428, {"message": "password initialization required"})
                return

            if state.mode == "401_qr":
                self._send_json(401, {"message": "unauthorized"})
                return

            if state.mode == "expired_once_refresh":
                self._send_json(
                    200,
                    {
                        "status": "expired",
                        "url": "",
                        "expiresIn": 0,
                        "scanned": False,
                    },
                )
                return

            if state.mode == "flow_success":
                if self.headers.get("Authorization") != "Bearer session-bearer-xyz":
                    self._send_json(401, {"message": "missing or wrong bearer token"})
                    return
                if call_num in (1, 2):
                    # Return the exact same QR URL twice to verify dedup
                    self._send_json(
                        200,
                        {
                            "status": "ok",
                            "url": "https://xui.ptlogin2.qq.com/qr?sig=dedup-token-01",
                            "expiresIn": 115,
                            "scanned": False,
                        },
                    )
                    return
                self._send_json(
                    200,
                    {
                        "status": "online",
                        "online": True,
                        "user_id": 1008611,
                    },
                )
                return

            self._send_json(500, {"message": "unexpected test mode"})

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, state, f"http://127.0.0.1:{port}"


class PicoQrTests(unittest.TestCase):
    def test_picoqr_has_no_utf8_bom_and_parses_cleanly(self) -> None:
        raw = PICOQR_PATH.read_bytes()
        self.assertFalse(
            raw.startswith(b"\xef\xbb\xbf"),
            "scripts/picoqr.py must NOT contain a UTF-8 BOM",
        )
        text = raw.decode("utf-8")
        self.assertTrue(text.startswith("#!/usr/bin/env python3"))
        ast.parse(text, filename=str(PICOQR_PATH))

    def test_all_python_tools_parse_cleanly(self) -> None:
        py_files = sorted((REPO_ROOT / "tools").glob("*.py")) + [PICOQR_PATH]
        self.assertGreaterEqual(len(py_files), 4)
        for path in py_files:
            with self.subTest(file=path.relative_to(REPO_ROOT).as_posix()):
                raw = path.read_bytes()
                self.assertFalse(raw.startswith(b"\xef\xbb\xbf"), f"{path} has UTF-8 BOM")
                ast.parse(raw.decode("utf-8"), filename=str(path))

    def _run_picoqr(self, *args: str, env_overrides: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.pop("PICO_WEBUI_TOKEN", None)
        env.pop("PICO_API", None)
        if env_overrides:
            env.update(env_overrides)
        return subprocess.run(
            [sys.executable, str(PICOQR_PATH), *args],
            capture_output=True,
            text=True,
            timeout=25,
            env=env,
        )

    def test_picoqr_handles_401_with_exit_code_2(self) -> None:
        server, state, base_url = _start_mock_webapi("401")
        try:
            proc = self._run_picoqr("--api", base_url, "--token", "wrong-pass", "--timeout", "10")
            self.assertEqual(proc.returncode, 2, msg=f"stderr={proc.stderr!r}")
            self.assertIn("401", proc.stderr)
            self.assertEqual(len(state.login_requests), 1)
            self.assertEqual(len(state.qr_requests), 0)
        finally:
            server.shutdown()
            server.server_close()

    def test_picoqr_handles_428_from_login_and_qr_with_exit_code_3(self) -> None:
        for mode, extra_args in (
            ("428_login", ("--token", "picopico")),
            ("428_qr", ()),
        ):
            with self.subTest(mode=mode):
                server, _state, base_url = _start_mock_webapi(mode)
                try:
                    proc = self._run_picoqr("--api", base_url, *extra_args, "--timeout", "10")
                    self.assertEqual(proc.returncode, 3, msg=f"stderr={proc.stderr!r}")
                    self.assertIn("428", proc.stderr)
                finally:
                    server.shutdown()
                    server.server_close()

    def test_picoqr_login_bearer_qr_dedup_and_online_transition(self) -> None:
        server, state, base_url = _start_mock_webapi("flow_success")
        try:
            proc = self._run_picoqr(
                "--api",
                base_url,
                "--timeout",
                "20",
                env_overrides={"PICO_WEBUI_TOKEN": "correct-console-pass"},
            )
            self.assertEqual(proc.returncode, 0, msg=f"stdout={proc.stdout!r}\nstderr={proc.stderr!r}")

            # 1. Login exchanged console password for session token via POST JSON
            self.assertEqual(len(state.login_requests), 1)
            self.assertEqual(state.login_requests[0]["payload"], {"token": "correct-console-pass"})
            self.assertIn("application/json", state.login_requests[0]["content_type"])

            # 2. All /api/qr calls carried Bearer session token and no query-string token
            self.assertEqual(len(state.qr_requests), 3)
            for req in state.qr_requests:
                self.assertEqual(req["authorization"], "Bearer session-bearer-xyz")
                self.assertEqual(req["query"], "")

            # 3. Duplicate QR URL rendered exactly once, followed by online transition message
            self.assertEqual(proc.stdout.count("[登录链接] https://xui.ptlogin2.qq.com/qr?sig=dedup-token-01"), 1)
            self.assertIn("1008611", proc.stdout)
        finally:
            server.shutdown()
            server.server_close()

    def test_picoqr_once_expired_uses_refreshed_qr_and_exits_0(self) -> None:
        server, state, base_url = _start_mock_webapi("expired_once_refresh")
        try:
            proc = self._run_picoqr("--api", base_url, "--once", "--timeout", "10")
            self.assertEqual(proc.returncode, 0, msg=f"stdout={proc.stdout!r}\nstderr={proc.stderr!r}")
            self.assertIn("[登录链接] https://xui.ptlogin2.qq.com/qr?sig=refreshed-once-02", proc.stdout)
            self.assertEqual(len(state.qr_requests), 1)
        finally:
            server.shutdown()
            server.server_close()


class PicoCtlTests(unittest.TestCase):
    """Regression tests for scripts/picoctl.sh HTTP probe & curl -K hardening (P0-1, P2-28)."""

    def _run_picoctl_snippet(
        self,
        snippet: str,
        mock_curl_script: str,
        env_overrides: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="picoctl-test-") as td:
            bin_dir = Path(td) / "bin"
            bin_dir.mkdir()
            curl_path = bin_dir / "curl"
            curl_path.write_text(mock_curl_script, encoding="utf-8")
            curl_path.chmod(curl_path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}:{env.get('PATH', '')}"
            env["PICOCTL_SOURCE_ONLY"] = "1"
            env["PICO_IP"] = "127.0.0.1"
            env["PICO_WEBUI_PORT"] = "6099"
            env["PICO_PORT"] = "3001"
            if env_overrides:
                env.update(env_overrides)

            cmd = f'source "{PICOCTL_PATH}"\n{snippet}'
            return subprocess.run(
                ["bash", "-c", cmd],
                capture_output=True,
                text=True,
                timeout=15,
                env=env,
            )

    def test_webui_ready_rejects_curl_failure_000_and_invalid_codes(self) -> None:
        # Simulate real curl behavior: prints '000' for -w '%{http_code}' AND exits non-zero (7 or 28).
        for label, body, rc, expected_rc in (
            ("conn_refused_000_exit7", "printf '000'; exit 7", 7, 1),
            ("timeout_000_exit28", "printf '000'; exit 28", 28, 1),
            ("empty_output_exit1", "exit 1", 1, 1),
            ("bogus_000000_exit0", "printf '000000'; exit 0", 0, 1),
            ("bogus_999_exit0", "printf '999'; exit 0", 0, 1),
            ("http_200", "printf '200'; exit 0", 0, 0),
            ("http_401", "printf '401'; exit 0", 0, 0),
            ("http_428", "printf '428'; exit 0", 0, 0),
            ("http_503", "printf '503'; exit 0", 0, 0),
        ):
            with self.subTest(case=label):
                proc = self._run_picoctl_snippet(
                    "webui_ready",
                    f"#!/usr/bin/env bash\n{body}\n",
                )
                self.assertEqual(proc.returncode, expected_rc, msg=f"stderr={proc.stderr!r}")

    def test_webui_state_and_pico_state_treat_curl_000_as_unreachable(self) -> None:
        mock_curl_fail = "#!/usr/bin/env bash\nprintf '000'\nexit 7\n"

        proc_ws = self._run_picoctl_snippet("webui_state", mock_curl_fail)
        self.assertEqual(proc_ws.returncode, 1)
        self.assertEqual(proc_ws.stdout.strip(), "")

        # Even when app_running returns true, pico_state MUST fail when both 3001 & 6099 fail with 000!
        proc_ps = self._run_picoctl_snippet(
            "app_running() { return 0; }\npico_state",
            mock_curl_fail,
        )
        self.assertEqual(proc_ps.returncode, 1)
        self.assertEqual(proc_ps.stdout.strip(), "")

    def test_webui_state_distinguishes_401_from_transient_429_5xx_in_detailed_mode(self) -> None:
        def make_curl(http_code: str, json_body: str = "{}") -> str:
            return (
                "#!/usr/bin/env bash\n"
                "out=''\n"
                "while [ $# -gt 0 ]; do\n"
                "  if [ \"$1\" = '-o' ]; then out=\"$2\"; shift 2; continue; fi\n"
                "  shift\n"
                "done\n"
                f"[ -n \"$out\" ] && printf '%s' '{json_body}' > \"$out\"\n"
                f"printf '{http_code}'\n"
                "exit 0\n"
            )

        cases = (
            ("200_online", "200", '{"online": true}', "0", 0, "ONLINE"),
            ("200_waiting", "200", '{"online": false}', "0", 0, "WAITING_LOGIN"),
            ("428_init", "428", "{}", "0", 0, "NEEDS_INIT"),
            ("401_default", "401", "{}", "0", 0, "WEBUI_UP"),
            ("401_detailed", "401", "{}", "1", 0, "WEBUI_AUTH_REJECTED"),
            ("429_detailed_not_auth_fail", "429", "{}", "1", 0, "WEBUI_UP"),
            ("503_detailed_not_auth_fail", "503", "{}", "1", 0, "WEBUI_UP"),
        )
        for label, code, body, detailed, exp_rc, exp_out in cases:
            with self.subTest(case=label):
                proc = self._run_picoctl_snippet(
                    "webui_state",
                    make_curl(code, body),
                    env_overrides={"PICO_STATE_DETAILED": detailed},
                )
                self.assertEqual(proc.returncode, exp_rc)
                self.assertEqual(proc.stdout.strip(), exp_out)

    def test_curl_auth_strips_crlf_from_token(self) -> None:
        # Mock curl dumps stdin (-K -) so we can verify no extra config directive line is injected
        mock_curl = "#!/usr/bin/env bash\ncat\n"
        proc = self._run_picoctl_snippet(
            "curl_auth $'tok\\r\\nurl = http://evil.example/' http://127.0.0.1:6099/",
            mock_curl,
        )
        self.assertEqual(proc.returncode, 0)
        lines = proc.stdout.splitlines()
        self.assertEqual(len(lines), 1, f"CR/LF must not inject extra curl -K lines: {lines!r}")
        self.assertEqual(lines[0], 'header = "Authorization: Bearer tokurl = http://evil.example/"')


class ObCheckTests(unittest.TestCase):
    """Regression tests for tools/obcheck.py header combining & request target validation (P2-29)."""

    @classmethod
    def setUpClass(cls) -> None:
        spec = importlib.util.spec_from_file_location("pico_obcheck", OBCHECK_PATH)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cls.obcheck = module

    def test_rejects_space_and_tab_in_ws_target(self) -> None:
        for bad_url in (
            "ws://127.0.0.1:3001/a b",
            "ws://127.0.0.1:3001/a\tb",
            "ws://127.0.0.1:3001/?q=hello world",
        ):
            with self.subTest(url=bad_url):
                with self.assertRaises(self.obcheck.WsError):
                    self.obcheck.resolve_ws_url(bad_url)

        sock_a, sock_b = socket.socketpair()
        try:
            for bad_path in ("/bad path", "/bad\tpath", ""):
                with self.subTest(path=bad_path):
                    with self.assertRaises(self.obcheck.WsError):
                        self.obcheck.handshake(sock_a, "127.0.0.1", 3001, bad_path)
        finally:
            sock_a.close()
            sock_b.close()

    def test_combines_duplicate_connection_headers_during_handshake(self) -> None:
        client, server = socket.socketpair()

        def serve() -> None:
            req = b""
            while b"\r\n\r\n" not in req:
                chunk = server.recv(4096)
                if not chunk:
                    break
                req += chunk
            key = ""
            for line in req.decode("iso-8859-1").split("\r\n"):
                if line.lower().startswith("sec-websocket-key:"):
                    key = line.split(":", 1)[1].strip()
                    break
            accept = base64.b64encode(
                hashlib.sha1((key + self.obcheck.WS_GUID).encode("ascii")).digest()
            ).decode("ascii")
            resp = (
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                "Connection: keep-alive\r\n"
                f"Sec-WebSocket-Accept: {accept}\r\n"
                "\r\n"
            ).encode("ascii")
            server.sendall(resp)

        t = threading.Thread(target=serve, daemon=True)
        t.start()
        try:
            rest = self.obcheck.handshake(client, "127.0.0.1", 3001, "/")
            self.assertEqual(rest, b"")
        finally:
            client.close()
            server.close()
            t.join(timeout=5)


class ProcNetTcpAwkFilterTests(unittest.TestCase):
    STATIC_PORT_SCRIPTS = (
        REPO_ROOT / "docker" / "pico-doctor.sh",
    )
    DYNAMIC_PORT_SCRIPTS = (
        REPO_ROOT / "docker" / "pico-health.sh",
        REPO_ROOT / "docker" / "pico-boot.sh",
    )

    @staticmethod
    def _run_awk(program: str, proc_net_content: str, port_hex: str | None = None) -> int:
        cmd = ["awk"]
        if port_hex is not None:
            cmd.extend(["-v", f"port_hex={port_hex}"])
        cmd.append(program)
        res = subprocess.run(
            cmd,
            input=proc_net_content,
            text=True,
            capture_output=True,
            timeout=10,
        )
        return res.returncode

    def test_awk_filter_matches_only_local_listen_17d3(self) -> None:
        header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"

        cases = [
            (
                "ipv4_local_listen_17D3",
                header + "   0: 00000000:17D3 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12345 1 00000000 100 0 0 10 0\n",
                0,
            ),
            (
                "ipv6_local_listen_lowercase_17d3",
                header + "   0: 00000000000000000000000000000000:17d3 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12346\n",
                0,
            ),
            (
                "reject_remote_17D3_established",
                header + "   1: 0100007F:C350 0A000002:17D3 01 00000000:00000000 00:00000000 00000000 1000 0 12347\n",
                1,
            ),
            (
                "reject_remote_17D3_even_if_state_0A",
                header + "   1: 0100007F:C350 0A000002:17D3 0A 00000000:00000000 00:00000000 00000000 1000 0 12348\n",
                1,
            ),
            (
                "reject_local_17D3_time_wait_06",
                header + "   2: 00000000:17D3 0A000002:D431 06 00000000:00000000 00:00000000 00000000 0 0 0\n",
                1,
            ),
            (
                "reject_local_17D3_established_01",
                header + "   3: 0100007F:17D3 0100007F:E100 01 00000000:00000000 00:00000000 00000000 1000 0 12349\n",
                1,
            ),
            (
                "reject_other_listen_port_0BB9",
                header + "   4: 00000000:0BB9 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12350\n",
                1,
            ),
            (
                "reject_prefix_suffix_port_17D30",
                header + "   5: 00000000:17D30 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12351\n",
                1,
            ),
            (
                "reject_empty_table",
                header,
                1,
            ),
            (
                "mixed_table_with_valid_listen_17D3",
                header
                + "   0: 00000000:0BB9 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 100\n"
                + "   1: 0100007F:C350 0A000002:17D3 01 00000000:00000000 00:00000000 00000000 1000 0 101\n"
                + "   2: 00000000:17D3 0A000002:D431 06 00000000:00000000 00:00000000 00000000 0 0 0\n"
                + "   3: 00000000:17D3 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 102\n",
                0,
            ),
        ]

        for script_path in self.STATIC_PORT_SCRIPTS:
            text = script_path.read_text(encoding="utf-8")
            match = AWK_17D3_RE.search(text)
            self.assertIsNotNone(match, f"Expected /proc/net/tcp awk 17[Dd]3 filter in {script_path}")
            assert match is not None
            awk_program = match.group(1)
            self.assertIn('$4 == "0A"', awk_program, f"{script_path} must verify LISTEN state 0A")
            self.assertIn("$2 ~", awk_program, f"{script_path} must match local address column $2")

            for case_name, content, expected_rc in cases:
                with self.subTest(script=script_path.name, case=case_name):
                    self.assertEqual(self._run_awk(awk_program, content), expected_rc)

        for script_path in self.DYNAMIC_PORT_SCRIPTS:
            text = script_path.read_text(encoding="utf-8")
            match = AWK_PORT_HEX_RE.search(text)
            self.assertIsNotNone(match, f"Expected dynamic port_hex /proc/net/tcp awk filter in {script_path}")
            assert match is not None
            awk_program = match.group(1)
            self.assertIn('$4 == "0A"', awk_program, f"{script_path} must verify LISTEN state 0A")
            self.assertIn("toupper($2) ~", awk_program, f"{script_path} must match local address column $2")

            for case_name, content, expected_rc in cases:
                with self.subTest(script=script_path.name, case=case_name):
                    self.assertEqual(self._run_awk(awk_program, content, port_hex="17D3"), expected_rc)

            # Custom configured port (8080 = 0x1F90) must match only 1F90, not 17D3
            custom_match_tbl = header + "   0: 00000000:1f90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 200\n"
            custom_miss_tbl = header + "   0: 00000000:17D3 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 201\n"
            with self.subTest(script=script_path.name, case="custom_port_8080_match"):
                self.assertEqual(self._run_awk(awk_program, custom_match_tbl, port_hex="1F90"), 0)
            with self.subTest(script=script_path.name, case="custom_port_8080_reject_6099"):
                self.assertEqual(self._run_awk(awk_program, custom_miss_tbl, port_hex="1F90"), 1)

    def test_detect_webui_port_parses_config_json_and_falls_back_to_6099(self) -> None:
        # Extract detect_webui_port() from pico-health.sh and execute under sh
        health_text = (REPO_ROOT / "docker" / "pico-health.sh").read_text(encoding="utf-8")
        m = re.search(r"detect_webui_port\(\)\s*\{.*?\n\}", health_text, re.S)
        self.assertIsNotNone(m)
        assert m is not None
        fn_def = m.group(0)

        with tempfile.TemporaryDirectory(prefix="pico-port-test-") as td:
            cfg = Path(td) / "config.json"
            samples = [
                ("pretty_custom_port", json.dumps({"version": 2, "webui": {"host": "0.0.0.0", "port": 8080}, "network": {"wsServers": [{"port": 3001}]}}, indent=2), "8080"),
                ("minified_custom_port", json.dumps({"webui": {"cors_allowed_origins": ["http://a.test"], "port": 7001}, "network": {"wsServers": [{"port": 3001}]}}), "7001"),
                ("missing_webui_port_does_not_grab_network_port", json.dumps({"webui": {"host": "0.0.0.0"}, "network": {"wsServers": [{"port": 3001}]}}), "6099"),
                ("invalid_port_out_of_range", json.dumps({"webui": {"port": 99999}}), "6099"),
                ("corrupt_json", "{not-json", "6099"),
            ]
            for label, content, expected in samples:
                with self.subTest(case=label):
                    cfg.write_text(content, encoding="utf-8")
                    env = os.environ.copy()
                    env["PICO_CONFIG_FILE"] = str(cfg)
                    proc = subprocess.run(
                        ["sh", "-c", f"{fn_def}\ndetect_webui_port"],
                        capture_output=True,
                        text=True,
                        timeout=10,
                        env=env,
                    )
                    self.assertEqual(proc.returncode, 0)
                    self.assertEqual(proc.stdout.strip(), expected)


class ShellAndYamlValidationTests(unittest.TestCase):
    def test_all_shell_scripts_pass_syntax_check(self) -> None:
        scripts = sorted(
            [
                *(REPO_ROOT / "docker").glob("*.sh"),
                *(REPO_ROOT / "docker" / "scripts").glob("*.sh"),
                *(REPO_ROOT / "scripts").glob("*.sh"),
            ]
        )
        self.assertGreaterEqual(len(scripts), 10)
        for script in scripts:
            first_line = script.read_text(encoding="utf-8").splitlines()[0]
            shell = "bash" if "bash" in first_line else "sh"
            with self.subTest(script=script.relative_to(REPO_ROOT).as_posix(), shell=shell):
                proc = subprocess.run(
                    [shell, "-n", str(script)],
                    capture_output=True,
                    text=True,
                    timeout=10,
                )
                self.assertEqual(
                    proc.returncode,
                    0,
                    msg=f"{shell} -n failed for {script}:\n{proc.stderr}",
                )

    def test_yaml_manifests_and_workflows_parse_cleanly(self) -> None:
        if yaml is None:
            self.skipTest("PyYAML is not installed")

        yaml_files = sorted(
            [
                *(REPO_ROOT / ".github").rglob("*.yml"),
                *(REPO_ROOT / ".github").rglob("*.yaml"),
                *(REPO_ROOT / "docker").glob("*.yml"),
                *(REPO_ROOT / "docker").glob("*.yaml"),
            ]
        )
        self.assertGreaterEqual(len(yaml_files), 6)

        parsed_by_rel: dict[str, list[dict]] = {}
        for path in yaml_files:
            rel = path.relative_to(REPO_ROOT).as_posix()
            with self.subTest(yaml_file=rel):
                docs = [d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if d is not None]
                self.assertGreaterEqual(len(docs), 1, f"{rel} contained no YAML documents")
                for doc in docs:
                    self.assertIsInstance(doc, dict, f"{rel} top-level document must be a mapping")
                parsed_by_rel[rel] = docs

        # Verify every workflow pins third-party actions to 40-char commit SHAs
        release_shas: set[str] = set()
        for rel, docs in parsed_by_rel.items():
            if not rel.startswith(".github/workflows/"):
                continue
            workflow = docs[0]
            for job_name, job in (workflow.get("jobs") or {}).items():
                for step in job.get("steps") or []:
                    uses = step.get("uses")
                    if not uses or uses.startswith("./") or uses.startswith("docker://"):
                        continue
                    with self.subTest(workflow=rel, job=job_name, uses=uses):
                        self.assertRegex(uses, SHA40_RE, f"Action must be pinned to 40-char SHA: {uses}")
                    if rel == ".github/workflows/release.yml":
                        release_shas.add(uses)

        # Verify ci.yml reuses exact verified SHAs from release.yml and enforces least-privilege permissions
        ci_doc = parsed_by_rel[".github/workflows/ci.yml"][0]
        self.assertEqual(ci_doc.get("permissions"), {"contents": "read"})
        for job_name, job in (ci_doc.get("jobs") or {}).items():
            job_perms = job.get("permissions")
            if job_perms is not None:
                self.assertEqual(job_perms, {"contents": "read"}, f"Job {job_name} must stay read-only")
            for step in job.get("steps") or []:
                uses = step.get("uses")
                if uses and not uses.startswith("./") and not uses.startswith("docker://"):
                    self.assertIn(
                        uses,
                        release_shas,
                        f"ci.yml action {uses} must match a verified SHA from release.yml",
                    )

        # Verify dependabot.yml covers github-actions, gradle, and npm (/web, /docs)
        dep_doc = parsed_by_rel[".github/dependabot.yml"][0]
        self.assertEqual(dep_doc.get("version"), 2)
        updates = dep_doc.get("updates") or []
        covered = {(u.get("package-ecosystem"), u.get("directory")) for u in updates}
        self.assertIn(("github-actions", "/"), covered)
        self.assertIn(("gradle", "/"), covered)
        self.assertIn(("npm", "/web"), covered)
        self.assertIn(("npm", "/docs"), covered)
        for upd in updates:
            self.assertEqual((upd.get("schedule") or {}).get("interval"), "weekly")

        # Verify K8s manifest enables NetworkPolicy, disables ADB by default, and sets PICO_LOG_KMSG=0
        k8s_docs = parsed_by_rel["docker/pico-k8s.yaml"]
        k8s_kinds = {doc.get("kind") for doc in k8s_docs}
        self.assertIn("NetworkPolicy", k8s_kinds, "pico-k8s.yaml must enable NetworkPolicy by default")
        k8s_deploy = next(doc for doc in k8s_docs if doc.get("kind") == "Deployment")
        k8s_env = {
            e["name"]: e["value"]
            for e in k8s_deploy["spec"]["template"]["spec"]["containers"][0].get("env", [])
        }
        self.assertEqual(k8s_env.get("PICO_LOG_KMSG"), "0")
        self.assertEqual(k8s_env.get("PICO_ENABLE_ADB"), "0")

        # Verify compose.yml defaults PICO_LOG_KMSG to 0
        compose_doc = parsed_by_rel["docker/compose.yml"][0]
        compose_env = compose_doc["services"]["pico-onebot"]["environment"]
        self.assertEqual(compose_env.get("PICO_LOG_KMSG"), "${PICO_LOG_KMSG:-0}")


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Standard-library test suite for PicoOnebot operational scripts, shell filters, and YAML configs.

Zero third-party dependencies required; YAML validation uses PyYAML when installed
and skips gracefully otherwise.
"""

from __future__ import annotations

import ast
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import unittest
from urllib.parse import urlparse

try:
    import yaml  # type: ignore[import-untyped]
except ImportError:  # pragma: no cover
    yaml = None

REPO_ROOT = Path(__file__).resolve().parent.parent
PICOQR_PATH = REPO_ROOT / "scripts" / "picoqr.py"

SHA40_RE = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
AWK_17D3_RE = re.compile(r"awk\s+'([^']*17\[Dd\]3[^']*)'")


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

            if state.mode == "flow_success":
                if self.headers.get("Authorization") != "Bearer session-bearer-xyz":
                    self._send_json(401, {"message": "missing or wrong bearer token"})
                    return
                if call_num in (1, 2):
                    # Return the exact same QR URL twice to verify dedup
                    self._send_json(
                        200,
                        {
                            "status": "qr",
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
            with self.subTest(file=str(path.relative_to(REPO_ROOT))):
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


class ProcNetTcpAwkFilterTests(unittest.TestCase):
    TARGET_SCRIPTS = (
        REPO_ROOT / "docker" / "pico-health.sh",
        REPO_ROOT / "docker" / "pico-boot.sh",
        REPO_ROOT / "docker" / "pico-doctor.sh",
    )

    @staticmethod
    def _run_awk(program: str, proc_net_content: str) -> int:
        res = subprocess.run(
            ["awk", program],
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

        for script_path in self.TARGET_SCRIPTS:
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
            with self.subTest(script=str(script.relative_to(REPO_ROOT)), shell=shell):
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
            rel = str(path.relative_to(REPO_ROOT))
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


if __name__ == "__main__":
    unittest.main()

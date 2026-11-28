#!/usr/bin/env python3
"""PicoOneBot 正向 WS 自检客户端(零依赖,标准库 socket + ssl 手写 RFC6455)。

用法:
    python3 tools/obcheck.py [ws://127.0.0.1:3001/] [access_token]
    python3 tools/obcheck.py --url wss://bot.example.com/onebot?role=check --token SECRET

它会:连接 → 收 lifecycle/heartbeat → 依次调用几个 action → 打印结果。
默认只读,不发消息;要测发送用 --group/--user 显式指定目标。

环境变量:
    PICO_OB_URL    默认 WS 地址 (默认 ws://127.0.0.1:3001/)
    PICO_OB_TOKEN  默认访问令牌 (Bearer Token)
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import socket
import ssl
import struct
import sys
import time
from urllib.parse import urlparse

DEFAULT_WS_URL = "ws://127.0.0.1:3001/"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_HANDSHAKE_BYTES = 64 * 1024
MAX_FRAME_SIZE = 16 * 1024 * 1024
MAX_MESSAGE_SIZE = 16 * 1024 * 1024

# 只读自检:默认**不发任何消息**。往一个不存在的群发测试消息会在手表的会话列表里
# 留下垃圾会话,登录后这代价是真实的 —— 要测发送就显式给目标:
#   python3 tools/obcheck.py ws://127.0.0.1:3001/ --group 123456
#   python3 tools/obcheck.py ws://127.0.0.1:3001/ --user 10000
DEFAULT_ACTIONS = (
    ("get_version_info", {}),
    ("get_status", {}),
    ("get_login_info", {}),
    ("_pico_diagnostics", {}),
    ("no_such_action", {}),
)


class WsError(Exception):
    """WebSocket 协议或握手异常。"""


def resolve_ws_url(raw_url: str):
    """校验并解析 ws:///wss:// URL，保留 query，返回 (scheme, host, port, target, display_url)。"""
    raw = (raw_url or "").strip() or DEFAULT_WS_URL
    if any(c in raw for c in ("\r", "\n", " ", "\t")) or any(ord(c) < 0x20 or ord(c) == 0x7F for c in raw):
        raise WsError("URL must not contain whitespace or control characters")
    u = urlparse(raw)
    scheme = u.scheme.lower()
    if scheme == "http":
        scheme = "ws"
    elif scheme == "https":
        scheme = "wss"
    if scheme not in ("ws", "wss"):
        raise WsError(f"unsupported WebSocket URL scheme: {u.scheme!r} (expected ws:// or wss://)")
    host = u.hostname
    if not host:
        raise WsError(f"missing hostname in URL: {raw_url!r}")
    try:
        port = u.port
    except ValueError as exc:
        raise WsError(f"invalid port in URL: {exc}") from exc
    default_port = 443 if scheme == "wss" else 80
    if port is None:
        port = default_port
    target = u.path or "/"
    if u.query:
        target = f"{target}?{u.query}"
    if any(c in target for c in ("\r", "\n", " ", "\t")) or any(ord(c) < 0x20 or ord(c) == 0x7F for c in target):
        raise WsError("request target must not contain whitespace or control characters")
    host_display = f"[{host}]" if ":" in host else host
    port_suffix = "" if port == default_port else f":{port}"
    display_url = f"{scheme}://{host_display}{port_suffix}{u.path or '/'}"
    return scheme, host, port, target, display_url


def format_host_header(host: str, port: int, scheme: str) -> str:
    default_port = 443 if scheme == "wss" else 80
    host_part = f"[{host}]" if ":" in host else host
    return host_part if port == default_port else f"{host_part}:{port}"


def handshake(sock, host: str, port: int, path: str, token: str = "", scheme: str = "ws") -> bytes:
    if not path or any(c in path for c in ("\r", "\n", " ", "\t")) or any(ord(c) < 0x20 or ord(c) == 0x7F for c in path):
        raise WsError("request path must not contain whitespace or control characters")
    if any(c in token for c in ("\r", "\n")):
        raise WsError("token must not contain CR/LF characters")

    key = base64.b64encode(os.urandom(16)).decode("ascii")
    expected_accept = base64.b64encode(
        hashlib.sha1((key + WS_GUID).encode("ascii")).digest()
    ).decode("ascii")

    req = [
        f"GET {path} HTTP/1.1",
        f"Host: {format_host_header(host, port, scheme)}",
        "Upgrade: websocket",
        "Connection: Upgrade",
        f"Sec-WebSocket-Key: {key}",
        "Sec-WebSocket-Version: 13",
    ]
    if token:
        req.append(f"Authorization: Bearer {token}")
    sock.sendall(("\r\n".join(req) + "\r\n\r\n").encode("ascii"))

    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise WsError("connection closed during WebSocket handshake")
        buf += chunk
        if len(buf) > MAX_HANDSHAKE_BYTES and b"\r\n\r\n" not in buf:
            raise WsError(f"handshake response headers exceeded {MAX_HANDSHAKE_BYTES} bytes")

    head_bytes, rest = buf.split(b"\r\n\r\n", 1)
    if len(head_bytes) > MAX_HANDSHAKE_BYTES:
        raise WsError(f"handshake response headers exceeded {MAX_HANDSHAKE_BYTES} bytes")

    head_lines = head_bytes.decode("iso-8859-1", errors="replace").split("\r\n")
    status_line = head_lines[0] if head_lines else ""
    status_parts = status_line.split(" ", 2)
    if len(status_parts) < 2 or not status_parts[0].upper().startswith("HTTP/") or status_parts[1] != "101":
        raise WsError(f"handshake rejected: {status_line or '<empty response>'}")

    headers = {}
    for line in head_lines[1:]:
        if ":" not in line:
            continue
        name, value = line.split(":", 1)
        key = name.strip().lower()
        val = value.strip()
        if not key:
            continue
        if key in headers and headers[key]:
            headers[key] = f"{headers[key]}, {val}" if val else headers[key]
        else:
            headers[key] = val

    upgrade_tokens = {t.strip().lower() for t in headers.get("upgrade", "").split(",") if t.strip()}
    if "websocket" not in upgrade_tokens:
        raise WsError(f"invalid Upgrade header: {headers.get('upgrade')!r}")
    conn_tokens = {t.strip().lower() for t in headers.get("connection", "").split(",") if t.strip()}
    if "upgrade" not in conn_tokens:
        raise WsError(f"invalid Connection header: {headers.get('connection')!r}")
    accept = headers.get("sec-websocket-accept", "")
    if not accept or not hmac.compare_digest(accept, expected_accept):
        raise WsError("Sec-WebSocket-Accept mismatch")

    return rest


def connect_ws(url: str, token: str = "", timeout: float = 10.0):
    """建立 ws:// 或 wss:// (TLS 校验) 连接并完成 RFC6455 握手，返回 (sock, rest, display_url)。"""
    scheme, host, port, target, display_url = resolve_ws_url(url)
    raw_sock = socket.create_connection((host, port), timeout=timeout)
    sock = raw_sock
    try:
        if scheme == "wss":
            ctx = ssl.create_default_context()
            sock = ctx.wrap_socket(raw_sock, server_hostname=host)
        rest = handshake(sock, host, port, target, token=token, scheme=scheme)
    except Exception:
        try:
            sock.close()
        except OSError:
            pass
        raise
    return sock, rest, display_url


def send_frame(sock, opcode: int, payload: bytes):
    if opcode >= 0x8 and len(payload) > 125:
        raise WsError(f"control frame payload too large: {len(payload)} > 125")
    header = bytearray([0x80 | (opcode & 0x0F)])
    n = len(payload)
    if n < 126:
        header.append(0x80 | n)
    elif n <= 0xFFFF:
        header.append(0x80 | 126)
        header += struct.pack(">H", n)
    else:
        header.append(0x80 | 127)
        header += struct.pack(">Q", n)
    mask = os.urandom(4)
    header += mask
    masked_payload = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
    sock.sendall(bytes(header) + masked_payload)


def send_text(sock, text: str):
    send_frame(sock, 0x1, text.encode("utf-8"))


def send_pong(sock, payload: bytes = b""):
    send_frame(sock, 0xA, payload[:125])


def send_close(sock, code: int = 1000):
    try:
        send_frame(sock, 0x8, struct.pack(">H", code))
    except OSError:
        pass


class Reader:
    def __init__(self, sock, rest: bytes = b"", max_frame_size: int = MAX_FRAME_SIZE, max_message_size: int = MAX_MESSAGE_SIZE):
        self.sock = sock
        self.buf = rest
        self.max_frame_size = max_frame_size
        self.max_message_size = max_message_size
        self._frag_opcode = None
        self._frag_data = bytearray()

    def need(self, n: int) -> bytes:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("connection closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def read_raw_frame(self):
        b0, b1 = self.need(2)
        fin = bool(b0 & 0x80)
        rsv = b0 & 0x70
        if rsv != 0:
            raise WsError(f"non-zero RSV bits: {rsv:#x}")
        opcode = b0 & 0x0F
        masked = bool(b1 & 0x80)
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack(">H", self.need(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self.need(8))[0]
            if n >= (1 << 63):
                raise WsError("invalid 64-bit frame length (MSB set)")
        if opcode >= 0x8:
            if opcode not in (0x8, 0x9, 0xA):
                raise WsError(f"unsupported control opcode: {opcode:#x}")
            if not fin:
                raise WsError("fragmented control frame is not allowed")
            if n > 125:
                raise WsError(f"control frame too large: {n} > 125")
        elif opcode not in (0x0, 0x1, 0x2):
            raise WsError(f"unsupported data opcode: {opcode:#x}")
        if n > self.max_frame_size:
            raise WsError(f"frame payload too large: {n} > {self.max_frame_size}")
        mask = self.need(4) if masked else None
        data = self.need(n)
        if mask:
            data = bytes(b ^ mask[i & 3] for i, b in enumerate(data))
        return fin, opcode, data

    def frame(self):
        """读取完整消息或控制帧，透明重组分片帧并允许控制帧穿插。"""
        while True:
            fin, opcode, data = self.read_raw_frame()
            if opcode >= 0x8:
                return opcode, data
            if opcode in (0x1, 0x2):
                if self._frag_opcode is not None:
                    raise WsError("unexpected data frame while fragmented message is in progress")
                if fin:
                    if len(data) > self.max_message_size:
                        raise WsError(f"message too large: {len(data)} > {self.max_message_size}")
                    return opcode, data
                if len(data) > self.max_message_size:
                    raise WsError(f"fragmented message too large: {len(data)} > {self.max_message_size}")
                self._frag_opcode = opcode
                self._frag_data = bytearray(data)
            elif opcode == 0x0:
                if self._frag_opcode is None:
                    raise WsError("unexpected continuation frame")
                if len(self._frag_data) + len(data) > self.max_message_size:
                    raise WsError(f"fragmented message exceeded {self.max_message_size} bytes")
                self._frag_data.extend(data)
                if fin:
                    out_op = self._frag_opcode
                    out_data = bytes(self._frag_data)
                    self._frag_opcode = None
                    self._frag_data = bytearray()
                    return out_op, out_data


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="PicoOnebot 正向 WebSocket (ws/wss) 自检客户端 (零外部依赖)。",
        epilog="环境变量: PICO_OB_URL (默认 ws://127.0.0.1:3001/), PICO_OB_TOKEN。",
    )
    parser.add_argument("pos_url", nargs="?", default=None, help=f"WebSocket URL (默认 {DEFAULT_WS_URL})")
    parser.add_argument("pos_token", nargs="?", default=None, help="访问令牌 (默认取 PICO_OB_TOKEN)")
    parser.add_argument("--url", dest="opt_url", default=None, help="WebSocket URL (覆盖位置参数与 PICO_OB_URL)")
    parser.add_argument("--token", dest="opt_token", default=None, help="访问令牌 (覆盖位置参数与 PICO_OB_TOKEN)")
    parser.add_argument("--group", type=int, default=None, help="可选: 追加一条 send_group_msg 测试消息到指定群号")
    parser.add_argument("--user", type=int, default=None, help="可选: 追加一条 send_private_msg 测试消息到指定 QQ 号")
    parser.add_argument("--text", default="ping from obcheck", help="配合 --group/--user 使用的测试文本")
    parser.add_argument("--timeout", type=float, default=15.0, help="接收事件与响应的总时长秒数 (默认 15)")
    parser.add_argument("--connect-timeout", type=float, default=10.0, help="建连与握手超时秒数 (默认 10)")
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.timeout <= 0:
        parser.error("--timeout must be greater than 0")
    if args.connect_timeout <= 0:
        parser.error("--connect-timeout must be greater than 0")

    url = args.opt_url or args.pos_url or os.environ.get("PICO_OB_URL") or DEFAULT_WS_URL
    if args.opt_token is not None:
        token = args.opt_token
    elif args.pos_token is not None:
        token = args.pos_token
    else:
        token = os.environ.get("PICO_OB_TOKEN", "")

    actions = list(DEFAULT_ACTIONS)
    if args.group is not None:
        actions.append(("send_group_msg", {"group_id": args.group, "message": args.text}))
    if args.user is not None:
        actions.append(("send_private_msg", {"user_id": args.user, "message": args.text}))

    sock = None
    try:
        sock, rest, display_url = connect_ws(url, token=token, timeout=args.connect_timeout)
        print(f"[ok] connected {display_url}")
        reader = Reader(sock, rest)

        for i, (action, params) in enumerate(actions):
            send_text(sock, json.dumps({"action": action, "params": params, "echo": str(i)}))
        print(f"[..] {len(actions)} actions sent, listening {args.timeout:g}s\n")

        deadline = time.monotonic() + args.timeout
        events, replies = 0, 0
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            sock.settimeout(remaining)
            try:
                opcode, data = reader.frame()
            except (EOFError, socket.timeout, TimeoutError):
                break
            if opcode == 0x8:
                print("[closed by server]")
                break
            if opcode == 0x9:
                send_pong(sock, data)
                continue
            if opcode != 0x1:
                continue
            try:
                msg = json.loads(data.decode("utf-8", "replace"))
            except ValueError:
                print("[warn] ignored non-JSON text frame", file=sys.stderr)
                continue
            if not isinstance(msg, dict):
                continue
            if "echo" in msg:
                replies += 1
                echo_raw = msg.get("echo")
                try:
                    idx = int(echo_raw)
                    action = actions[idx][0] if 0 <= idx < len(actions) else f"echo:{echo_raw}"
                except (TypeError, ValueError):
                    action = f"echo:{echo_raw}"
                status = msg.get("status")
                retcode = msg.get("retcode")
                detail = msg.get("message") or json.dumps(msg.get("data"), ensure_ascii=False)[:220]
                print(f"[resp] {action:20s} status={status} retcode={retcode} {detail}")
            else:
                events += 1
                pt = msg.get("post_type")
                if pt == "meta_event":
                    sub = msg.get("meta_event_type")
                    extra = msg.get("sub_type") or ""
                    if sub == "heartbeat":
                        st = msg.get("status") or {}
                        extra = f"online={st.get('online')} state={(st.get('pico') or {}).get('state')}"
                    print(f"[event] meta/{sub} {extra}")
                else:
                    print(f"[event] {pt} {json.dumps(msg, ensure_ascii=False)[:200]}")

        print(f"\n[done] replies={replies} events={events}")
        send_close(sock)
        return 0
    except WsError as exc:
        print(f"[error] websocket error: {exc}", file=sys.stderr)
        return 1
    except (ssl.SSLError, OSError, ValueError) as exc:
        print(f"[error] connection failed: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n[interrupted]", file=sys.stderr)
        return 130
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass


if __name__ == "__main__":
    sys.exit(main())

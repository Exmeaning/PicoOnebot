#!/usr/bin/env python3
"""PicoOneBot 正向 WS 自检客户端(零依赖,标准库 socket 手写 RFC6455)。

用法:
    python3 tools/obcheck.py [ws://192.168.240.112:3001/] [access_token]

它会:连接 → 收 lifecycle/heartbeat → 依次调用几个 action → 打印结果。
默认只读,不发消息;要测发送用 --group/--user 显式指定目标。
"""
import base64
import json
import os
import socket
import struct
import sys
import time
from urllib.parse import urlparse

# 只读自检:默认**不发任何消息**。往一个不存在的群发测试消息会在手表的会话列表里
# 留下垃圾会话,登录后这代价是真实的 —— 要测发送就显式给目标:
#   python3 tools/obcheck.py ws://host:3001/ "" --group 123456
#   python3 tools/obcheck.py ws://host:3001/ "" --user 10000
ACTIONS = [
    ("get_version_info", {}),
    ("get_status", {}),
    ("get_login_info", {}),
    ("_pico_diagnostics", {}),
    ("no_such_action", {}),
]


def add_send_action(argv):
    """--group <id> / --user <id> [--text <s>] 时才追加一条真实发送。"""
    text = "ping from obcheck"
    if "--text" in argv:
        text = argv[argv.index("--text") + 1]
    if "--group" in argv:
        gid = int(argv[argv.index("--group") + 1])
        ACTIONS.append(("send_group_msg", {"group_id": gid, "message": text}))
    if "--user" in argv:
        uid = int(argv[argv.index("--user") + 1])
        ACTIONS.append(("send_private_msg", {"user_id": uid, "message": text}))


def handshake(sock, host, port, path, token):
    key = base64.b64encode(os.urandom(16)).decode()
    req = [
        f"GET {path} HTTP/1.1",
        f"Host: {host}:{port}",
        "Upgrade: websocket",
        "Connection: Upgrade",
        f"Sec-WebSocket-Key: {key}",
        "Sec-WebSocket-Version: 13",
    ]
    if token:
        req.append(f"Authorization: Bearer {token}")
    sock.sendall(("\r\n".join(req) + "\r\n\r\n").encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise SystemExit("connection closed during handshake")
        buf += chunk
    head = buf.split(b"\r\n\r\n", 1)[0].decode(errors="replace")
    if "101" not in head.splitlines()[0]:
        raise SystemExit("handshake rejected:\n" + head)
    return buf.split(b"\r\n\r\n", 1)[1]


def send_text(sock, text):
    payload = text.encode()
    header = bytearray([0x81])
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
    sock.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))


class Reader:
    def __init__(self, sock, rest=b""):
        self.sock = sock
        self.buf = rest

    def need(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def frame(self):
        b0, b1 = self.need(2)
        opcode = b0 & 0x0F
        masked = b1 & 0x80
        n = b1 & 0x7F
        if n == 126:
            n = struct.unpack(">H", self.need(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self.need(8))[0]
        mask = self.need(4) if masked else None
        data = self.need(n)
        if mask:
            data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        return opcode, data


def main():
    argv = sys.argv[1:]
    positional = [a for a in argv if not a.startswith("--")]
    # 跳过被 --group/--user/--text 吃掉的值
    flagged = set()
    for f in ("--group", "--user", "--text"):
        if f in argv and argv.index(f) + 1 < len(argv):
            flagged.add(argv[argv.index(f) + 1])
    positional = [a for a in positional if a not in flagged]
    url = positional[0] if positional else "ws://192.168.240.112:3001/"
    token = positional[1] if len(positional) > 1 else ""
    add_send_action(argv)
    u = urlparse(url)
    host, port = u.hostname, u.port or 80
    path = u.path or "/"

    sock = socket.create_connection((host, port), timeout=10)
    rest = handshake(sock, host, port, path, token)
    print(f"[ok] connected {url}")
    reader = Reader(sock, rest)

    for i, (action, params) in enumerate(ACTIONS):
        send_text(sock, json.dumps({"action": action, "params": params, "echo": str(i)}))
    print(f"[..] {len(ACTIONS)} actions sent, listening 15s\n")

    sock.settimeout(15)
    deadline = time.time() + 15
    events, replies = 0, 0
    while time.time() < deadline:
        try:
            opcode, data = reader.frame()
        except (EOFError, socket.timeout):
            break
        if opcode != 0x1:
            continue
        msg = json.loads(data.decode())
        if "echo" in msg:
            replies += 1
            action = ACTIONS[int(msg["echo"])][0]
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
                    st = msg.get("status", {})
                    extra = f"online={st.get('online')} state={st.get('pico', {}).get('state')}"
                print(f"[event] meta/{sub} {extra}")
            else:
                print(f"[event] {pt} {json.dumps(msg, ensure_ascii=False)[:200]}")

    print(f"\n[done] replies={replies} events={events}")
    sock.close()


if __name__ == "__main__":
    main()

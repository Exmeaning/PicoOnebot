#!/usr/bin/env python3
"""PicoOneBot 事件流 tail(零依赖,复用 obcheck 的手写 WS)。

用法:
    python3 tools/obtail.py [ws://192.168.240.112:3001/] [access_token]

每条事件压成一行,便于一边发测试消息一边核对解析结果(--raw 打完整 JSON)。
心跳默认折叠成计数,不刷屏(要看心跳加 --heartbeat)。
"""
import json
import os
import socket
import struct
import sys
from urllib.parse import urlparse

from obcheck import Reader, handshake  # 同目录复用


def seg_brief(message):
    """把 segment 数组压成一行可读文本,保留类型信息(排查媒体段时要看类型)。"""
    if isinstance(message, str):
        return message
    out = []
    for s in message or []:
        t = s.get("type")
        d = s.get("data", {})
        if t == "text":
            out.append(d.get("text", ""))
        elif t == "at":
            out.append("@" + str(d.get("qq")))
        elif t == "face":
            out.append("[face:%s]" % d.get("id"))
        elif t == "reply":
            out.append("[reply:%s]" % d.get("id"))
        elif t == "image":
            out.append("[image]")
        elif t == "forward":
            out.append("[forward:%s]" % d.get("id"))
        else:
            out.append("[%s:%s]" % (t, json.dumps(d, ensure_ascii=False)[:80]))
    return "".join(out)


def send_pong(sock, payload):
    header = bytearray([0x8A])
    n = len(payload)
    header.append(0x80 | n)
    mask = os.urandom(4)
    header += mask
    sock.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))


def brief(ev):
    post = ev.get("post_type")
    if post in ("message", "message_sent"):
        mt = ev.get("message_type")
        where = (
            "group %s" % ev.get("group_id") if mt == "group" else "private %s" % ev.get("user_id")
        )
        tgt = ev.get("target_id")
        return "[%s] %s%s from %s mid=%s seq=%s nick=%s :: %s" % (
            post,
            where,
            (" ->%s" % tgt) if tgt else "",
            ev.get("user_id"),
            ev.get("message_id"),
            ev.get("message_seq"),
            (ev.get("sender") or {}).get("nickname", ""),
            seg_brief(ev.get("message")),
        )
    if post == "notice":
        return "[notice] %s %s" % (
            ev.get("notice_type"),
            json.dumps(
                {k: v for k, v in ev.items() if k not in ("post_type", "time", "self_id")},
                ensure_ascii=False,
            )[:200],
        )
    if post == "request":
        return "[request] " + json.dumps(ev, ensure_ascii=False)[:200]
    if post == "meta_event":
        return "[meta] %s %s" % (ev.get("meta_event_type"), ev.get("sub_type", ""))
    return "[?] " + json.dumps(ev, ensure_ascii=False)[:200]


def main():
    argv = sys.argv[1:]
    show_hb = "--heartbeat" in argv
    raw = "--raw" in argv
    positional = [a for a in argv if not a.startswith("--")]
    url = positional[0] if positional else "ws://192.168.240.112:3001/"
    token = positional[1] if len(positional) > 1 else ""

    u = urlparse(url)
    host, port, path = u.hostname, u.port or 3001, u.path or "/"
    sock = socket.create_connection((host, port), timeout=10)
    rest = handshake(sock, host, port, path, token)
    print("[ok] tailing %s (Ctrl-C to stop)" % url, flush=True)
    sock.settimeout(None)

    hb = 0
    reader = Reader(sock, rest)
    while True:
        opcode, data = reader.frame()
        if opcode == 0x8:
            print("[closed by server]", flush=True)
            return
        if opcode == 0x9:  # ping:长连接不回 pong 会被服务端判死
            send_pong(sock, data)
            continue
        if opcode not in (0x1, 0x2):
            continue
        try:
            ev = json.loads(data.decode("utf-8", "replace"))
        except ValueError:
            continue
        if "echo" in ev or "retcode" in ev:
            continue  # 动作应答,tail 只关心事件
        if ev.get("meta_event_type") == "heartbeat" and not show_hb:
            hb += 1
            if hb % 12 == 0:
                print("[meta] heartbeat x%d online=%s"
                      % (hb, (ev.get("status") or {}).get("online")), flush=True)
            continue
        print(json.dumps(ev, ensure_ascii=False) if raw else brief(ev), flush=True)


if __name__ == "__main__":
    main()

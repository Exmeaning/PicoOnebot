#!/usr/bin/env python3
"""PicoOneBot 事件流 tail(零依赖,复用 obcheck 的手写 RFC6455 WS/WSS 客户端)。

用法:
    python3 tools/obtail.py [ws://127.0.0.1:3001/] [access_token]
    python3 tools/obtail.py --url wss://bot.example.com/onebot --token SECRET --raw

每条事件压成一行,便于一边发测试消息一边核对解析结果(--raw 打完整 JSON)。
心跳默认折叠成计数,不刷屏(要看心跳加 --heartbeat)。

环境变量:
    PICO_OB_URL    默认 WS 地址 (默认 ws://127.0.0.1:3001/)
    PICO_OB_TOKEN  默认访问令牌 (Bearer Token)
"""
import argparse
import json
import os
import ssl
import sys

try:
    from obcheck import DEFAULT_WS_URL, Reader, WsError, connect_ws, send_close, send_pong
except ImportError:  # 支持作为 tools.obtail 导入
    from tools.obcheck import DEFAULT_WS_URL, Reader, WsError, connect_ws, send_close, send_pong


def seg_brief(message):
    """把 segment 数组压成一行可读文本,保留类型信息(排查媒体段时要看类型)。"""
    if isinstance(message, str):
        return message
    out = []
    for s in message or []:
        if not isinstance(s, dict):
            continue
        t = s.get("type")
        d = s.get("data") or {}
        if not isinstance(d, dict):
            d = {}
        if t == "text":
            out.append(str(d.get("text", "")))
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


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="PicoOnebot 正向 WebSocket (ws/wss) 事件流 tail (零外部依赖)。",
        epilog="环境变量: PICO_OB_URL (默认 ws://127.0.0.1:3001/), PICO_OB_TOKEN。",
    )
    parser.add_argument("pos_url", nargs="?", default=None, help=f"WebSocket URL (默认 {DEFAULT_WS_URL})")
    parser.add_argument("pos_token", nargs="?", default=None, help="访问令牌 (默认取 PICO_OB_TOKEN)")
    parser.add_argument("--url", dest="opt_url", default=None, help="WebSocket URL (覆盖位置参数与 PICO_OB_URL)")
    parser.add_argument("--token", dest="opt_token", default=None, help="访问令牌 (覆盖位置参数与 PICO_OB_TOKEN)")
    parser.add_argument("--raw", action="store_true", help="输出原始完整 JSON 事件")
    parser.add_argument("--heartbeat", action="store_true", help="逐条打印心跳事件（默认每 12 次折叠一次）")
    parser.add_argument("--connect-timeout", type=float, default=10.0, help="建连与握手超时秒数 (默认 10)")
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.connect_timeout <= 0:
        parser.error("--connect-timeout must be greater than 0")

    url = args.opt_url or args.pos_url or os.environ.get("PICO_OB_URL") or DEFAULT_WS_URL
    if args.opt_token is not None:
        token = args.opt_token
    elif args.pos_token is not None:
        token = args.pos_token
    else:
        token = os.environ.get("PICO_OB_TOKEN", "")

    sock = None
    try:
        sock, rest, display_url = connect_ws(url, token=token, timeout=args.connect_timeout)
        print(f"[ok] tailing {display_url} (Ctrl-C to stop)", flush=True)
        sock.settimeout(None)

        hb = 0
        reader = Reader(sock, rest)
        while True:
            opcode, data = reader.frame()
            if opcode == 0x8:
                print("[closed by server]", flush=True)
                return 0
            if opcode == 0x9:  # ping: 长连接不回 pong 会被服务端判死
                send_pong(sock, data)
                continue
            if opcode not in (0x1, 0x2):
                continue
            try:
                ev = json.loads(data.decode("utf-8", "replace"))
            except ValueError:
                continue
            if not isinstance(ev, dict):
                continue
            if "echo" in ev or "retcode" in ev:
                continue  # 动作应答,tail 只关心事件
            if ev.get("meta_event_type") == "heartbeat" and not args.heartbeat:
                hb += 1
                if hb % 12 == 0:
                    print(
                        "[meta] heartbeat x%d online=%s"
                        % (hb, (ev.get("status") or {}).get("online")),
                        flush=True,
                    )
                continue
            print(json.dumps(ev, ensure_ascii=False) if args.raw else brief(ev), flush=True)
    except KeyboardInterrupt:
        if sock is not None:
            send_close(sock)
        return 0
    except EOFError:
        print("[error] connection closed unexpectedly", file=sys.stderr)
        return 1
    except WsError as exc:
        print(f"[error] websocket error: {exc}", file=sys.stderr)
        return 1
    except (ssl.SSLError, OSError, ValueError) as exc:
        print(f"[error] connection failed: {exc}", file=sys.stderr)
        return 1
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass


if __name__ == "__main__":
    sys.exit(main())

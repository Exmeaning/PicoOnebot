#!/usr/bin/env python3
"""调用任意 OneBot 动作(零依赖,走内置 HTTP API)。

用法:
    python3 tools/obact.py <action> '<json params>' [--host 192.168.240.112:3001] [--token T]
    python3 tools/obact.py get_forward_msg '{"message_id": 4}'
    python3 tools/obact.py send_group_msg  '{"group_id":316897519,"message":[{"type":"text","data":{"text":"hi"}}]}'

参数也可以从 stdin 读(消息体里带图片 base64 时命令行塞不下):
    cat payload.json | python3 tools/obact.py send_group_msg -
"""
import json
import sys
import urllib.request

DEFAULT_HOST = "192.168.240.112:3001"


def call(action, params, host=DEFAULT_HOST, token="", timeout=120):
    body = json.dumps(params).encode("utf-8")
    req = urllib.request.Request(
        "http://%s/%s" % (host, action),
        data=body,
        headers={"Content-Type": "application/json"},
    )
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def main():
    argv = sys.argv[1:]
    host, token = DEFAULT_HOST, ""
    rest = []
    i = 0
    while i < len(argv):
        if argv[i] == "--host":
            host = argv[i + 1]
            i += 2
        elif argv[i] == "--token":
            token = argv[i + 1]
            i += 2
        else:
            rest.append(argv[i])
            i += 1
    if not rest:
        print(__doc__)
        return 2

    action = rest[0]
    raw = rest[1] if len(rest) > 1 else "{}"
    if raw == "-":
        raw = sys.stdin.read()
    params = json.loads(raw) if raw.strip() else {}

    resp = call(action, params, host, token)
    # 应答里可能带几兆的 base64,截断后再打,否则终端直接卡住
    def shorten(o):
        if isinstance(o, dict):
            return {k: ("<%d chars>" % len(v) if k == "base64" and isinstance(v, str) else shorten(v))
                    for k, v in o.items()}
        if isinstance(o, list):
            return [shorten(x) for x in o]
        return o

    print(json.dumps(shorten(resp), ensure_ascii=False, indent=2))
    return 0 if resp.get("retcode") == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""调用任意 OneBot 动作(零依赖,走内置 HTTP/HTTPS API)。

用法:
    python3 tools/obact.py <action> ['<json params>'] [--url http://127.0.0.1:3001] [--token T]
    python3 tools/obact.py get_forward_msg '{"message_id": 4}'
    python3 tools/obact.py send_group_msg  '{"group_id":316897519,"message":[{"type":"text","data":{"text":"hi"}}]}'

参数也可以从 stdin 读(消息体里带图片 base64 时命令行塞不下):
    cat payload.json | python3 tools/obact.py send_group_msg -

环境变量:
    PICO_OB_URL    默认服务地址 (默认 http://127.0.0.1:3001)
    PICO_OB_TOKEN  默认访问令牌 (Bearer Token)
"""
import argparse
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_URL = "http://127.0.0.1:3001"


def normalize_http_url(raw_url: str) -> urllib.parse.SplitResult:
    raw = (raw_url or "").strip()
    if not raw:
        raw = DEFAULT_URL
    if "://" not in raw:
        raw = "http://" + raw
    parts = urllib.parse.urlsplit(raw)
    scheme = parts.scheme.lower()
    if scheme == "ws":
        scheme = "http"
    elif scheme == "wss":
        scheme = "https"
    if scheme not in ("http", "https"):
        raise ValueError(f"unsupported URL scheme: {parts.scheme!r} (expected http/https)")
    if not parts.hostname:
        raise ValueError(f"missing hostname in URL: {raw_url!r}")
    # Validate port early (accessing parts.port raises ValueError on invalid ports)
    _ = parts.port
    if any(c in raw for c in ("\r", "\n")):
        raise ValueError("URL must not contain CR/LF characters")
    return parts._replace(scheme=scheme)


def build_action_url(base: urllib.parse.SplitResult, action: str) -> str:
    act = (action or "").strip()
    if not act or any(c in act for c in ("/", "?", "#", "\r", "\n", " ", "\t")):
        raise ValueError(f"invalid action name: {action!r}")
    base_path = base.path.rstrip("/")
    full_path = f"{base_path}/{urllib.parse.quote(act, safe='')}"
    return urllib.parse.urlunsplit((base.scheme, base.netloc, full_path, base.query, ""))


def call(action: str, params: dict, url: str = DEFAULT_URL, token: str = "", timeout: float = 120.0):
    if timeout <= 0:
        raise ValueError("timeout must be positive")
    if any(c in token for c in ("\r", "\n")):
        raise ValueError("token must not contain CR/LF characters")
    base = normalize_http_url(url)
    target_url = build_action_url(base, action)
    body = json.dumps(params, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        target_url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    if token:
        req.add_header("Authorization", "Bearer " + token)
    context = ssl.create_default_context() if base.scheme == "https" else None
    with urllib.request.urlopen(req, timeout=timeout, context=context) as resp:
        raw_body = resp.read().decode("utf-8", "replace")
    try:
        data = json.loads(raw_body)
    except json.JSONDecodeError as exc:
        raise ValueError(f"server returned non-JSON response: {exc.msg}") from exc
    if not isinstance(data, dict):
        raise ValueError(f"expected JSON object response, got {type(data).__name__}")
    return data


def shorten(obj):
    if isinstance(obj, dict):
        return {
            k: ("<%d chars>" % len(v) if k == "base64" and isinstance(v, str) else shorten(v))
            for k, v in obj.items()
        }
    if isinstance(obj, list):
        return [shorten(x) for x in obj]
    return obj


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="调用 PicoOnebot OneBot v11 HTTP/HTTPS 动作 (零外部依赖)。",
        epilog="环境变量: PICO_OB_URL (默认 http://127.0.0.1:3001), PICO_OB_TOKEN。",
    )
    parser.add_argument("action", help="OneBot 动作名，例如 get_login_info / send_group_msg")
    parser.add_argument(
        "params",
        nargs="?",
        default="{}",
        help="JSON 对象参数字符串，或 '-' 从 stdin 读取 (默认 '{}')",
    )
    parser.add_argument(
        "--url",
        default=None,
        help=f"服务基础 URL (默认取 PICO_OB_URL 或 {DEFAULT_URL})",
    )
    parser.add_argument(
        "--host",
        default=None,
        help="兼容旧用法: 指定 host[:port] 或完整 URL (优先于 PICO_OB_URL)",
    )
    parser.add_argument(
        "--token",
        default=None,
        help="Bearer 访问令牌 (默认取 PICO_OB_TOKEN)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=120.0,
        help="HTTP 请求超时秒数 (默认 120)",
    )
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.url and args.host:
        parser.error("--url and --host cannot be used together")
    if args.timeout <= 0:
        parser.error("--timeout must be greater than 0")

    url = args.url or args.host or os.environ.get("PICO_OB_URL") or DEFAULT_URL
    token = args.token if args.token is not None else os.environ.get("PICO_OB_TOKEN", "")

    raw_params = sys.stdin.read() if args.params == "-" else args.params
    if raw_params.strip():
        try:
            params = json.loads(raw_params)
        except json.JSONDecodeError as exc:
            print(f"[error] invalid JSON params: {exc.msg} (pos {exc.pos})", file=sys.stderr)
            return 2
        if not isinstance(params, dict):
            print("[error] params must be a JSON object", file=sys.stderr)
            return 2
    else:
        params = {}

    try:
        resp = call(args.action, params, url=url, token=token, timeout=args.timeout)
    except ValueError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 2
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = exc.read().decode("utf-8", "replace").strip()[:300]
        except OSError:
            pass
        suffix = f": {detail}" if detail else ""
        print(f"[error] HTTP {exc.code} {exc.reason}{suffix}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"[error] request failed: {exc.reason}", file=sys.stderr)
        return 1
    except (ssl.SSLError, TimeoutError, OSError) as exc:
        print(f"[error] network error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n[interrupted]", file=sys.stderr)
        return 130

    print(json.dumps(shorten(resp), ensure_ascii=False, indent=2))
    return 0 if resp.get("retcode") == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

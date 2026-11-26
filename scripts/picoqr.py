#!/usr/bin/env python3
"""PicoOnebot 终端登录二维码渲染工具

通过 WebApi (默认 http://<IP>:6099/api/qr) 获取登录状态与二维码 URL，
并在终端以 ANSI 字符块渲染二维码。
"""
import argparse
import json
import os
import sys
import time
from typing import Optional
import urllib.error
import urllib.request


def _parse_json_bytes(raw: bytes) -> dict:
    try:
        parsed = json.loads(raw.decode("utf-8", "replace"))
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}


def _request_json(url: str, method: str = "GET", token: str = "", payload: Optional[dict] = None, timeout: float = 5.0):
    """发送 HTTP JSON 请求，返回 (data, status_code, error_message)。"""
    headers = {"User-Agent": "PicoQr/2.0"}
    data_bytes = None
    if payload is not None:
        data_bytes = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data_bytes, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return _parse_json_bytes(resp.read()), resp.status, ""
    except urllib.error.HTTPError as err:
        body = {}
        try:
            body = _parse_json_bytes(err.read())
        except Exception:
            pass
        msg = str(body.get("message") or err.reason or f"HTTP {err.code}")
        return body, err.code, msg
    except (urllib.error.URLError, TimeoutError, OSError) as err:
        return None, 0, str(err)


def exchange_session_token(api_base: str, credential: str):
    """尝试通过 /api/auth/login 用控制台密码换取会话令牌。

    返回 (session_token, status_code, error_message)。
    status_code == 200 表示成功（或旧版服务端无 /api/auth/login 时回退直传）。
    """
    if not credential:
        return "", 200, ""
    url = f"{api_base.rstrip('/')}/api/auth/login"
    data, code, err = _request_json(url, method="POST", payload={"token": credential}, timeout=5.0)
    if code == 200 and isinstance(data, dict):
        if data.get("mustChangePassword"):
            return "", 428, "首次使用必须先修改控制台密码"
        session = data.get("token")
        return (str(session) if session else credential), 200, ""
    if code == 404:
        # 兼容未提供 /api/auth/login 的旧版服务端
        return credential, 200, ""
    return "", code, err


def fetch_qr(api_base: str, token: str = "", refresh: bool = False):
    """请求 WebApi 获取或刷新二维码状态，返回 (data, status_code, error_message)。"""
    base = api_base.rstrip("/")
    if refresh:
        return _request_json(f"{base}/api/qr/refresh", method="POST", token=token, payload={}, timeout=8.0)
    return _request_json(f"{base}/api/qr", method="GET", token=token, payload=None, timeout=5.0)


def report_auth_error(api_base: str, status_code: int, message: str) -> int:
    if status_code == 401:
        print(
            f"[picoqr] 鉴权失败 (HTTP 401): {message}。"
            "请通过 --token 或环境变量 PICO_WEBUI_TOKEN 提供正确的控制台密码/会话令牌。",
            file=sys.stderr,
        )
        return 2
    if status_code == 428:
        print(
            f"[picoqr] 需要先初始化密码 (HTTP 428): {message}。"
            f"请先在浏览器打开 {api_base}/ 将初始密码修改为至少 8 位的新密码，再使用新密码运行 picoqr。",
            file=sys.stderr,
        )
        return 3
    return 0


def print_terminal_qr(url: str, light_mode: bool = False) -> bool:
    """将 URL 渲染为终端二维码字符块。优先使用 qrcode 库。"""
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(url)
        qr.print_ascii(invert=not light_mode)
        return True
    except ImportError:
        pass
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="PicoOnebot 登录二维码获取与渲染")
    parser.add_argument(
        "--api",
        default=os.getenv("PICO_API", "http://localhost:6099"),
        help="PicoOnebot WebApi 基础地址 (默认 http://localhost:6099)",
    )
    parser.add_argument(
        "--token",
        default=os.getenv("PICO_WEBUI_TOKEN", ""),
        help="WebUI 控制台密码或会话令牌 (默认读取环境变量 PICO_WEBUI_TOKEN)",
    )
    parser.add_argument("--url", help="直接渲染指定的 URL")
    parser.add_argument("--refresh", action="store_true", help="强制刷新二维码")
    parser.add_argument("--light", action="store_true", help="浅色终端背景模式")
    parser.add_argument("--once", action="store_true", help="只获取一次，不循环轮询")
    parser.add_argument("--timeout", type=float, default=120.0, help="轮询超时时间(秒)")
    args, _ = parser.parse_known_args()

    # 1. 直接指定 URL 渲染
    if args.url:
        print(f"\n[链接] {args.url}\n")
        if not print_terminal_qr(args.url, args.light):
            print("提示: 可在支持的终端安装 python3-qrcode (pip install qrcode) 以查看字符点阵码。")
        return 0

    api_base = args.api.rstrip("/")
    if api_base.endswith(":3001"):
        api_base = api_base[:-5] + ":6099"

    print(f"[picoqr] 正在连接 PicoOnebot 控制台: {api_base}")

    deadline = time.monotonic() + (5.0 if args.once else args.timeout)
    credential = args.token or ""
    auth_token = ""
    login_done = not bool(credential)
    refresh_pending = bool(args.refresh)
    last_status = ""
    last_printed_url = ""
    last_error = ""

    while time.monotonic() <= deadline:
        if not login_done:
            session_token, code, err = exchange_session_token(api_base, credential)
            if code in (401, 428):
                return report_auth_error(api_base, code, err)
            if code == 0:
                last_error = err
                if last_status != "unreachable":
                    print(f"[picoqr] 正在等待 WebApi 服务就绪 ({api_base})...", file=sys.stderr)
                    last_status = "unreachable"
                if args.once:
                    break
                time.sleep(2)
                continue
            if code != 200:
                print(f"[picoqr] 登录接口返回异常 (HTTP {code}): {err}", file=sys.stderr)
                return 1
            auth_token = session_token
            login_done = True

        if refresh_pending:
            _, rcode, rerr = fetch_qr(api_base, token=auth_token, refresh=True)
            if rcode in (401, 428):
                return report_auth_error(api_base, rcode, rerr)
            refresh_pending = False
            if rcode == 0:
                last_error = rerr
                if args.once:
                    break
                time.sleep(2)
                continue
            time.sleep(1)

        data, code, err = fetch_qr(api_base, token=auth_token, refresh=False)
        if code in (401, 428):
            return report_auth_error(api_base, code, err)
        if code == 0 or data is None:
            last_error = err or "无法连接 WebApi"
            if last_status != "unreachable":
                print(f"[picoqr] 正在等待 WebApi 服务就绪 ({api_base})...", file=sys.stderr)
                last_status = "unreachable"
            if args.once:
                break
            time.sleep(2)
            continue
        if code != 200:
            last_error = f"HTTP {code}: {err}"
            print(f"[picoqr] WebApi 返回错误 ({last_error})", file=sys.stderr)
            if args.once:
                return 1
            time.sleep(2)
            continue

        if data.get("status") == "online" or data.get("online"):
            uin = data.get("user_id", "未知")
            print(f"[picoqr] PicoOneBot 已登录在线！当前 QQ: {uin}")
            return 0

        status = data.get("status")
        url = data.get("url", "")
        expires_in = data.get("expiresIn", 0)
        is_scanned = data.get("scanned", False)

        if is_scanned:
            if last_status != "scanned":
                print("[picoqr] 手机 QQ 已扫码，等待在手机上确认登录...", file=sys.stderr)
                last_status = "scanned"
            if args.once:
                return 0
            time.sleep(2)
            continue

        if status == "expired":
            last_printed_url = ""
            if last_status != "expired":
                print("[picoqr] 二维码已过期，正在请求刷新...", file=sys.stderr)
                last_status = "expired"
            _, rcode, rerr = fetch_qr(api_base, token=auth_token, refresh=True)
            if rcode in (401, 428):
                return report_auth_error(api_base, rcode, rerr)
            if args.once:
                break
            time.sleep(2)
            continue

        if status == "waiting_qr" or not url:
            if last_status != "waiting":
                print("[picoqr] 等待生成登录二维码...", file=sys.stderr)
                last_status = "waiting"
            if args.once:
                break
            time.sleep(2)
            continue

        # 成功拿到有效二维码 URL；同一 URL 仅渲染打印一次，避免轮询刷屏
        if url != last_printed_url:
            print("\n" + "=" * 48)
            print("           PicoOnebot 手机 QQ 扫码登录")
            print("=" * 48)

            rendered = print_terminal_qr(url, args.light)

            print(f"\n[有效时间] 剩余 {expires_in} 秒")
            print(f"[登录链接] {url}")
            print(f"[控制台]   {api_base}/")
            if not rendered:
                print("\n[提示] 终端未安装 qrcode 模块，请直接复制上方链接扫码或在浏览器打开控制台完成登录。")
                print("       (安装点阵支持: apt install python3-qrcode 或 pip install qrcode)")
            print("=" * 48 + "\n")
            last_printed_url = url
            last_status = "qr"

        if args.once:
            return 0

        time.sleep(3)

    if last_error:
        print(f"[picoqr] 获取登录二维码失败: {last_error}", file=sys.stderr)
    else:
        print("[picoqr] 等待登录超时，未完成扫码登录。", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

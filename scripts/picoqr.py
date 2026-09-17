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
import urllib.request
import urllib.error

def fetch_qr(api_base: str, refresh: bool = False):
    """请求 WebApi 获取二维码状态。"""
    url = f"{api_base.rstrip('/')}/api/qr"
    if refresh:
        url = f"{api_base.rstrip('/')}/api/qr/refresh"
    
    req = urllib.request.Request(
        url,
        data=b"{}" if refresh else None,
        headers={"Content-Type": "application/json", "User-Agent": "PicoQr/2.0"}
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        return None

def print_terminal_qr(url: str, light_mode: bool = False):
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

def main():
    parser = argparse.ArgumentParser(description="PicoOnebot 登录二维码获取与渲染")
    parser.add_argument("--api", default=os.getenv("PICO_API", "http://localhost:6099"),
                        help="PicoOnebot WebApi 基础地址 (默认 http://localhost:6099)")
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
    if args.refresh:
        fetch_qr(api_base, refresh=True)
        time.sleep(1)

    deadline = time.time() + (5.0 if args.once else args.timeout)
    last_status = ""

    while time.time() <= deadline:
        data = fetch_qr(api_base)
        if not data:
            if last_status != "unreachable":
                print(f"[picoqr] 正在等待 WebApi 服务就绪 ({api_base})...", file=sys.stderr)
                last_status = "unreachable"
            if args.once:
                break
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
            print("[picoqr] 手机 QQ 已扫码，等待在手机上确认登录...", file=sys.stderr)
            time.sleep(2)
            continue

        if status == "expired":
            print("[picoqr] 二维码已过期，正在请求刷新...", file=sys.stderr)
            fetch_qr(api_base, refresh=True)
            time.sleep(2)
            continue

        if status == "waiting_qr" or not url:
            if last_status != "waiting":
                print("[picoqr] 等待生成登录二维码...", file=sys.stderr)
                last_status = "waiting"
            time.sleep(2)
            continue

        # 成功拿到有效二维码 URL
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

        if args.once:
            return 0

        time.sleep(3)

    return 0

if __name__ == "__main__":
    sys.exit(main())

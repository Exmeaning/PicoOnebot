#!/system/bin/sh
# 容器健康检查：检查 sys.boot_completed 状态与 WebUI 端口 (6099 / 0x17D3) 监听状态
grep -qi ':17D3 ' /proc/net/tcp /proc/net/tcp6 2>/dev/null || exit 1
[ "$(getprop sys.boot_completed)" = "1" ] || exit 1
exit 0

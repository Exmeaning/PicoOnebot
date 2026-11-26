#!/system/bin/pico-bb/sh
# 容器健康检查：检查 sys.boot_completed 状态与 WebUI 端口 (6099 / 0x17D3) 本地 LISTEN (0A) 状态
# 运行在静态 BusyBox 上；getprop 仍来自 Android（健康检查时 /init 已挂载 /apex）。
export PATH=/system/bin:/system/xbin:/system/bin/pico-bb
[ "$(getprop sys.boot_completed)" = "1" ] || exit 1
(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null || true) \
    | awk '$4 == "0A" && $2 ~ /:17[Dd]3$/ { found = 1; exit 0 } END { exit(found ? 0 : 1) }' \
    || exit 1
exit 0

#!/system/bin/pico-bb/sh
# 容器健康检查：检查 sys.boot_completed 状态与已配置 WebUI 端口 (默认 6099 / 0x17D3) 本地 LISTEN (0A) 状态
# 运行在静态 BusyBox 上；getprop 仍来自 Android（健康检查时 /init 已挂载 /apex）。
export PATH=/system/bin:/system/xbin:/system/bin/pico-bb
[ "$(getprop sys.boot_completed)" = "1" ] || exit 1

detect_webui_port() {
    _cfg="${PICO_CONFIG_FILE:-/data/user/0/com.tencent.qqlite/files/pico-onebot/config.json}"
    if [ -z "${PICO_CONFIG_FILE:-}" ] && [ ! -f "$_cfg" ]; then
        _cfg="/sdcard/pico-onebot/config.json"
    fi
    _port=""
    if [ -r "$_cfg" ] && [ ! -L "$_cfg" ]; then
        _port="$(awk '
            { buf = buf " " $0 }
            END {
                if (match(buf, /"webui"[[:space:]]*:[[:space:]]*\{[^}]*"port"[[:space:]]*:[[:space:]]*[0-9]+/)) {
                    seg = substr(buf, RSTART, RLENGTH)
                    sub(/^.*"port"[[:space:]]*:[[:space:]]*/, "", seg)
                    print seg
                }
            }
        ' "$_cfg" 2>/dev/null || true)"
    fi
    case "$_port" in
        ''|*[!0-9]*) _port=6099 ;;
    esac
    if [ "$_port" -lt 1 ] || [ "$_port" -gt 65535 ]; then
        _port=6099
    fi
    printf '%s\n' "$_port"
}

webui_port="$(detect_webui_port)"
webui_port_hex="$(printf '%04X' "$webui_port")"
(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null || true) \
    | awk -v port_hex="$webui_port_hex" '$4 == "0A" && toupper($2) ~ (":" port_hex "$") { found = 1; exit 0 } END { exit(found ? 0 : 1) }' \
    || exit 1
exit 0

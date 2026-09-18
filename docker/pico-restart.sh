#!/system/bin/sh

restart_ready=/data/local/tmp/pico-qq-restart.ready
restart_request=/data/user/0/com.tencent.qqlite/files/pico-onebot/restart-qq.request
restart_last=0

restart_init() {
    [ "$(cat /system/etc/pico-container 2>/dev/null)" = "pico-container-v1" ] || return 1
    rm -f "$restart_ready" "$restart_ready.tmp" "$restart_request"
}

restart_tick() {
    printf '%s\n' restart-qq-v1 > "$restart_ready.tmp" || return
    chmod 0644 "$restart_ready.tmp"
    mv -f "$restart_ready.tmp" "$restart_ready" || return
    [ -f "$restart_request" ] || return 0
    [ ! -L "$restart_request" ] || { rm -f "$restart_request"; return 0; }
    requested_at="$(head -c 32 "$restart_request" 2>/dev/null)"
    rm -f "$restart_request"
    case "$requested_at" in
        ''|*[!0-9]*) log "忽略无效的 QQ 重启请求"; return 0 ;;
    esac
    [ "${#requested_at}" -eq 10 ] || return 0
    case "$requested_at" in 0*) return 0 ;; esac
    restart_now="$(date +%s)"
    restart_age=$((restart_now - requested_at))
    if [ "$restart_age" -lt 0 ] || [ "$restart_age" -gt 60 ] || [ $((restart_now - restart_last)) -lt 30 ]; then
        log "忽略过期或过于频繁的 QQ 重启请求"
        return 0
    fi
    restart_last="$restart_now"
    log "WebUI 请求重启 QQ（保留容器与应用数据）"
    sleep 3
    if am force-stop com.tencent.qqlite; then
        sleep 2
        am start -W -n com.tencent.qqlite/com.tencent.qqnt.watch.app.JumpActivity || log "ERROR: QQ 重新启动失败，保活循环将重试"
    else
        log "ERROR: QQ 停止失败"
    fi
}

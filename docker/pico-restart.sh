#!/system/bin/sh
# pico-restart.sh: 由 pico-boot.sh source，提供容器内 QQ 受控重启守护。
# 路径契约 (/data/local/tmp/pico-qq-restart.ready) 与 ContainerRestart.kt 保持兼容，
# 并在写入前严格校验符号链接、目录与 O_EXCL 独占创建，防范 /data/local/tmp 提权风险。

restart_dir=/data/local/tmp
restart_ready=/data/local/tmp/pico-qq-restart.ready
restart_request=/data/user/0/com.tencent.qqlite/files/pico-onebot/restart-qq.request
restart_last=0

restart_init() {
    [ -f /system/etc/pico-container ] && [ ! -L /system/etc/pico-container ] || return 1
    [ "$(cat /system/etc/pico-container 2>/dev/null)" = "pico-container-v1" ] || return 1
    for _p in "$restart_ready" "${restart_ready}.tmp" "$restart_request"; do
        if [ -L "$_p" ] || [ -f "$_p" ]; then
            rm -f "$_p" 2>/dev/null || true
        fi
    done
    return 0
}

restart_write_ready() {
    _tmp="${restart_ready}.tmp"
    # 父目录必须为真实目录而非符号链接
    if [ ! -d "$restart_dir" ] || [ -L "$restart_dir" ]; then
        log "WARN: $restart_dir 不可用或为符号链接，拒绝写入重启就绪标记"
        return 1
    fi
    # 拒绝并清理符号链接；若被替换为目录则拒绝操作（避免 root 执行 rm -rf）
    for _p in "$_tmp" "$restart_ready"; do
        if [ -L "$_p" ]; then
            log "WARN: 检测到符号链接 $_p，已移除"
            rm -f "$_p" 2>/dev/null || return 1
        elif [ -d "$_p" ]; then
            log "WARN: 检测到异常目录 $_p，拒绝覆盖"
            return 1
        fi
    done
    # 清理残留临时文件后在子 shell 内启用 umask 022 + set -C (O_CREAT|O_EXCL) 独占创建，
    # 防止竞态符号链接被跟随写入任意文件。
    rm -f "$_tmp" 2>/dev/null || return 1
    [ ! -e "$_tmp" ] && [ ! -L "$_tmp" ] || return 1
    (
        umask 022
        set -C
        printf '%s\n' restart-qq-v1 > "$_tmp"
    ) 2>/dev/null || {
        log "WARN: 无法独占创建重启就绪临时文件 $_tmp"
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    }
    if [ -L "$_tmp" ] || [ ! -f "$_tmp" ]; then
        log "WARN: 拒绝非普通文件 $_tmp"
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    fi
    chmod 0644 "$_tmp" 2>/dev/null || {
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    }
    if [ -L "$restart_ready" ] || [ -d "$restart_ready" ]; then
        log "WARN: 拒绝覆盖异常目标 $restart_ready"
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    fi
    mv -f "$_tmp" "$restart_ready" 2>/dev/null || {
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    }
}

restart_tick() {
    restart_write_ready || return 0
    if [ -L "$restart_request" ]; then
        log "WARN: 忽略符号链接重启请求 $restart_request"
        rm -f "$restart_request" 2>/dev/null || true
        return 0
    fi
    [ -f "$restart_request" ] || return 0
    requested_at="$(head -c 32 "$restart_request" 2>/dev/null)"
    rm -f "$restart_request" 2>/dev/null || true
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

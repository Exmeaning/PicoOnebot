#!/system/bin/sh
# pico-restart.sh: 由 pico-boot.sh source，提供容器内 QQ 受控重启守护。
# 路径契约 (/data/local/tmp/pico-qq-restart.ready) 与 ContainerRestart.kt 保持兼容，
# 并在写入前严格校验符号链接、目录与 O_EXCL 独占创建，防范 /data/local/tmp 提权风险。

restart_dir=/data/local/tmp
restart_ready=/data/local/tmp/pico-qq-restart.ready
restart_request=/data/user/0/com.tencent.qqlite/files/pico-onebot/restart-qq.request
restart_last=0
restart_mv="mv"
restart_mv_has_T=0
restart_mv_probed=0

restart_probe_mv() {
    if [ -x /system/bin/pico-bb/mv ]; then
        restart_mv="/system/bin/pico-bb/mv"
    else
        restart_mv="mv"
    fi
    restart_mv_has_T=0
    restart_mv_probed=1
    _probe_base=""
    for _cand in /data/pico "${TMPDIR:-/tmp}" "$restart_dir"; do
        if [ -d "$_cand" ] && [ ! -L "$_cand" ] && [ -w "$_cand" ]; then
            _probe_base="$_cand"
            break
        fi
    done
    [ -n "$_probe_base" ] || return 0
    _probe_dir="${_probe_base}/.pico-mv-probe.$$"
    if (umask 077 && mkdir "$_probe_dir" && mkdir "$_probe_dir/d") 2>/dev/null; then
        if (set -C; : > "$_probe_dir/a") 2>/dev/null \
            && "$restart_mv" -T -f "$_probe_dir/a" "$_probe_dir/b" >/dev/null 2>&1 \
            && [ -f "$_probe_dir/b" ] && [ ! -e "$_probe_dir/a" ] \
            && ! "$restart_mv" -T -f "$_probe_dir/b" "$_probe_dir/d" >/dev/null 2>&1 \
            && [ -f "$_probe_dir/b" ] && [ ! -e "$_probe_dir/d/b" ]; then
            restart_mv_has_T=1
        fi
        rm -f "$_probe_dir/a" "$_probe_dir/b" "$_probe_dir/d/b" 2>/dev/null || true
        rmdir "$_probe_dir/d" "$_probe_dir" 2>/dev/null || true
    fi
}

restart_init() {
    [ -f /system/etc/pico-container ] && [ ! -L /system/etc/pico-container ] || return 1
    [ "$(cat /system/etc/pico-container 2>/dev/null)" = "pico-container-v1" ] || return 1
    restart_probe_mv
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
    if [ -L "$restart_ready" ] || [ -d "$restart_ready" ]; then
        log "WARN: 拒绝覆盖异常目标 $restart_ready"
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    fi
    [ "$restart_mv_probed" = "1" ] || restart_probe_mv
    if [ "$restart_mv_has_T" = "1" ]; then
        # 已在初始化时确认 mv 支持 -T：失败（例如目标已被替换为目录）时绝不回退到普通 mv -f
        if ! "$restart_mv" -T -f "$_tmp" "$restart_ready" 2>/dev/null; then
            log "WARN: 无法原子替换 $restart_ready（可能被替换为目录）"
            rm -f "$_tmp" 2>/dev/null || true
            return 1
        fi
    else
        if [ -L "$restart_ready" ] || [ -d "$restart_ready" ] || ! "$restart_mv" -f "$_tmp" "$restart_ready" 2>/dev/null; then
            rm -f "$_tmp" 2>/dev/null || true
            return 1
        fi
    fi
}

restart_tick() {
    restart_now="$(date +%s)"
    # 时钟回拨时重置冷却记录，避免永久锁死重启
    if [ "$restart_last" -gt 0 ] && [ "$restart_now" -lt "$restart_last" ]; then
        restart_last=0
    fi
    # 冷却期内不发布 ready 标记（并移除旧标记），与 Android 侧新进程内存冷却保持一致，
    # 避免容器刚重启后 30s 内 WebUI 返回 202 Accepted 却被宿主机静默丢弃。
    if [ "$restart_last" -gt 0 ] && [ $((restart_now - restart_last)) -lt 30 ]; then
        if [ -L "$restart_ready" ] || [ -f "$restart_ready" ]; then
            rm -f "$restart_ready" 2>/dev/null || true
        fi
        if [ -L "$restart_request" ] || [ -f "$restart_request" ]; then
            rm -f "$restart_request" 2>/dev/null || true
            log "忽略冷却期内的 QQ 重启请求"
        fi
        return 0
    fi
    restart_write_ready || return 0
    if [ -L "$restart_request" ]; then
        log "WARN: 忽略符号链接重启请求 $restart_request"
        rm -f "$restart_request" 2>/dev/null || true
        return 0
    fi
    [ -f "$restart_request" ] || return 0
    requested_at="$(head -c 32 "$restart_request" 2>/dev/null)"
    rm -f "$restart_request" 2>/dev/null || true
    # 读取请求后再采样当前秒数，避免 restart_write_ready 跨越整秒边界导致 restart_age = -1 误丢弃合法请求
    restart_now="$(date +%s)"
    case "$requested_at" in
        ''|*[!0-9]*) log "忽略无效的 QQ 重启请求"; return 0 ;;
    esac
    [ "${#requested_at}" -eq 10 ] || return 0
    case "$requested_at" in 0*) return 0 ;; esac
    restart_age=$((restart_now - requested_at))
    if [ "$restart_age" -lt 0 ] || [ "$restart_age" -gt 60 ]; then
        log "忽略过期的 QQ 重启请求"
        return 0
    fi
    restart_last="$restart_now"
    # 重启期间移除 ready 标记，防止新进程启动瞬间在冷却期内接收重复请求
    if [ -L "$restart_ready" ] || [ -f "$restart_ready" ]; then
        rm -f "$restart_ready" 2>/dev/null || true
    fi
    log "WebUI 请求重启 QQ（保留容器与应用数据）"
    sleep 3
    if am force-stop com.tencent.qqlite; then
        sleep 2
        am start -W -n com.tencent.qqlite/com.tencent.qqnt.watch.app.JumpActivity || log "ERROR: QQ 重新启动失败，保活循环将重试"
    else
        log "ERROR: QQ 停止失败"
    fi
}

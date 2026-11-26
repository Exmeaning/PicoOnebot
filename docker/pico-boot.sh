#!/system/bin/sh
# pico-boot.sh: PicoOnebot 在 Redroid 容器内的安装引导与保活守护脚本
# 日志写入 /data/pico/pico-boot.log (带大小轮转) 以及 PID 1 stdout (/proc/1/fd/1)，
# 不再写入宿主机共享的 /dev/kmsg，避免污染宿主机 dmesg 或导致容器 stdout 重复输出。

export PATH="${PATH:-/system/bin:/system/xbin}:/system/bin/pico-bb"

PKG="com.tencent.qqlite"
ACTIVITY="com.tencent.qqnt.watch.app.JumpActivity"
APK="/opt/pico-onebot/PicoOnebot.apk"
LOG_DIR="/data/pico"
LOG_FILE="$LOG_DIR/pico-boot.log"
LOG_BACKUP="$LOG_DIR/pico-boot.log.1"
LOG_MAX_BYTES="${PICO_BOOT_LOG_MAX_BYTES:-1048576}"
APK_SHA_FILE="$LOG_DIR/installed-apk.sha256"

case "$LOG_MAX_BYTES" in
    ''|*[!0-9]*|0) LOG_MAX_BYTES=1048576 ;;
esac

mkdir -p "$LOG_DIR" 2>/dev/null
# 启动时轮转上一次日志（保留一份 .1 排障），同时拒绝符号链接
if [ -L "$LOG_FILE" ]; then
    rm -f "$LOG_FILE" 2>/dev/null
elif [ -s "$LOG_FILE" ]; then
    rm -f "$LOG_BACKUP" 2>/dev/null
    mv -f "$LOG_FILE" "$LOG_BACKUP" 2>/dev/null || : > "$LOG_FILE" 2>/dev/null
fi
: > "$LOG_FILE" 2>/dev/null

rotate_log_if_needed() {
    if [ -L "$LOG_FILE" ]; then
        rm -f "$LOG_FILE" 2>/dev/null
        return 0
    fi
    [ -f "$LOG_FILE" ] || return 0
    _size="$(wc -c < "$LOG_FILE" 2>/dev/null | tr -d '[:space:]')"
    case "$_size" in
        ''|*[!0-9]*) return 0 ;;
    esac
    if [ "$_size" -ge "$LOG_MAX_BYTES" ]; then
        rm -f "$LOG_BACKUP" 2>/dev/null
        mv -f "$LOG_FILE" "$LOG_BACKUP" 2>/dev/null || : > "$LOG_FILE" 2>/dev/null
    fi
}

log() {
    _msg="[pico-boot $(date '+%m-%d %H:%M:%S')] $*"
    rotate_log_if_needed
    if [ ! -L "$LOG_FILE" ]; then
        printf '%s\n' "$_msg" >> "$LOG_FILE" 2>/dev/null
    fi
    printf '%s\n' "$_msg" > /proc/1/fd/1 2>/dev/null
}

file_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" 2>/dev/null | awk '{print $1}'
    elif [ -x /system/bin/pico-bb/sha256sum ]; then
        /system/bin/pico-bb/sha256sum "$1" 2>/dev/null | awk '{print $1}'
    fi
}

write_apk_sha() {
    [ -n "$1" ] || return 0
    printf '%s\n' "$1" > "${APK_SHA_FILE}.tmp" 2>/dev/null && mv -f "${APK_SHA_FILE}.tmp" "$APK_SHA_FILE" 2>/dev/null
}

webui_listening() {
    (cat /proc/net/tcp /proc/net/tcp6 2>/dev/null || true) \
        | awk '$4 == "0A" && $2 ~ /:17[Dd]3$/ { found = 1; exit 0 } END { exit(found ? 0 : 1) }'
}

log "=============================================================="
log "PicoOnebot boot service starting (pid $$)"

# ── 0. Binder 节点检查 ──────────────────────────────────────────────────────
binder_ok=1
for node in /dev/binder /dev/hwbinder /dev/vndbinder; do
    if [ ! -e "$node" ]; then
        log "ERROR: $node 不存在 (内核缺少 binder 支持或未以 --privileged 运行)"
        binder_ok=0
        continue
    fi
    perm="$(stat -L -c '%a %U %G' "$node" 2>/dev/null)"
    log "binder 节点 $node -> $perm ($(readlink -f "$node" 2>/dev/null))"
    case "$perm" in
        666\ *) ;;
        *)
            log "ERROR: $node 权限为 $perm (需为 0666)"
            log "       可在宿主机执行: sudo chmod 666 /dev/binder /dev/hwbinder /dev/vndbinder 或改用 binderfs"
            binder_ok=0
            ;;
    esac
done
[ "$binder_ok" = "1" ] && log "Binder 设备检查正常。"

# ── 1. 等待 Android 启动完成 ────────────────────────────────────────────────
log "等待 sys.boot_completed ..."
i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
    i=$((i + 1))
    if [ $((i % 15)) -eq 0 ]; then
        log "等待 Android 系统就绪 (已等待 $((i * 2))s)... servicemanager=$(pidof servicemanager >/dev/null 2>&1 && echo up || echo DOWN) surfaceflinger=$(pidof surfaceflinger >/dev/null 2>&1 && echo up || echo DOWN)"
    fi
    if [ "$i" -gt 150 ]; then
        log "ERROR: 等待 Android 启动超时 (300s)。"
        if [ "$binder_ok" = "0" ]; then
            log "ERROR: 检测到 Binder 设备权限异常，请参考 docs/BINDER.md 调整配置。"
        else
            log "ERROR: 可执行 pico-doctor.sh 进一步排查问题。"
        fi
        break
    fi
done
[ "$(getprop sys.boot_completed)" = "1" ] && log "Android 系统就绪 (耗时约 $((i * 2))s)。"

# ── 2. 安装 / 覆盖升级 APK ──────────────────────────────────────────────────
is_first_install=0
pkg_installed=0
pm path "$PKG" >/dev/null 2>&1 && pkg_installed=1
apk_sha=""
[ -f "$APK" ] && apk_sha="$(file_sha256 "$APK")"
installed_sha="$(tr -d '[:space:]' < "$APK_SHA_FILE" 2>/dev/null || true)"

install_apk_with_retry() {
    _out="$(pm install -r -d "$APK" 2>&1)" && return 0
    log "WARN: $PKG 安装未成功 (${_out})，将在 10s 后重试..."
    sleep 10
    _out="$(pm install -r -d "$APK" 2>&1)" && return 0
    log "ERROR: $PKG 安装失败: $(printf '%s\n' "$_out" | tail -n 3)"
    return 1
}

if [ "$pkg_installed" = "0" ]; then
    if [ -f "$APK" ]; then
        log "安装 $APK (sha256=${apk_sha:-unknown}) ..."
        if install_apk_with_retry; then
            is_first_install=1
            pkg_installed=1
            write_apk_sha "$apk_sha"
            log "$PKG 安装成功。"
        fi
    else
        log "WARN: 未检测到 $APK 且 $PKG 未安装，请检查镜像文件完整性。"
    fi
elif [ -f "$APK" ] && [ -n "$apk_sha" ] && [ "$apk_sha" != "$installed_sha" ]; then
    log "检测到镜像内 APK 已更新 (已安装=${installed_sha:-未记录}, 镜像=${apk_sha})，执行覆盖安装..."
    if install_apk_with_retry; then
        write_apk_sha "$apk_sha"
        log "$PKG 覆盖升级成功 (sha256=$apk_sha)。"
    fi
else
    log "$PKG 已安装且与镜像 APK 一致 (sha256=${apk_sha:-unknown}, 版本: $(dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d ' '))。"
fi

# ── 3. 授予运行时权限 ───────────────────────────────────────────────────────
run_keepalive_cmd() {
    _err="$("$@" 2>&1)" && return 0
    sleep 2
    _err="$("$@" 2>&1)" && return 0
    _err_summary="$(printf '%s\n' "$_err" | head -n 1 | tr '\r\n' '  ')"
    log "WARN: 保活/权限配置命令失败: $* (${_err_summary:-no output})"
    return 1
}

if [ "$pkg_installed" = "1" ]; then
    log "授予 $PKG 运行时权限 ..."
    run_keepalive_cmd pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
    run_keepalive_cmd pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE || true
    run_keepalive_cmd pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE || true
fi

# ── 3b. Redroid 电源/防息屏/Doze/网络检测加固 ───────────────────────────────
log "配置 Redroid 电源、防息屏、Doze 与 Captive Portal ..."
# 模拟已插电满电状态：Android 的 svc power stayon 仅在系统判定已插电(ac/usb/wireless)时生效
run_keepalive_cmd dumpsys battery set present 1 || true
run_keepalive_cmd dumpsys battery set ac 1 || true
run_keepalive_cmd dumpsys battery set status 2 || true
run_keepalive_cmd dumpsys battery set level 100 || true
# 防止虚拟屏息屏导致 Activity onStop -> QQ 切后台触发 SilenceMsgMgr 主动报静默
run_keepalive_cmd svc power stayon true || true
run_keepalive_cmd settings put system screen_off_timeout 2147483647 || true
run_keepalive_cmd input keyevent KEYCODE_WAKEUP || true
# 禁用 Doze 并放行 QQ 后台运行
run_keepalive_cmd dumpsys deviceidle disable || true
if [ "$pkg_installed" = "1" ]; then
    run_keepalive_cmd dumpsys deviceidle whitelist +"$PKG" || true
    run_keepalive_cmd am set-standby-bucket "$PKG" active || true
    run_keepalive_cmd cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow || true
fi
# 关闭 Captive Portal 校验，避免无法访问 google.com 时持续报 ETHERNET validation failed
run_keepalive_cmd settings put global captive_portal_mode 0 || true

# ── 4. 启动应用 ─────────────────────────────────────────────────────────────
log "启动应用 $PKG/$ACTIVITY ..."
logcat -c >/dev/null 2>&1 || true
if ! _start_out="$(am start -W -n "$PKG/$ACTIVITY" 2>&1)"; then
    log "WARN: 初始启动 $PKG 失败: $(printf '%s\n' "$_start_out" | head -n 1)"
fi

# ── 5. 首次运行引导处理 ────────────────────────────────────────────────────
if [ "$is_first_install" -eq 1 ]; then
    log "首次安装，等待应用内 Hook 完成隐私授权与登录页导航 ..."
    i=0
    while [ "$i" -lt 30 ]; do
        if logcat -d -s PicoOB:I '*:S' 2>/dev/null | grep -q 'Privacy agreement accepted by deployment hook'; then
            log "隐私授权已由应用内 Hook 完成，重启 QQ 以重新初始化 QIMEI。"
            am force-stop "$PKG" >/dev/null 2>&1 || true
            sleep 2
            if ! _start_out="$(am start -W -n "$PKG/$ACTIVITY" 2>&1)"; then
                log "WARN: 隐私授权后重启 $PKG 失败: $(printf '%s\n' "$_start_out" | head -n 1)"
            fi
            break
        fi
        sleep 1
        i=$((i + 1))
    done
    if [ "$i" -ge 30 ]; then
        log "WARN: 等待隐私授权 Hook 超时；未执行盲点点击，请查看 PicoOB 日志。"
    fi
fi

# ── 6. 检测 WebUI 服务就绪 ──────────────────────────────────────────────────
# 6099 = 0x17D3，严格校验本地地址 (:17D3) 且状态为 LISTEN (0A)
webui_up=0
i=0
while [ "$i" -lt 30 ]; do
    if webui_listening; then
        webui_up=1
        break
    fi
    sleep 2
    i=$((i + 1))
done

if [ "$webui_up" = "1" ]; then
    log "WebUI 服务已就绪 (0.0.0.0:6099)。"
    log "  控制台: http://<宿主机IP>:6099 (首次默认密码: picopico，请立即修改)"
    log "  OneBot v11: 请在 WebUI 网络配置中启用正向/反向监听（默认常用 3001）"
else
    log "WARN: 应用已启动，但尚未检测到 6099 端口监听。"
    log "  可执行 pico-doctor.sh 或查看 logcat 排查。"
fi

# ── 7. 进程保活守护 ─────────────────────────────────────────────────────────
log "启动进程守护循环。"
# shellcheck source=docker/pico-restart.sh
. /system/bin/pico-restart.sh
restart_enabled=0
restart_init && restart_enabled=1
pm_recheck_ticks=0
while true; do
    sleep 3
    [ "$restart_enabled" = 0 ] || restart_tick
    # 缓存 pm path 结果，避免每 3s 调用 Binder pm (28,800 次/天)：
    # - 未安装时仅每 60s (20 ticks) 探测一次；
    # - 已安装时稳态零 pm 调用，仅在 am start 失败时复核包是否被卸载。
    if [ "$pkg_installed" = "0" ]; then
        pm_recheck_ticks=$((pm_recheck_ticks + 1))
        if [ "$pm_recheck_ticks" -ge 20 ]; then
            pm_recheck_ticks=0
            if pm path "$PKG" >/dev/null 2>&1; then
                pkg_installed=1
                log "检测到 $PKG 已安装，启用进程拉起。"
            fi
        fi
    fi
    if [ "$pkg_installed" = "1" ]; then
        if ! pidof "$PKG" >/dev/null 2>&1; then
            log "$PKG 未运行，正在重新拉起 ..."
            if ! _start_out="$(am start -n "$PKG/$ACTIVITY" 2>&1)"; then
                log "WARN: 拉起 $PKG 失败: $(printf '%s\n' "$_start_out" | head -n 1)"
                if ! pm path "$PKG" >/dev/null 2>&1; then
                    pkg_installed=0
                    pm_recheck_ticks=0
                    log "WARN: $PKG 似乎已卸载，暂停拉起并按 60s 周期探测。"
                fi
            fi
        fi
    fi
done

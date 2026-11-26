#!/system/bin/sh
# pico-boot.sh: PicoOnebot 在 Redroid 容器内的安装引导与保活守护脚本
# 日志同时写入 /data/pico/pico-boot.log、/dev/kmsg 以及 PID 1 stdout (/proc/1/fd/1)。

export PATH="${PATH:-/system/bin:/system/xbin}:/system/bin/pico-bb"

PKG="com.tencent.qqlite"
ACTIVITY="com.tencent.qqnt.watch.app.JumpActivity"
APK="/opt/pico-onebot/PicoOnebot.apk"
LOG_DIR="/data/pico"
LOG_FILE="$LOG_DIR/pico-boot.log"
APK_SHA_FILE="$LOG_DIR/installed-apk.sha256"

mkdir -p "$LOG_DIR" 2>/dev/null
: > "$LOG_FILE" 2>/dev/null

log() {
    _msg="[pico-boot $(date '+%m-%d %H:%M:%S')] $*"
    echo "$_msg" >> "$LOG_FILE" 2>/dev/null
    echo "$_msg" > /dev/kmsg 2>/dev/null
    echo "$_msg" > /proc/1/fd/1 2>/dev/null
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
installed_sha="$(cat "$APK_SHA_FILE" 2>/dev/null | tr -d '[:space:]')"

install_apk_with_retry() {
    _out="$(pm install -r -d "$APK" 2>&1)" && return 0
    log "WARN: $PKG 安装未成功 (${_out})，将在 10s 后重试..."
    sleep 10
    _out="$(pm install -r -d "$APK" 2>&1)" && return 0
    log "ERROR: $PKG 安装失败: $(printf '%s\n' "$_out" | tail -3)"
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
if [ "$pkg_installed" = "1" ]; then
    log "授予 $PKG 运行时权限 ..."
    pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
    pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
fi

# ── 3b. Redroid 电源/防息屏/Doze/网络检测加固 ───────────────────────────────
log "配置 Redroid 电源、防息屏、Doze 与 Captive Portal ..."
run_keepalive_cmd() {
    if ! "$@" >/dev/null 2>&1; then
        log "WARN: 保活配置命令失败: $*"
    fi
}
# 模拟已插电满电状态：Android 的 svc power stayon 仅在系统判定已插电(ac/usb/wireless)时生效
run_keepalive_cmd dumpsys battery set present 1
run_keepalive_cmd dumpsys battery set ac 1
run_keepalive_cmd dumpsys battery set status 2
run_keepalive_cmd dumpsys battery set level 100
# 防止虚拟屏息屏导致 Activity onStop -> QQ 切后台触发 SilenceMsgMgr 主动报静默
run_keepalive_cmd svc power stayon true
run_keepalive_cmd settings put system screen_off_timeout 2147483647
run_keepalive_cmd input keyevent KEYCODE_WAKEUP
# 禁用 Doze 并放行 QQ 后台运行
run_keepalive_cmd dumpsys deviceidle disable
if [ "$pkg_installed" = "1" ]; then
    run_keepalive_cmd dumpsys deviceidle whitelist +"$PKG"
    run_keepalive_cmd am set-standby-bucket "$PKG" active
    run_keepalive_cmd cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow
fi
# 关闭 Captive Portal 校验，避免无法访问 google.com 时持续报 ETHERNET validation failed
run_keepalive_cmd settings put global captive_portal_mode 0

# ── 4. 启动应用 ─────────────────────────────────────────────────────────────
log "启动应用 $PKG/$ACTIVITY ..."
logcat -c >/dev/null 2>&1 || true
am start -W -n "$PKG/$ACTIVITY" >/dev/null 2>&1 || true

# ── 5. 首次运行引导处理 ────────────────────────────────────────────────────
if [ "$is_first_install" -eq 1 ]; then
    log "首次安装，等待应用内 Hook 完成隐私授权与登录页导航 ..."
    i=0
    while [ "$i" -lt 30 ]; do
        if logcat -d -s PicoOB:I '*:S' 2>/dev/null | grep -q 'Privacy agreement accepted by deployment hook'; then
            log "隐私授权已由应用内 Hook 完成，重启 QQ 以重新初始化 QIMEI。"
            am force-stop "$PKG" >/dev/null 2>&1 || true
            sleep 2
            am start -W -n "$PKG/$ACTIVITY" >/dev/null 2>&1 || true
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
. /system/bin/pico-restart.sh
restart_enabled=0
restart_init && restart_enabled=1
while true; do
    sleep 3
    [ "$restart_enabled" = 0 ] || restart_tick
    if [ "$pkg_installed" = "0" ] && pm path "$PKG" >/dev/null 2>&1; then
        pkg_installed=1
    fi
    if [ "$pkg_installed" = "1" ]; then
        if ! pidof "$PKG" >/dev/null 2>&1; then
            log "$PKG 未运行，正在重新拉起 ..."
            am start -n "$PKG/$ACTIVITY" >/dev/null 2>&1 || true
        fi
    fi
done

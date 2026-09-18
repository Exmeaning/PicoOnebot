#!/system/bin/sh
# pico-boot.sh: PicoOnebot 在 Redroid 容器内的安装引导与保活守护脚本
# 日志同时写入 /data/pico/pico-boot.log、/dev/kmsg 以及 PID 1 stdout (/proc/1/fd/1)。

PKG="com.tencent.qqlite"
ACTIVITY="com.tencent.qqnt.watch.app.JumpActivity"
APK="/opt/pico-onebot/PicoOnebot.apk"
LOG_DIR="/data/pico"
LOG_FILE="$LOG_DIR/pico-boot.log"

mkdir -p "$LOG_DIR" 2>/dev/null
: > "$LOG_FILE" 2>/dev/null

log() {
    _msg="[pico-boot $(date '+%m-%d %H:%M:%S')] $*"
    echo "$_msg" >> "$LOG_FILE" 2>/dev/null
    echo "$_msg" > /dev/kmsg 2>/dev/null
    echo "$_msg" > /proc/1/fd/1 2>/dev/null
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
    perm="$(stat -c '%a %U %G' "$node" 2>/dev/null)"
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

# ── 2. 安装 APK ─────────────────────────────────────────────────────────────
is_first_install=0
if ! pm path "$PKG" >/dev/null 2>&1; then
    if [ -f "$APK" ]; then
        log "安装 $APK ..."
        if pm install -r "$APK" >/dev/null 2>&1; then
            is_first_install=1
            log "$PKG 安装成功。"
        else
            log "WARN: $PKG 安装未成功，将在 10s 后重试..."
            sleep 10
            if pm install -r "$APK" >/dev/null 2>&1; then
                is_first_install=1
                log "$PKG 重试安装成功。"
            else
                log "ERROR: $PKG 安装失败: $(pm install -r "$APK" 2>&1 | tail -3)"
            fi
        fi
    else
        log "WARN: 未检测到 $APK 且 $PKG 未安装，请检查镜像文件完整性。"
    fi
else
    log "$PKG 已安装 (版本: $(dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d ' '))。"
fi

# ── 3. 授予运行时权限 ───────────────────────────────────────────────────────
if pm path "$PKG" >/dev/null 2>&1; then
    log "授予 $PKG 运行时权限 ..."
    pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
    pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
fi

# ── 4. 启动应用 ─────────────────────────────────────────────────────────────
log "启动应用 $PKG/$ACTIVITY ..."
am start -W -n "$PKG/$ACTIVITY" >/dev/null 2>&1 || true

# ── 5. 首次运行引导处理 ────────────────────────────────────────────────────
if [ "$is_first_install" -eq 1 ]; then
    log "首次安装，执行初始化引导流程 ..."
    sleep 6
    # 坐标基于 720x1256 分辨率
    input tap 360 622 2>/dev/null || true   # 允许通知
    sleep 2
    input tap 535 749 2>/dev/null || true   # 同意隐私政策
    sleep 2
    input tap 360 1121 2>/dev/null || true  # 欢迎页登录
    sleep 3
    log "重启 $PKG 应用..."
    am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 2
    am start -W -n "$PKG/$ACTIVITY" >/dev/null 2>&1 || true
    sleep 4
    input tap 360 1121 2>/dev/null || true
fi

# ── 6. 检测 WebUI 服务就绪 ──────────────────────────────────────────────────
# 6099 = 0x17D3，直接读取 /proc/net/tcp 检查监听状态
webui_up=0
i=0
while [ "$i" -lt 30 ]; do
    if grep -qi ':17D3 ' /proc/net/tcp /proc/net/tcp6 2>/dev/null; then
        webui_up=1
        break
    fi
    sleep 2
    i=$((i + 1))
done

if [ "$webui_up" = "1" ]; then
    log "WebUI 服务已就绪 (0.0.0.0:6099)。"
    log "  控制台: http://<宿主机IP>:6099 (默认密码: picopico)"
    log "  OneBot v11: <宿主机IP>:3001"
else
    log "WARN: 应用已启动，但尚未检测到 6099 端口监听。"
    log "  可执行 pico-doctor.sh 或查看 logcat 排查。"
fi

# ── 7. 进程保活守护 ─────────────────────────────────────────────────────────
log "启动进程守护循环。"
while true; do
    sleep 15
    if pm path "$PKG" >/dev/null 2>&1; then
        if ! pidof "$PKG" >/dev/null 2>&1; then
            log "$PKG 未运行，正在重新拉起 ..."
            am start -n "$PKG/$ACTIVITY" >/dev/null 2>&1 || true
        fi
    fi
done

#!/usr/bin/env bash
# 在 Ubuntu/Debian 上安装 Waydroid、ARM 转译、PicoOnebot APK 与无头保活服务。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APK=""
RUN_USER="${SUDO_USER:-}"
INSTALL_LIBNDK=1

usage() {
  cat <<'EOF'
用法: sudo bash scripts/install-waydroid.sh --apk PicoOnebot_x.y.z.apk [--user 用户名] [--skip-libndk]

--user         运行 Waydroid session 的普通用户;sudo 调用时默认取 SUDO_USER
--skip-libndk  已自行安装 ARM 转译层时跳过 casualsnek/waydroid_script
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:-}"; shift 2 ;;
    --user) RUN_USER="${2:-}"; shift 2 ;;
    --skip-libndk) INSTALL_LIBNDK=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[ "$(id -u)" = 0 ] || { echo "请用 sudo/root 运行" >&2; exit 1; }
[ -n "$APK" ] || { echo "必须传 --apk" >&2; usage >&2; exit 2; }
[ -f "$APK" ] || { echo "找不到 APK: $APK" >&2; exit 1; }
[ -n "$RUN_USER" ] || { echo "无法确定普通用户,请传 --user" >&2; exit 1; }
id "$RUN_USER" >/dev/null 2>&1 || { echo "用户不存在: $RUN_USER" >&2; exit 1; }
[ "$(id -u "$RUN_USER")" != 0 ] || { echo "Waydroid session 不能以 root 运行" >&2; exit 1; }

APK="$(readlink -f "$APK")"
RUN_HOME="$(getent passwd "$RUN_USER" | cut -d: -f6)"
RUN_GROUP="$(id -gn "$RUN_USER")"
RUNTIME_DIR="/run/pico-wayland-$RUN_USER"

log() { printf '[install %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

log "安装系统依赖"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y adb ca-certificates curl git python3 python3-venv weston

if ! command -v waydroid >/dev/null 2>&1; then
  log "添加 Waydroid 软件源"
  curl -fsSL https://repo.waydro.id | bash
  apt-get update
  apt-get install -y waydroid
fi

if [ ! -e /dev/binder ]; then
  grep -q binder /proc/filesystems || {
    echo "当前内核没有 binderfs。先按 docs/env/waydroid-setup.md 启用 CONFIG_ANDROID_BINDER_IPC/BINDERFS。" >&2
    exit 1
  }
  log "挂载 binderfs"
  mkdir -p /dev/binderfs
  mountpoint -q /dev/binderfs || mount -t binder binder /dev/binderfs
  ln -sf /dev/binderfs/binder /dev/binder
  ln -sf /dev/binderfs/hwbinder /dev/hwbinder
  ln -sf /dev/binderfs/vndbinder /dev/vndbinder
fi

log "安装 binderfs 开机单元"
cat >/etc/systemd/system/waydroid-binder.service <<'EOF'
[Unit]
Description=Android binderfs for Waydroid/redroid
Before=waydroid-container.service docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/mkdir -p /dev/binderfs
ExecStart=/bin/mount -t binder binder /dev/binderfs
ExecStart=/bin/ln -sf /dev/binderfs/binder /dev/binder
ExecStart=/bin/ln -sf /dev/binderfs/hwbinder /dev/hwbinder
ExecStart=/bin/ln -sf /dev/binderfs/vndbinder /dev/vndbinder

[Install]
WantedBy=multi-user.target
EOF
systemctl enable waydroid-binder.service

if [ ! -f /var/lib/waydroid/images/system.img ]; then
  log "初始化 Waydroid Android 13 VANILLA 镜像"
  waydroid init -s VANILLA
fi

if [ "$INSTALL_LIBNDK" = 1 ] && [ ! -f /var/lib/waydroid/overlay/system/lib/libndk_translation.so ]; then
  log "安装 libndk_translation(手表 QQ 只有 armeabi-v7a)"
  if [ ! -d /opt/waydroid_script/.git ]; then
    git clone --depth 1 https://github.com/casualsnek/waydroid_script /opt/waydroid_script
  fi
  python3 -m venv /opt/waydroid_script/venv
  /opt/waydroid_script/venv/bin/pip install -r /opt/waydroid_script/requirements.txt
  (cd /opt/waydroid_script && ./venv/bin/python3 main.py -a 13 install libndk)
fi

[ -f /var/lib/waydroid/overlay/system/lib/libndk_translation.so ] || {
  echo "缺少 libndk_translation.so,armeabi-v7a 的手表 QQ 无法启动。" >&2
  exit 1
}

log "关闭 Waydroid 无窗口冻结"
PROP=/var/lib/waydroid/waydroid_base.prop
touch "$PROP"
sed -i '/^persist.waydroid.suspend=/d' "$PROP"
echo 'persist.waydroid.suspend=false' >>"$PROP"

log "安装 PicoOnebot 运维文件"
install -d /opt/pico-onebot
install -m 0755 "$HERE/picoctl.sh" "$HERE/picoqr.py" /opt/pico-onebot/
install -m 0644 "$APK" /opt/pico-onebot/PicoOnebot.apk
install -m 0644 "$HERE/pico-onebot.service" /etc/systemd/system/pico-onebot@.service
install -m 0644 "$HERE/pico-wayland.service" /etc/systemd/system/pico-wayland@.service
systemctl daemon-reload

log "准备无头 Wayland 与 ADB 授权"
systemctl enable --now "pico-wayland@$RUN_USER.service"
install -d -o "$RUN_USER" -g "$RUN_GROUP" "$RUN_HOME/.android"
runuser -u "$RUN_USER" -- env HOME="$RUN_HOME" adb start-server >/dev/null
runuser -u "$RUN_USER" -- env HOME="$RUN_HOME" adb kill-server >/dev/null 2>&1 || true
[ -f "$RUN_HOME/.android/adbkey.pub" ] || { echo "ADB 密钥生成失败" >&2; exit 1; }
ADB_DATA="$RUN_HOME/.local/share/waydroid/data/misc/adb"
install -d -o "$RUN_USER" -g "$RUN_GROUP" "$ADB_DATA"
install -o "$RUN_USER" -g "$RUN_GROUP" -m 0600 "$RUN_HOME/.android/adbkey.pub" "$ADB_DATA/adb_keys"

PUBLIC_HOST="$(hostname -I 2>/dev/null | awk '{print $1}')"
run_as_user() {
  runuser -u "$RUN_USER" -- env \
    HOME="$RUN_HOME" \
    XDG_RUNTIME_DIR="$RUNTIME_DIR" \
    WAYLAND_DISPLAY=wayland-pico \
    PICO_WAYLAND_DISPLAY=wayland-pico \
    PICO_PUBLIC_HOST="${PUBLIC_HOST:-<服务器IP>}" \
    "$@"
}

log "启动 Waydroid、安装混合 APK"
run_as_user /opt/pico-onebot/picoctl.sh install /opt/pico-onebot/PicoOnebot.apk

systemctl enable --now "pico-onebot@$RUN_USER.service"

log "安装完成"
echo "WebUI: http://${PUBLIC_HOST:-<服务器IP>}:6099/"
echo "初始密码: picopico (首次进入控制台时必须设置至少 8 位的新密码)"
echo "终端二维码: sudo -u $RUN_USER env XDG_RUNTIME_DIR=$RUNTIME_DIR PICO_WAYLAND_DISPLAY=wayland-pico /opt/pico-onebot/picoctl.sh qr"
run_as_user /opt/pico-onebot/picoctl.sh qr --once || true

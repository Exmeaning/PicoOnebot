#!/usr/bin/env bash
# 在 Ubuntu/Debian 上安装 Waydroid、ARM 转译、PicoOnebot APK 与无头保活服务。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APK=""
RUN_USER="${SUDO_USER:-}"
INSTALL_LIBNDK=1
ARCH="$(uname -m)"

# 官方 Waydroid APT 仓库签名公钥 SHA-256 (https://repo.waydro.id/waydroid.gpg)
# 如上游轮换密钥，可通过环境变量 WAYDROID_GPG_SHA256 显式覆盖
WAYDROID_GPG_URL="https://repo.waydro.id/waydroid.gpg"
WAYDROID_GPG_SHA256="${WAYDROID_GPG_SHA256:-71fe05d735c812e15fe229bf10106b02b62561be8aa5280d63a58e25a5c0c5e2}"

# 固定 casualsnek/waydroid_script 的已校验 commit SHA，避免拉取可变 HEAD 执行未知代码
WAYDROID_SCRIPT_REPO="https://github.com/casualsnek/waydroid_script.git"
WAYDROID_SCRIPT_COMMIT="${WAYDROID_SCRIPT_COMMIT:-4a50dabd97fcb632a608b1421105258862a5b994}"

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

detect_waydroid_codename() {
  local os_release="" codename=""
  if [ -e /etc/os-release ]; then
    os_release=/etc/os-release
  elif [ -e /usr/lib/os-release ]; then
    os_release=/usr/lib/os-release
  fi
  if [ -n "$os_release" ]; then
    codename="$(grep -m1 '^UBUNTU_CODENAME=' "$os_release" | cut -d'=' -f2 | tr -d '"' || true)"
    [ -n "$codename" ] || codename="$(grep -m1 '^DEBIAN_CODENAME=' "$os_release" | cut -d'=' -f2 | tr -d '"' || true)"
    [ -n "$codename" ] || codename="$(grep -m1 '^VERSION_CODENAME=' "$os_release" | cut -d'=' -f2 | tr -d '"' || true)"
  fi
  if [ -z "$codename" ] && [ -e /etc/debian_version ]; then
    codename="$(cut -d / -f 1 /etc/debian_version)"
  fi
  case "$codename" in
    mantic|focal|jammy|kinetic|lunar|noble|oracular|plucky|questing|resolute|buster|bookworm|bullseye|trixie|sid|landing|crimson|byzantium|amber)
      printf '%s\n' "$codename"
      return 0
      ;;
    *)
      echo "不支持的 Debian/Ubuntu 发行版代号: '${codename:-unknown}' (可通过 WAYDROID_CODENAME 指定)" >&2
      return 1
      ;;
  esac
}

setup_waydroid_apt_repo() {
  local codename="${WAYDROID_CODENAME:-}"
  if [ -z "$codename" ]; then
    codename="$(detect_waydroid_codename)"
  fi
  local tmp_gpg
  tmp_gpg="$(mktemp)"
  trap 'rm -f "$tmp_gpg"' RETURN

  log "下载并校验 Waydroid APT GPG 公钥 ($WAYDROID_GPG_URL)"
  curl --proto '=https' --tlsv1.2 -fsSL -o "$tmp_gpg" "$WAYDROID_GPG_URL"
  printf '%s  %s\n' "$WAYDROID_GPG_SHA256" "$tmp_gpg" | sha256sum -c -

  install -d -m 0755 /usr/share/keyrings /etc/apt/sources.list.d
  install -m 0644 "$tmp_gpg" /usr/share/keyrings/waydroid.gpg
  printf 'deb [signed-by=/usr/share/keyrings/waydroid.gpg] https://repo.waydro.id/ %s main\n' "$codename" \
    > /etc/apt/sources.list.d/waydroid.list
  chmod 0644 /etc/apt/sources.list.d/waydroid.list
}

log "安装系统依赖"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y adb ca-certificates curl git python3 python3-venv weston

if ! command -v waydroid >/dev/null 2>&1; then
  log "声明式配置 Waydroid APT 软件源 (signed-by)"
  setup_waydroid_apt_repo
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
ConditionPathIsMountPoint=!/dev/binderfs

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/sh -c 'mkdir -p /dev/binderfs && (mountpoint -q /dev/binderfs || mount -t binder binder /dev/binderfs) && ln -sf /dev/binderfs/binder /dev/binder && ln -sf /dev/binderfs/hwbinder /dev/hwbinder && ln -sf /dev/binderfs/vndbinder /dev/vndbinder'

[Install]
WantedBy=multi-user.target
EOF
systemctl enable waydroid-binder.service

if [ ! -f /var/lib/waydroid/images/system.img ]; then
  log "初始化 Waydroid Android 13 VANILLA 镜像"
  waydroid init -s VANILLA
fi

if [ "$ARCH" = "x86_64" ]; then
  if [ "$INSTALL_LIBNDK" = 1 ] && [ ! -f /var/lib/waydroid/overlay/system/lib/libndk_translation.so ]; then
    log "安装 libndk_translation (手表 QQ 仅含 armeabi-v7a，固定 waydroid_script@${WAYDROID_SCRIPT_COMMIT:0:12})"
    if [ ! -d /opt/waydroid_script/.git ]; then
      rm -rf /opt/waydroid_script
      git init /opt/waydroid_script
      git -C /opt/waydroid_script remote add origin "$WAYDROID_SCRIPT_REPO"
    fi
    git -C /opt/waydroid_script fetch --depth 1 origin "$WAYDROID_SCRIPT_COMMIT"
    git -C /opt/waydroid_script checkout --force FETCH_HEAD
    if [ "$(git -C /opt/waydroid_script rev-parse HEAD)" != "$WAYDROID_SCRIPT_COMMIT" ]; then
      echo "waydroid_script commit 校验失败，期望 $WAYDROID_SCRIPT_COMMIT" >&2
      exit 1
    fi
    python3 -m venv /opt/waydroid_script/venv
    /opt/waydroid_script/venv/bin/pip install -r /opt/waydroid_script/requirements.txt
    (cd /opt/waydroid_script && ./venv/bin/python3 main.py -a 13 install libndk)
  fi

  [ -f /var/lib/waydroid/overlay/system/lib/libndk_translation.so ] || {
    echo "缺少 libndk_translation.so,armeabi-v7a 的手表 QQ 在 x86_64 上无法启动。" >&2
    exit 1
  }
else
  log "检测到非 x86_64 架构 ($ARCH)，跳过 libndk_translation 安装与校验。"
fi

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
echo "终端二维码: sudo -u $RUN_USER env XDG_RUNTIME_DIR=$RUNTIME_DIR PICO_WAYLAND_DISPLAY=wayland-pico PICO_WEBUI_TOKEN=<新密码> /opt/pico-onebot/picoctl.sh qr"
run_as_user /opt/pico-onebot/picoctl.sh qr --once || true

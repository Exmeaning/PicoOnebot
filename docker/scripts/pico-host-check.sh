#!/usr/bin/env bash
# pico-host-check.sh: 宿主机 Binder 支持检查脚本
#
# 用法:
#   curl -fsSL https://raw.githubusercontent.com/exmeaning/PicoOnebot/main/docker/scripts/pico-host-check.sh | bash
# 或
#   bash docker/scripts/pico-host-check.sh

set -u

RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; BLD=$'\033[1m'; RST=$'\033[0m'
ok()   { echo "${GRN}✅ $*${RST}"; }
warn() { echo "${YLW}⚠️  $*${RST}"; }
bad()  { echo "${RED}❌ $*${RST}"; }
hr()   { echo; echo "${BLD}── $* ──${RST}"; }

echo "${BLD}PicoOnebot 宿主机 Binder 环境检查${RST}"
echo "内核版本: $(uname -r)    系统架构: $(uname -m)"

# ── 1. 运行环境识别 ─────────────────────────────────────────────────────────
hr "1. 运行环境"
env_kind="normal"
if grep -qiE 'microsoft|wsl' /proc/version 2>/dev/null; then
    env_kind="wsl"
    warn "检测到 WSL 环境 ($(grep -oiE 'microsoft[^ ]*' /proc/version | head -1))"
elif [ -f /proc/1/environ ] && grep -qa 'container=lxc' /proc/1/environ 2>/dev/null; then
    env_kind="lxc"
    warn "检测到 LXC/LXD 容器环境"
elif [ -d /proc/vz ] && [ ! -d /proc/bc ]; then
    env_kind="openvz"
    warn "检测到 OpenVZ/Virtuozzo 容器环境"
else
    ok "标准物理机 / KVM 虚拟机环境"
fi

# ── 2. 内核 Binder 支持 ─────────────────────────────────────────────────────
hr "2. 内核 Binder 支持"
KCFG=""
for c in /proc/config.gz "/boot/config-$(uname -r)" "/lib/modules/$(uname -r)/config"; do
    [ -r "$c" ] && { KCFG="$c"; break; }
done

cfg_get() {
    [ -n "$KCFG" ] || { echo unknown; return; }
    local line
    if [[ "$KCFG" == *.gz ]]; then line="$(zcat "$KCFG" 2>/dev/null | grep -E "^$1=" || true)"
    else line="$(grep -E "^$1=" "$KCFG" 2>/dev/null || true)"; fi
    [ -n "$line" ] && echo "${line#*=}" || echo n
}

if [ -n "$KCFG" ]; then
    echo "内核配置文件: $KCFG"
    IPC="$(cfg_get CONFIG_ANDROID_BINDER_IPC)"
    BFS="$(cfg_get CONFIG_ANDROID_BINDERFS)"
    echo "CONFIG_ANDROID_BINDER_IPC = $IPC"
    echo "CONFIG_ANDROID_BINDERFS   = $BFS"
else
    IPC=unknown; BFS=unknown
    warn "未找到内核配置文件，根据已加载模块与设备节点进行判断"
fi

echo "-- 已加载模块 --"
lsmod 2>/dev/null | grep -E '^(binder_linux|ashmem_linux)' || echo "（未加载 binder_linux 模块）"

echo "-- 文件系统支持 --"
if grep -qw binder /proc/filesystems 2>/dev/null; then
    ok "/proc/filesystems 包含 binder (支持 binderfs)"
    binderfs_ok=1
else
    echo "（/proc/filesystems 未包含 binder）"
    binderfs_ok=0
fi

# ── 3. 现有设备节点 ─────────────────────────────────────────────────────────
hr "3. /dev 下的 Binder 设备节点"
nodes_present=0
nodes_bad=""
for n in /dev/binder /dev/hwbinder /dev/vndbinder; do
    if [ -e "$n" ]; then
        nodes_present=1
        mode="$(stat -c %a "$n")"
        printf '%-16s mode=%s owner=%s\n' "$n" "$mode" "$(stat -c '%U:%G' "$n")"
        [ "$mode" = "666" ] || nodes_bad="$nodes_bad $n"
    else
        printf '%-16s 不存在\n' "$n"
    fi
done
[ "$nodes_present" = "0" ] && echo "（未检测到设备节点，若使用 binderfs 模式属于正常状态）"

# ── 4. 检查结论与建议 ───────────────────────────────────────────────────────
hr "4. 检查结论与建议"

if [ "$binderfs_ok" = "1" ] && [ "$nodes_present" = "0" ]; then
    ok "内核支持 binderfs。"
    echo
    echo "建议启动命令（无需映射宿主机 binder 设备）："
    cat <<'CMD'

  docker run -d --name pico-onebot --privileged --shm-size 1g \
    -p 6099:6099 -p 3001:3001 \
    -v pico-data:/data \
    --restart unless-stopped \
    ghcr.io/exmeaning/pico-onebot:latest

CMD
    exit 0
fi

if [ "$nodes_present" = "1" ]; then
    if [ -n "$nodes_bad" ]; then
        warn "检测到 Binder 设备节点权限不足 (需为 0666):$nodes_bad"
        echo
        echo "${BLD}方案 1（推荐）${RST}: 若内核支持 binderfs（当前: $([ "$binderfs_ok" = 1 ] && echo 支持 || echo 不支持)），无需映射设备节点，交由容器自动挂载。"
        echo
        echo "${BLD}方案 2${RST}: 修改宿主机设备权限为 0666 并持久化配置："
        cat <<'CMD'

  sudo chmod 666 /dev/binder /dev/hwbinder /dev/vndbinder

  # 持久化 udev 规则
  sudo tee /etc/udev/rules.d/99-binder.rules >/dev/null <<'RULES'
KERNEL=="binder",    MODE="0666"
KERNEL=="hwbinder",  MODE="0666"
KERNEL=="vndbinder", MODE="0666"
RULES
  sudo udevadm control --reload && sudo udevadm trigger

CMD
    else
        ok "Binder 设备节点存在且权限正常 (0666)。"
    fi
    echo "启动命令（通过 --device 传入字符设备）："
    cat <<'CMD'

  docker run -d --name pico-onebot --privileged --shm-size 1g \
    --device /dev/binder --device /dev/hwbinder --device /dev/vndbinder \
    -p 6099:6099 -p 3001:3001 \
    -v pico-data:/data \
    --restart unless-stopped \
    ghcr.io/exmeaning/pico-onebot:latest

CMD
    exit 0
fi

# 既没有 binderfs 也没有设备节点
bad "当前内核未检测到 Binder 支持。"
echo
case "$env_kind" in
    wsl)
        echo "WSL2 默认内核未开启 CONFIG_ANDROID_BINDER_IPC。可选方案："
        echo "  1) 自行编译 WSL2 内核并开启 Binder 支持，在 .wslconfig 中指定内核路径；"
        echo "  2) 在标准 Linux 环境（物理机或 KVM 虚拟机）中运行；"
        echo "  3) 改用 APK 方式在实体 Android 设备或模拟器中运行。"
        ;;
    lxc|openvz)
        echo "LXC / OpenVZ 容器通常不支持加载独立 Binder 模块，建议使用物理机或 KVM 虚拟机。"
        ;;
    *)
        echo "可尝试加载内核模块："
        cat <<'CMD'

  sudo modprobe binder_linux devices=binder,hwbinder,vndbinder
  # 持久化配置
  echo binder_linux | sudo tee /etc/modules-load.d/binder.conf
  echo "options binder_linux devices=binder,hwbinder,vndbinder" \
    | sudo tee /etc/modprobe.d/binder.conf

CMD
        echo "若提示 'Module binder_linux not found'，说明当前内核未包含该模块，需更换内核或选择其他部署方式。"
        ;;
esac
exit 1

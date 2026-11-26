#!/system/bin/pico-bb/sh
# pico-entrypoint.sh: 容器启动入口脚本
# 负责 Binder 设备校验、权限检查、可选 kmsg 日志转发与启动 Android init (PID 1)。
#
# 注意：本脚本在 /init 之前运行。此时 /apex 尚未挂载，Android 自带的 /system/bin/sh、stat、
# grep 等全部动态链接到 /apex/com.android.runtime 下的 linker/libc，execve 会直接报
# "no such file or directory"。因此入口必须使用镜像内置的静态 BusyBox (/system/bin/pico-bb)，
# 且 PATH 里 BusyBox 必须排在 /system/bin 之前。
#
# 环境变量：
#   PICO_LOG_KMSG=0|1|all   将容器启动后的 /dev/kmsg 转发至 stdout。
#                           默认 0 (关闭，避免泄露宿主机内核日志；pico-boot 已直接输出至容器 stdout)
#                           1 (仅转发容器 Android init/核心服务新增日志，去重 pico-boot 输出)
#                           all (转发启动后全量内核日志，仅用于排障，含宿主机共享内核输出)
#   PICO_BINDER_STRICT=1    Binder 校验未通过时直接退出（默认 0，仅告警）

export PATH=/system/bin/pico-bb:/system/bin:/system/xbin

say() { printf '[pico-entrypoint] %s\n' "$*"; }

say "=============================================================="
say "PicoOnebot All-in-One 容器启动"
say "架构: $(uname -m)   内核: $(uname -r)"
say "=============================================================="

# ── Binder 设备校验 ─────────────────────────────────────────────────────────
binder_state="pending"
binder_bad=""

for node in /dev/binder /dev/hwbinder /dev/vndbinder; do
    [ -e "$node" ] || continue
    binder_state="present"
    mode="$(stat -L -c %a "$node" 2>/dev/null)"
    owner="$(stat -L -c '%U:%G' "$node" 2>/dev/null)"
    say "检测到 Binder 设备 $node (mode=$mode owner=$owner)"
    if [ "$mode" != "666" ]; then
        if chmod 0666 "$node" 2>/dev/null && [ "$(stat -L -c %a "$node" 2>/dev/null)" = "666" ]; then
            say "  已将 $node 权限修正为 0666"
        else
            say "  警告: 无法修改 $node 权限 (当前 0$mode)"
            binder_bad="$binder_bad $node"
        fi
    fi
done

if [ -n "$binder_bad" ]; then
    say "============================================================"
    say "错误: Binder 设备权限不足:$binder_bad"
    say "Android 系统服务访问 Binder 设备需要 0666 权限。"
    say "处理建议:"
    say "  1. (推荐) 使用 binderfs，移除 --device 设备映射，由容器自动挂载；"
    say "  2. 在宿主机执行: sudo chmod 666 /dev/binder /dev/hwbinder /dev/vndbinder"
    say "详细配置请参见 docs/BINDER.md"
    say "============================================================"
    if [ "${PICO_BINDER_STRICT:-0}" = "1" ]; then
        say "PICO_BINDER_STRICT=1，退出容器。"
        exit 78
    fi
    say "继续执行启动流程。如需严格校验退出可设置 PICO_BINDER_STRICT=1。"
elif [ "$binder_state" = "present" ]; then
    say "Binder 设备校验通过 (权限 0666)。"
else
    say "未检测到外部 Binder 节点映射，将通过 binderfs 自动挂载。"
fi

# ── 可选转发 /dev/kmsg 至 stdout ────────────────────────────────────────────
# /dev/kmsg 属于宿主机共享内核环形缓冲：
# - 默认关闭 (PICO_LOG_KMSG=0)，避免向容器 stdout 泄露无关宿主机内核日志；
# - 开启 1/all 时记录容器启动时刻 (/proc/uptime)，丢弃历史宿主机记录并过滤已直接输出至 stdout 的 pico-boot 日志。
kmsg_relay() {
    _mode="$1"
    _start_sec=0
    if [ -r /proc/uptime ]; then
        read -r _up _ < /proc/uptime 2>/dev/null || true
        _up_int="${_up%%.*}"
        case "$_up_int" in
            ''|*[!0-9]*) _start_sec=0 ;;
            *) _start_sec="$_up_int" ;;
        esac
    fi
    cat /dev/kmsg 2>/dev/null | while IFS= read -r _line; do
        # 跳过续行元数据 (以空格开头或不含分号)
        case "$_line" in
            ' '*) continue ;;
            *';'*) ;;
            *) continue ;;
        esac
        _hdr="${_line%%;*}"
        _msg="${_line#*;}"
        # 提取第 3 字段 (微秒时间戳) 并转为整秒，跳过容器启动之前的宿主机历史日志
        _ts_field="${_hdr#*,}"
        _ts_field="${_ts_field#*,}"
        _ts_us="${_ts_field%%,*}"
        case "$_ts_us" in
            ''|*[!0-9]*) continue ;;
        esac
        if [ "${#_ts_us}" -le 6 ]; then
            _ts_sec=0
        else
            _ts_sec="${_ts_us%??????}"
        fi
        [ "$_ts_sec" -ge "$_start_sec" ] || continue
        # 避免重复输出已直写 /proc/1/fd/1 的 pico-boot / pico-entrypoint 日志
        case "$_msg" in
            *'[pico-boot '*|*'[pico-entrypoint]'*) continue ;;
        esac
        if [ "$_mode" = "all" ]; then
            printf '[kmsg] %s\n' "$_msg"
        else
            case "$_msg" in
                init:*|*'servicemanager'*|*'surfaceflinger'*|*'zygote'*|*'keystore'*|*'redroid'*)
                    printf '[kmsg] %s\n' "$_msg"
                    ;;
            esac
        fi
    done
}

case "${PICO_LOG_KMSG:-0}" in
    0|'')
        say "已禁用 kmsg 日志转发 (默认 PICO_LOG_KMSG=0，可设为 1 或 all 排障)。"
        ;;
    all)
        if [ -r /dev/kmsg ]; then
            say "WARN: 已开启 kmsg 全量日志转发 (仅转发启动后记录；注意 /dev/kmsg 包含宿主机内核输出)。"
            kmsg_relay all &
        else
            say "WARN: /dev/kmsg 不可读，无法转发日志。"
        fi
        ;;
    *)
        if [ -r /dev/kmsg ]; then
            say "已开启 kmsg 核心 Android 服务日志转发 (已过滤历史宿主机记录与重复输出)。"
            kmsg_relay core &
        else
            say "WARN: /dev/kmsg 不可读，无法转发日志。"
        fi
        ;;
esac

if [ ! -x /init ]; then
    say "ERROR: /init 不存在或不可执行，无法启动 Android。"
    exit 127
fi

# shellcheck source=docker/pico-network.sh
. /system/bin/pico-network.sh

# redroid 官方镜像的 ENTRYPOINT 是 ["/init","qemu=1","androidboot.hardware=redroid"]。
# 我们替换了 ENTRYPOINT，这两个参数必须由这里补回：缺少 androidboot.hardware=redroid 时
# ro.hardware 不是 redroid，hwcomposer HAL 找不到 hwcomposer.redroid.so 而退出(status 1)，
# 其 onrestart 会不断重启 surfaceflinger → zygote → 整个系统 5 秒一轮死循环，永远到不了 boot_completed。
has_boot_prop() {
    _key="$1"
    shift
    for _arg in "$@"; do
        case "$_arg" in
            "${_key}="*) return 0 ;;
        esac
    done
    return 1
}

has_boot_prop "androidboot.hardware" "$@" || set -- androidboot.hardware=redroid "$@"
has_boot_prop "qemu" "$@" || set -- qemu=1 "$@"

# 仅在 x86_64 架构且镜像已内置 libndk_translation.so 时注入 ARM 转译属性，
# 避免在 linux/arm64 镜像上误覆盖原生 ABI 列表或加载不存在的转译层。
if [ "$(uname -m)" = "x86_64" ] && [ -f /system/lib64/libndk_translation.so ]; then
    say "检测到 x86_64 与 libndk_translation.so，自动注入 ARM 转译启动参数。"
    for _prop in \
        "ro.product.cpu.abilist=x86_64,arm64-v8a,x86,armeabi-v7a,armeabi" \
        "ro.product.cpu.abilist32=x86,armeabi-v7a,armeabi" \
        "ro.product.cpu.abilist64=x86_64,arm64-v8a" \
        "ro.dalvik.vm.isa.arm=x86" \
        "ro.dalvik.vm.isa.arm64=x86_64" \
        "ro.enable.native.bridge.exec=1" \
        "ro.dalvik.vm.native.bridge=libndk_translation.so" \
        "ro.ndk_translation.version=0.2.3"; do
        _key="${_prop%%=*}"
        has_boot_prop "$_key" "$@" || set -- "$@" "$_prop"
    done
fi

say "启动 Android init: /init $*"
say "首次启动初始化预计耗时 2–5 分钟。"

exec /init "$@"

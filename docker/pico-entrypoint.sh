#!/system/bin/sh
# pico-entrypoint.sh: 容器启动入口脚本
# 负责 Binder 设备校验、权限检查、kmsg 日志转发与启动 Android init (PID 1)。
#
# 环境变量：
#   PICO_LOG_KMSG=1|all|0   将 /dev/kmsg 转发至 stdout。默认 1 (关键日志)，all (全量)，0 (关闭)
#   PICO_BINDER_STRICT=1    Binder 校验未通过时直接退出（默认 0，仅告警）

say() { echo "[pico-entrypoint] $*"; }

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
    mode="$(stat -c %a "$node" 2>/dev/null)"
    owner="$(stat -c '%U:%G' "$node" 2>/dev/null)"
    say "检测到 Binder 设备 $node (mode=$mode owner=$owner)"
    if [ "$mode" != "666" ]; then
        if chmod 0666 "$node" 2>/dev/null && [ "$(stat -c %a "$node" 2>/dev/null)" = "666" ]; then
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

# ── 转发 /dev/kmsg 至 stdout ────────────────────────────────────────────────
case "${PICO_LOG_KMSG:-1}" in
    0) say "已禁用 kmsg 日志转发 (PICO_LOG_KMSG=0)。" ;;
    all)
        if [ -r /dev/kmsg ]; then
            say "已开启 kmsg 全量日志转发。"
            ( cat /dev/kmsg 2>/dev/null | while IFS= read -r line; do echo "[kmsg] $line"; done ) &
        else
            say "WARN: /dev/kmsg 不可读，无法转发日志。"
        fi
        ;;
    *)
        if [ -r /dev/kmsg ]; then
            say "已开启 kmsg 核心日志转发 (可通过 PICO_LOG_KMSG=all 开启全量)。"
            ( cat /dev/kmsg 2>/dev/null \
                | grep -i -E 'init:|binder|pico|servicemanager|surfaceflinger|zygote|keystore|permission denied|selinux' \
                | while IFS= read -r line; do echo "[kmsg] $line"; done ) &
        else
            say "WARN: /dev/kmsg 不可读，无法转发日志。"
        fi
        ;;
esac

if [ ! -x /init ]; then
    say "ERROR: /init 不存在或不可执行，无法启动 Android。"
    exit 127
fi

say "启动 Android init: /init $*"
say "首次启动初始化预计耗时 2–5 分钟。"

exec /init "$@"

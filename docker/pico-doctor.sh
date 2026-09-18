#!/system/bin/sh
# pico-doctor.sh: 容器内部环境与运行状态诊断脚本
#
# 用法:
#   docker exec <容器名> /system/bin/sh /system/bin/pico-doctor.sh

hr() { echo; echo "===== $* ====="; }

echo "PicoOnebot 容器诊断报告  $(date)"

hr "1. Binder 设备节点与权限"
for n in /dev/binder /dev/hwbinder /dev/vndbinder; do
    if [ -e "$n" ]; then
        printf '%-18s mode=%s owner=%s:%s target=%s\n' \
            "$n" "$(stat -c %a "$n" 2>/dev/null)" \
            "$(stat -c %U "$n" 2>/dev/null)" "$(stat -c %G "$n" 2>/dev/null)" \
            "$(readlink -f "$n" 2>/dev/null)"
    else
        printf '%-18s 缺失\n' "$n"
    fi
done
echo "-- binderfs 挂载状态 --"
grep -i binder /proc/mounts 2>/dev/null || echo "（未挂载 binderfs）"
echo "-- 检查结果 --"
bad=0
for n in /dev/binder /dev/hwbinder /dev/vndbinder; do
    [ -e "$n" ] || { echo "❌ $n 不存在 (内核缺少 Binder 支持或未配置 --privileged)"; bad=1; continue; }
    [ "$(stat -c %a "$n" 2>/dev/null)" = "666" ] || { echo "❌ $n 权限不是 0666 (Android 系统服务需要 0666 权限)"; bad=1; }
done
[ "$bad" = "0" ] && echo "✅ Binder 节点状态正常"

hr "2. Android 启动状态"
echo "sys.boot_completed = $(getprop sys.boot_completed)"
echo "init.svc.zygote     = $(getprop init.svc.zygote)"
echo "init.svc.pico_boot  = $(getprop init.svc.pico_boot)"
for s in servicemanager surfaceflinger zygote system_server keystore2 audioserver; do
    printf '%-16s %s\n' "$s" "$(pidof "$s" >/dev/null 2>&1 && echo 运行中 || echo 未运行)"
done

hr "3. ARM 转译层（libndk）"
echo "ro.product.cpu.abilist     = $(getprop ro.product.cpu.abilist)"
echo "ro.dalvik.vm.native.bridge = $(getprop ro.dalvik.vm.native.bridge)"
echo "ro.enable.native.bridge.exec = $(getprop ro.enable.native.bridge.exec)"
ls -l /system/lib64/libndk_translation.so 2>/dev/null || echo "⚠️  未检测到 /system/lib64/libndk_translation.so (amd64 架构运行 arm 应用所需)"

hr "4. PicoOnebot 应用状态"
if pm path com.tencent.qqlite >/dev/null 2>&1; then
    pm path com.tencent.qqlite
    echo "版本: $(dumpsys package com.tencent.qqlite 2>/dev/null | grep -m1 versionName | tr -d ' ')"
    echo "进程: $(pidof com.tencent.qqlite >/dev/null 2>&1 && echo 运行中 || echo 未运行)"
else
    echo "❌ com.tencent.qqlite 未安装"
    ls -l /opt/pico-onebot/PicoOnebot.apk 2>/dev/null || echo "❌ 未检测到 /opt/pico-onebot/PicoOnebot.apk"
fi

hr "5. 端口监听（6099=0x17D3 WebUI / 3001=0x0BB9 OneBot）"
awk 'NR>1 && $4=="0A" {print "listen", $2}' /proc/net/tcp /proc/net/tcp6 2>/dev/null | sort -u
grep -qi ':17D3 ' /proc/net/tcp /proc/net/tcp6 2>/dev/null \
    && echo "✅ 6099 端口处于监听状态" || echo "❌ 6099 端口未监听"

hr "6. pico-boot 日志（末 40 行）"
tail -n 40 /data/pico/pico-boot.log 2>/dev/null || echo "（暂无日志）"

hr "7. Binder 相关内核/系统日志"
dmesg 2>/dev/null | grep -i -m 20 'binder\|permission denied' || echo "（无）"

hr "8. 应用日志（末 60 行）"
logcat -d -t 60 -s PicoOnebot:V AndroidRuntime:E 2>/dev/null || echo "（logcat 不可用）"

echo
echo "===== 报告结束 ====="

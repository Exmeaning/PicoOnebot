#!/system/bin/sh
# pico-network.sh: 由 pico-entrypoint.sh source 执行，将容器 DNS 与 hosts 同步给 Android。

explicit_dns=0
for boot_arg in "$@"; do
    case "$boot_arg" in
        androidboot.redroid_net_ndns=*|androidboot.redroid_net_dns*=*) explicit_dns=1 ;;
    esac
done
if [ "$explicit_dns" = 0 ] && [ -r /etc/resolv.conf ]; then
    dns_count=0
    _cr="$(printf '\r')"
    while read -r _kw _val _rest || [ -n "$_kw" ]; do
        [ "$_kw" = "nameserver" ] || continue
        _val="${_val%"$_cr"}"
        _val="${_val%%#*}"
        case "$_val" in
            ""|*[!0-9a-fA-F.:%]*) continue ;;
        esac
        dns_count=$((dns_count + 1))
        set -- "$@" "androidboot.redroid_net_dns${dns_count}=${_val}"
    done < /etc/resolv.conf
    if [ "$dns_count" -gt 0 ]; then
        set -- "$@" "androidboot.redroid_net_ndns=$dns_count"
        say "向 Android 传递容器 DNS ($dns_count 个服务器)"
    fi
fi

# 在可写目录 (/tmp) 暂存合并后的 hosts，避免 /system 只读时回退 mount --bind 提前失败，
# 同时每次从干净来源重建，防止重启累积旧容器 IP。
if mkdir -p /tmp 2>/dev/null && [ -w /tmp ]; then
    hosts_temp="/tmp/pico-hosts.merged"
else
    hosts_temp="/dev/pico-hosts.merged"
fi

if (
    printf '127.0.0.1 localhost\n::1 ip6-localhost\n'
    [ -r /etc/hosts ] && cat /etc/hosts
    [ -r /system/etc/pico-qimei-hosts ] && cat /system/etc/pico-qimei-hosts
) 2>/dev/null | awk '!seen[$0]++' > "$hosts_temp"; then
    chmod 0644 "$hosts_temp"
    if cat "$hosts_temp" > /system/etc/hosts 2>/dev/null; then
        rm -f "$hosts_temp"
    elif ! mount --bind "$hosts_temp" /system/etc/hosts 2>/dev/null; then
        rm -f "$hosts_temp"
        say "WARN: 无法向 Android 同步容器 hosts；请检查 host.docker.internal 映射"
    fi
else
    rm -f "$hosts_temp"
    say "WARN: 无法合并容器 hosts"
fi

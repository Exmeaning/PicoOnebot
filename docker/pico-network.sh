#!/system/bin/sh

explicit_dns=0
for boot_arg in "$@"; do
    case "$boot_arg" in
        androidboot.redroid_net_ndns=*|androidboot.redroid_net_dns*=*) explicit_dns=1 ;;
    esac
done
if [ "$explicit_dns" = 0 ] && [ -r /etc/resolv.conf ]; then
    dns_count=0
    for dns_server in $(awk '$1 == "nameserver" { print $2 }' /etc/resolv.conf); do
        dns_count=$((dns_count + 1))
        set -- "$@" "androidboot.redroid_net_dns${dns_count}=$dns_server"
    done
    if [ "$dns_count" -gt 0 ]; then
        set -- "$@" "androidboot.redroid_net_ndns=$dns_count"
        say "向 Android 传递容器 DNS ($dns_count 个服务器)"
    fi
fi

hosts_temp=/system/etc/pico-hosts.merged
if (cat /etc/hosts /system/etc/hosts /system/etc/pico-qimei-hosts 2>/dev/null | awk '!seen[$0]++') > "$hosts_temp"; then
    chmod 0644 "$hosts_temp"
    if ! cat "$hosts_temp" > /system/etc/hosts; then
        if ! mount --bind "$hosts_temp" /system/etc/hosts; then
            say "WARN: 无法向 Android 同步容器 hosts；请检查 host.docker.internal 映射"
        fi
    fi
else
    say "WARN: 无法合并容器 hosts"
fi

#!/usr/bin/env bash
set -eu

apk="${PICO_APK:-/opt/pico-onebot/PicoOnebot.apk}"
serial="${PICO_SERIAL:-android:5555}"

if [ "${PICO_DNS_PROXY:-0}" = "1" ]; then
  python3 /opt/pico-onebot/dns_proxy.py &
fi

i=0
until adb connect "$serial" >/dev/null 2>&1 && adb -s "$serial" shell true >/dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -lt 90 ] || { echo "等待 Android ADB 超时: $serial" >&2; exit 1; }
  sleep 2
done

until [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done

if ! adb -s "$serial" shell pm path "${PICO_PKG:-com.tencent.qqlite}" >/dev/null 2>&1; then
  [ -f "$apk" ] || { echo "未安装 PicoOnebot,且容器内找不到 APK: $apk" >&2; exit 1; }
  /opt/pico-onebot/picoctl.sh install "$apk"
fi

exec /opt/pico-onebot/picoctl.sh "$@"

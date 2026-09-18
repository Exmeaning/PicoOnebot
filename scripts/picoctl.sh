#!/usr/bin/env bash
# PicoOneBot 运维脚本:在**没有图形界面**的 Linux 上把 Waydroid + 手表 QQ 拉起来并保活。
#
# 解决三件事:
#   1. 宿主重启后要人手工拉  → up / systemd 单元
#   2. 无 GUI 看不到登录二维码 → qr(截屏后在终端重绘二维码)
#   3. 跑着跑着静默失效      → watch(按已知故障模式分级恢复)
#
# "静默失效"不是抽象风险,是这个项目实测踩过三次的坑:
#   · Waydroid 没有窗口时会冻结整个容器(Container: FROZEN),QQ 看着在线但一条消息都收不到;
#   · adb install -r 之后旧 :MSF 进程以 PPID=1 存活,所有 SSO 请求 30s 超时;
#   · 被踢下线后协议端状态变 LOGGED_OUT,但进程还活着,从外面看毫无异常。
# 这三种都不会让进程退出,所以**只看进程在不在是不够的**,必须查协议端自己报的状态。
#
# 用法:
#   linux/picoctl.sh up        拉起 session + 容器 + QQ,等到协议端应答
#   linux/picoctl.sh install <apk>  卸旧装新 + 重启容器 + Hook 完成首次引导
#   linux/picoctl.sh qr        在终端里显示登录二维码
#   linux/picoctl.sh status    一屏看清各层状态
#   linux/picoctl.sh doctor    只做体检,退出码非 0 表示有问题
#   linux/picoctl.sh watch     保活循环(systemd 用这个)
#   linux/picoctl.sh restart   重启整个容器(僵尸 :MSF 的唯一解)
#
# 可调环境变量:
#   PICO_BACKEND=waydroid|adb    PICO_PKG=com.tencent.qqlite
#   PICO_PORT=3001               PICO_TOKEN=                    PICO_INTERVAL=30
#   PICO_ADB=adb                 PICO_SERIAL=<自动探测>          PICO_IP=<自动探测>
#   PICO_WEBUI_PORT=6099         PICO_WEBUI_TOKEN=<控制台密码>   PICO_PUBLIC_HOST=<自动探测>
set -u

BACKEND="${PICO_BACKEND:-waydroid}"
PKG="${PICO_PKG:-com.tencent.qqlite}"
PORT="${PICO_PORT:-3001}"
TOKEN="${PICO_TOKEN:-}"
ADB="${PICO_ADB:-adb}"
INTERVAL="${PICO_INTERVAL:-30}"
ACTIVITY="${PICO_ACTIVITY:-com.tencent.qqnt.watch.app.JumpActivity}"
WEBUI_PORT="${PICO_WEBUI_PORT:-6099}"
WEBUI_TOKEN="${PICO_WEBUI_TOKEN:-picopico}"
WAYLAND_DISPLAY_NAME="${PICO_WAYLAND_DISPLAY:-wayland-0}"
HERE="$(cd "$(dirname "$0")" && pwd)"
# 按用户分文件:root 与普通用户交替使用时 /tmp 下同名文件会 Permission denied,导致 session 起不来
SESSION_LOG="${TMPDIR:-/tmp}/waydroid-session-$(id -u).log"

log() { printf '[picoctl %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

case "$BACKEND" in
  waydroid|adb) ;;
  *) log "PICO_BACKEND 只支持 waydroid 或 adb(当前: $BACKEND)"; exit 2 ;;
esac

# ---------------------------------------------------------------- 底层探测

# Waydroid 的 IP 每次 session 起来都可能变,写死会在重启后莫名其妙连不上
android_ip() {
  if [ -n "${PICO_IP:-}" ]; then echo "$PICO_IP"; return; fi
  if [ "$BACKEND" = "adb" ]; then
    local s; s="$(serial)"
    case "$s" in
      *:*) echo "${s%:*}" ;;
    esac
    return
  fi
  waydroid status 2>/dev/null | tr -d '\000\r' | sed -n 's/.*IP address:[[:space:]]*\([0-9.]*\).*/\1/p' | head -1
}

serial() {
  if [ -n "${PICO_SERIAL:-}" ]; then echo "$PICO_SERIAL"; return; fi
  [ "$BACKEND" = "adb" ] && return 1
  local ip; ip="$(android_ip)"
  [ -n "$ip" ] && echo "$ip:5555"
}

adbx() {
  local s; s="$(serial)"
  [ -z "$s" ] && return 1
  $ADB -s "$s" "$@" 2>/dev/null
}

wd_field() { waydroid status 2>/dev/null | tr -d '\000\r' | sed -n "s/^$1:[[:space:]]*//p" | head -1; }

session_running() {
  if [ "$BACKEND" = "adb" ]; then adbx get-state 2>/dev/null | grep -q '^device$';
  else [ "$(wd_field Session)" = "RUNNING" ]; fi
}
container_running() {
  if [ "$BACKEND" = "adb" ]; then session_running;
  else [ "$(wd_field Container)" = "RUNNING" ]; fi
}
app_running()      { [ -n "$(adbx shell pidof "$PKG")" ]; }

# 协议端自报状态:INIT / TRANSPORT_UP / SESSION_READY / LOGGED_IN / ONLINE / LOGGED_OUT
pico_state() {
  local ip url; ip="$(android_ip)"; [ -z "$ip" ] && return 1
  url="http://$ip:$PORT/_pico_diagnostics"
  [ -n "$TOKEN" ] && url="$url?access_token=$TOKEN"
  curl -fsS --max-time 8 "$url" 2>/dev/null \
    | grep -o '"state":"[A-Z_]*"' | head -1 | cut -d'"' -f4
}

# 僵尸 :MSF 的两个判据,除了重启容器没有别的解法:
#  · logcat 里出现 MsfService timeout —— 确凿,但要等到真的发过请求才看得见;
#  · :MSF 进程的父进程是 1 —— 便宜且**提前**,install -r 之后立刻就能看出来。
# 后者偶尔会漏报(PPID 正常但仍然僵),所以两个都查。
msf_timeouts() { adbx shell logcat -d -t 800 2>/dev/null | tr -d '\000\r' | grep -ac 'MsfService timeout' || true; }
msf_orphan() {
  adbx shell "ps -A -o PPID,NAME | grep ':MSF'" 2>/dev/null |
    awk '{ if ($1 + 0 == 1) found = 1 } END { exit found ? 0 : 1 }'
}

# ---------------------------------------------------------------- 动作

ensure_suspend_off() {
  [ "$BACKEND" = "adb" ] && return 0
  # 没有窗口时 Waydroid 会 SIGSTOP 掉整个容器。无头部署必须关掉这个行为,
  # 而且要写进 base.prop —— `waydroid prop set` 在 session 追踪损坏时会直接报错。
  local f=/var/lib/waydroid/waydroid_base.prop
  [ -w "$f" ] || [ "$(id -u)" = 0 ] || return 0
  if ! grep -q '^persist.waydroid.suspend=false' "$f" 2>/dev/null; then
    log "关闭 waydroid 无窗口冻结(persist.waydroid.suspend=false)"
    sed -i '/^persist.waydroid.suspend=/d' "$f" 2>/dev/null
    echo 'persist.waydroid.suspend=false' >> "$f"
    return 1   # 需要重启 session 才生效
  fi
  return 0
}

session_start() {
  if [ "$BACKEND" = "adb" ]; then
    log "等待 ADB 设备 $(serial)"
    adb_connect || return 1
    wait_boot
    return
  fi
  log "启动 waydroid session"
  # 在 WSL2 环境下普通后台进程会在 subshell 退出时被杀,使用 systemd-run --user 托管
  if command -v systemd-run >/dev/null 2>&1 && systemctl --user is-system-running >/dev/null 2>&1; then
    systemctl --user stop waydroid-session >/dev/null 2>&1 || true
    systemd-run --user --unit=waydroid-session --setenv=WAYLAND_DISPLAY="$WAYLAND_DISPLAY_NAME" waydroid session start >"$SESSION_LOG" 2>&1
  else
    WAYLAND_DISPLAY="$WAYLAND_DISPLAY_NAME" nohup waydroid session start >"$SESSION_LOG" 2>&1 &
  fi
  local i=0
  while [ $i -lt 60 ]; do
    container_running && { log "container RUNNING"; return 0; }
    sleep 2; i=$((i + 1))
  done
  log "等待 container RUNNING 超时,见 $SESSION_LOG"
  return 1
}

session_stop() {
  if [ "$BACKEND" = "adb" ]; then
    log "停止 $PKG(ADB 后端不管理 Android 容器生命周期)"
    adbx shell am force-stop "$PKG" >/dev/null 2>&1 || true
    return
  fi
  log "停止 waydroid session"
  systemctl --user stop waydroid-session >/dev/null 2>&1 || true
  waydroid session stop >/dev/null 2>&1 || true
  sleep 3
}

# 等 Android 真正 boot 完。容器 RUNNING 只代表 LXC 起了,此时 app launch 会被静默丢弃,
# 后续"首启点击"就会落到别的 App(实测点进了时钟)。
wait_boot() {
  local i=0
  while [ $i -lt 45 ]; do
    [ "$(adbx shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && return 0
    sleep 2; i=$((i + 1))
  done
  log "等待 sys.boot_completed 超时"; return 1
}

app_launch() {
  # 必须用 waydroid app launch。adb shell monkey 会打印"已派发"但主进程根本不起来。
  # 发起后轮询 pidof 确认**主进程**真的在(:MSF 会被广播单独拉起,不算),不在就再发一次。
  wait_boot
  local attempt=0 i
  while [ $attempt -lt 2 ]; do
    log "启动 $PKG"
    if [ "$BACKEND" = "adb" ]; then
      adbx shell am start -W -n "$PKG/$ACTIVITY" >/dev/null 2>&1
    else
      waydroid app launch "$PKG" >/dev/null 2>&1 &
    fi
    i=0
    while [ $i -lt 10 ]; do
      sleep 2; app_running && return 0
      i=$((i + 1))
    done
    attempt=$((attempt + 1))
  done
  log "$PKG 主进程没起来"; return 1
}

adb_connect() {
  local s; s="$(serial)"; [ -z "$s" ] && return 1
  local i=0
  while [ $i -lt 30 ]; do
    $ADB connect "$s" >/dev/null 2>&1 || true
    adbx shell true >/dev/null 2>&1 && return 0
    sleep 2; i=$((i + 1))
  done
  return 1
}

show_access() {
  local host="${PICO_PUBLIC_HOST:-$(android_ip)}"
  [ -z "$host" ] && host='<设备IP>'
  log "WebUI: http://$host:$WEBUI_PORT/"
  if [ "$WEBUI_TOKEN" = "picopico" ]; then
    log "首次进入使用初始密码 picopico;设置新密码后请同步更新 PICO_WEBUI_TOKEN"
  else
    log "WebUI 密码: 已通过环境变量提供"
  fi
}

cmd_up() {
  ensure_suspend_off || { session_running && session_stop; }
  session_running || session_start || return 1
  container_running || { session_stop; session_start || return 1; }
  adb_connect || { log "adb 连不上 $(serial)"; return 1; }
  app_running || app_launch
  local i=0 st
  while [ $i -lt 40 ]; do
    st="$(pico_state)"
    case "$st" in
      ONLINE)       log "PicoOneBot ONLINE"; show_access; return 0 ;;
      LOGGED_OUT|"")  ;;
      *)            log "协议端状态 $st,等待登录完成" ;;
    esac
    if [ "$st" = "LOGGED_OUT" ] || { [ -n "$st" ] && [ "$st" != "ONLINE" ] && [ $i -gt 10 ]; }; then
      log "还没登录 —— 跑 '$0 qr' 扫码"
      show_access; return 2
    fi
    sleep 3; i=$((i + 1))
  done
  log "协议端在 ${PORT} 上没有应答"
  return 1
}

cmd_restart() {
  if [ "$BACKEND" = "adb" ]; then
    log "通过 am force-stop 重启 $PKG 及其 :MSF 进程"
    adbx shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 2
  else
    log "重启整个容器(僵尸 :MSF / 冻结容器的唯一解法)"
    session_stop
    session_start || return 1
  fi
  adb_connect
  adbx shell logcat -c >/dev/null 2>&1   # 清掉旧日志,否则下一轮体检会被历史记录误伤
  app_launch
}

# 装/换混合包。签名与官方不同、版本可能倒退,所以一律先卸再装(登录态会清,之后要重新扫码)。
# 装完必须重启整个容器:install 之后旧 :MSF 进程会以 PPID=1 活着,主进程连不上它,显示在线却收不到消息。
cmd_install() {
  local apk="${1:?用法: $0 install <PicoOnebot_x.y.z.apk>}"
  [ -f "$apk" ] || { log "找不到 $apk"; return 1; }
  session_running && container_running || session_start >/dev/null 2>&1
  adb_connect || { log "adb 连不上 $(serial)"; return 1; }
  if adbx shell pm list packages | grep -q "^package:$PKG$"; then
    log "卸载已装的 $PKG(会清数据,之后需要重新扫码)"
    adbx uninstall "$PKG" >/dev/null || true
  fi
  log "安装 $apk"
  local out; out="$($ADB -s "$(serial)" install "$apk" 2>&1 | tr -d '\r')"
  echo "$out" | grep -q "Success" || { log "安装失败:"; echo "$out" | grep -v Incremental; return 1; }
  adbx shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  adbx shell sh -c "mkdir -p /sdcard/pico-onebot && printf '%s\\n' pico-auto-accept-privacy-v1 > /sdcard/pico-onebot/auto-accept-privacy && printf '%s\\n' pico-auto-accept-privacy-v1 > /data/local/tmp/pico-auto-accept-privacy && chmod 0644 /data/local/tmp/pico-auto-accept-privacy" >/dev/null 2>&1 || true
  cmd_restart
  # QIMEI 在首次进程启动时早于隐私页初始化。Hook 点击真实“同意”按钮后，
  # 等日志确认授权已经由 QQ 自己落盘，再重启一次主进程和 :MSF。
  local i=0
  while [ $i -lt 30 ]; do
    adbx shell logcat -d -s PicoOB:I '*:S' 2>/dev/null | grep -q 'Privacy agreement accepted by deployment hook' && break
    sleep 1; i=$((i + 1))
  done
  if [ $i -lt 30 ]; then
    log "隐私授权已由应用内 Hook 完成,重启 QQ 以重新初始化 QIMEI"
    cmd_restart
  else
    log "等待隐私授权 Hook 超时;未执行盲点点击,请查看 PicoOB 日志"
  fi
  log "装好了。接下来:$0 qr  扫码登录"
  show_access
}

cmd_qr() {
  app_running || { log "$PKG 没在跑,先拉起来"; cmd_up >/dev/null 2>&1; }
  local py; py="$(command -v python3 || command -v python)"
  [ -z "$py" ] && { log "需要 python3 才能渲染二维码"; return 1; }
  local ip; ip="$(android_ip)"
  local api_opts=()
  [ -n "$ip" ] && api_opts=(--api "http://$ip:$WEBUI_PORT")
  "$py" "$HERE/picoqr.py" "${api_opts[@]}" "$@"
}

cmd_status() {
  printf 'backend          : %s\n' "$BACKEND"
  if [ "$BACKEND" = "waydroid" ]; then
    printf 'waydroid session : %s\n' "$(wd_field Session)"
    printf 'waydroid container: %s\n' "$(wd_field Container)"
  else
    printf 'adb device       : %s (%s)\n' "$(serial)" "$(session_running && echo READY || echo DOWN)"
  fi
  printf 'android ip       : %s\n' "$(android_ip)"
  printf 'app %-14s: %s\n' "$PKG" "$(app_running && echo RUNNING || echo DOWN)"
  printf 'pico state       : %s\n' "$(pico_state || echo UNREACHABLE)"
  printf 'MsfService timeout: %s (必须是 0)\n' "$(msf_timeouts)"
  printf ':MSF 孤儿进程     : %s\n' "$(msf_orphan && echo 'YES —— 僵尸,需要 restart' || echo no)"
}

cmd_doctor() {
  local bad=0
  session_running   || { echo "NG  $BACKEND session/ADB 不可用"; bad=1; }
  container_running || { echo "NG  $BACKEND container 不可用"; bad=1; }
  app_running       || { echo "NG  $PKG 进程不在"; bad=1; }
  local n; n="$(msf_timeouts)"
  [ "${n:-0}" -gt 2 ] && { echo "NG  MsfService timeout x$n —— 僵尸 :MSF,必须重启容器"; bad=1; }
  msf_orphan && { echo "NG  :MSF 的 PPID=1 —— 僵尸进程,联网请求会全部超时"; bad=1; }
  local st; st="$(pico_state)"
  case "$st" in
    ONLINE) echo "OK  PicoOneBot ONLINE" ;;
    "")     echo "NG  协议端无应答"; bad=1 ;;
    LOGGED_OUT) echo "NG  已掉线,需要重新扫码($0 qr)"; bad=1 ;;
    *)      echo "NG  协议端状态 $st"; bad=1 ;;
  esac
  return $bad
}

# ---------------------------------------------------------------- 保活

cmd_watch() {
  log "保活循环启动,间隔 ${INTERVAL}s"
  local miss=0 last_restart=0 last_state=""
  cmd_up >/dev/null 2>&1
  while :; do
    local st now; st="$(pico_state)"; now="$(date +%s)"

    if [ "$st" = "ONLINE" ]; then
      [ "$last_state" != "ONLINE" ] && log "ONLINE"
      miss=0; last_state="$st"
      # 在线也可能是"假在线":僵尸 :MSF 下 isLogin 仍为 true,只有日志露馅
      local n; n="$(msf_timeouts)"
      if { [ "${n:-0}" -gt 2 ] || msf_orphan; } && [ $((now - last_restart)) -gt 600 ]; then
        log "ONLINE 但 :MSF 已僵(timeout x${n:-0} / PPID=1)—— 假在线,重启容器"
        cmd_restart; last_restart="$now"; miss=0
      fi
      sleep "$INTERVAL"; continue
    fi

    if [ "$st" = "LOGGED_OUT" ]; then
      # 掉线是要人扫码的,重启一百次也没用,只把话说清楚
      [ "$last_state" != "LOGGED_OUT" ] && log "已掉线,等待重新扫码:$0 qr"
      last_state="$st"; sleep "$INTERVAL"; continue
    fi

    case "$st" in
      INIT|TRANSPORT_UP|SESSION_READY|LOGGED_IN)
        # 这些都是登录前/登录中的正常状态。二维码尚未生成时可能长时间停在
        # TRANSPORT_UP，重启 QQ 只会打断 QIMEI 和 wtlogin 的初始化。
        [ "$last_state" != "$st" ] && log "协议端状态 $st,等待登录完成"
        miss=0; last_state="$st"; sleep "$INTERVAL"; continue
        ;;
    esac

    miss=$((miss + 1))
    last_state="$st"
    log "异常(state=${st:-无应答},连续 $miss 次)"

    if ! session_running || ! container_running; then
      log "session/container 掉了,重新拉起"
      cmd_up >/dev/null 2>&1; miss=0
    elif [ "$miss" -ge 6 ] && [ $((now - last_restart)) -gt 300 ]; then
      log "连续 $miss 次异常,升级到重启容器"
      cmd_restart; last_restart="$now"; miss=0
    elif [ "$miss" -ge 3 ]; then
      log "尝试重新拉起 $PKG"
      app_running || app_launch
    fi
    sleep "$INTERVAL"
  done
}

cmd_logout() {
  log "发起退出登录"
  local ip; ip="$(android_ip)"
  if [ -z "$ip" ]; then
    log "无法获取 Android IP"
    return 1
  fi
  local url="http://$ip:$PORT/_pico_logout"
  [ -n "$TOKEN" ] && url="$url?access_token=$TOKEN"
  curl -fsS -X POST --max-time 8 "$url" >/dev/null 2>&1 || {
    python3 "$HERE/../tools/obact.py" _pico_logout >/dev/null 2>&1 || {
      log "调用 _pico_logout 失败(服务未就绪?)"
      return 1
    }
  }
  log "已触发退出登录，等待进入扫码页面..."
  sleep 4
  cmd_qr
}

cmd_login() {
  log "获取并显示登录二维码:"
  cmd_qr
}

case "${1:-status}" in
  up)      shift; cmd_up "$@" ;;
  install) shift; cmd_install "$@" ;;
  qr)      shift; cmd_qr "$@" ;;
  login)   shift; cmd_login "$@" ;;
  logout)  shift; cmd_logout "$@" ;;
  status)  cmd_status ;;
  doctor)  cmd_doctor ;;
  watch)   cmd_watch ;;
  restart) cmd_restart ;;
  stop)    session_stop ;;
  *)       sed -n '2,30p' "$0"; exit 2 ;;
esac

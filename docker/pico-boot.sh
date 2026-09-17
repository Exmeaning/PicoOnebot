#!/system/bin/sh
# pico-boot.sh: Automated startup and watchdog for PicoOnebot inside Redroid

PKG="com.tencent.qqlite"
ACTIVITY="com.tencent.qqnt.watch.app.JumpActivity"
APK="/opt/pico-onebot/PicoOnebot.apk"

log() {
    echo "[pico-boot $(date +%H:%M:%S)] $*"
}

log "Starting PicoOnebot boot service..."

# 1. Wait for Android boot to complete
log "Waiting for sys.boot_completed..."
i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
    i=$((i + 1))
    if [ "$i" -gt 90 ]; then
        log "WARNING: sys.boot_completed wait timeout, proceeding anyway..."
        break
    fi
done
log "Android system boot completed."

# 2. Check and install APK if needed
is_first_install=0
if ! pm path "$PKG" >/dev/null 2>&1; then
    if [ -f "$APK" ]; then
        log "Installing $APK..."
        pm install -r "$APK"
        is_first_install=1
        log "Package $PKG installed successfully."
    else
        log "WARNING: $APK not found and $PKG is not installed yet."
    fi
else
    log "Package $PKG is already installed."
fi

# 3. Grant runtime permissions
if pm path "$PKG" >/dev/null 2>&1; then
    log "Granting permissions for $PKG..."
    pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
    pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
fi

# 4. Launch the app
log "Launching $PKG/$ACTIVITY..."
am start -W -n "$PKG/$ACTIVITY" >/dev/null 2>&1 || true

# 5. Handle first-run privacy agreement & permission dialog taps
if [ "$is_first_install" -eq 1 ]; then
    log "First run detected. Performing automated setup clicks..."
    sleep 6
    # Coordinates based on 720x1256 resolution
    # Tap 1: Allow notifications
    input tap 360 622 2>/dev/null || true
    sleep 2
    # Tap 2: Agree privacy policy
    input tap 535 749 2>/dev/null || true
    sleep 2
    # Tap 3: Welcome screen Login button
    input tap 360 1121 2>/dev/null || true
    sleep 3
    # Restart app once to ensure QIMEI re-initializes with agreed privacy state
    log "Restarting $PKG to finalize QIMEI initialization..."
    am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 2
    am start -W -n "$PKG/$ACTIVITY" >/dev/null 2>&1 || true
    sleep 4
    input tap 360 1121 2>/dev/null || true
fi

log "PicoOnebot is ready! WebUI: http://<HOST>:6099 / OneBot: 3001"

# 6. Watchdog loop
while true; do
    sleep 15
    if pm path "$PKG" >/dev/null 2>&1; then
        if ! pidof "$PKG" >/dev/null 2>&1; then
            log "$PKG is not running, restarting..."
            am start -n "$PKG/$ACTIVITY" >/dev/null 2>&1 || true
        fi
    fi
done

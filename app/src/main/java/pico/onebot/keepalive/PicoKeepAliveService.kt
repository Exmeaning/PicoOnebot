package pico.onebot.keepalive

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import pico.onebot.PicoBoot
import pico.onebot.core.PicoLog

/** Optional accessibility service used only to raise the process keep-alive priority. */
class PicoKeepAliveService : AccessibilityService() {

    private val wakeLockLock = Any()

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 1_000
            info.packageNames = arrayOf(packageName)
            serviceInfo = info
        } catch (t: Throwable) {
            PicoLog.w("keepalive accessibility serviceInfo update failed: " + t.message)
        }
        try {
            PicoBoot.start(applicationContext)
        } catch (t: Throwable) {
            PicoLog.w("keepalive PicoBoot.start failed: " + t.message)
        }
        acquireWakeLock()
        PicoLog.i("keepalive accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        PicoLog.w("keepalive accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        releaseWakeLock()
        PicoLog.i("keepalive accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        PicoLog.i("keepalive accessibility service destroyed")
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        synchronized(wakeLockLock) {
            runCatching {
                val existing = wakeLock
                if (existing != null && existing.isHeld) return
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                val wl = existing ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PicoOnebot:KeepAlive").apply {
                    setReferenceCounted(false)
                }.also { wakeLock = it }
                if (!wl.isHeld) {
                    wl.acquire()
                    PicoLog.i("keepalive partial wake lock acquired")
                }
            }.onFailure { t ->
                PicoLog.w("keepalive wake lock acquire skipped: " + t.message)
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockLock) {
            val wl = wakeLock ?: return
            wakeLock = null
            runCatching {
                if (wl.isHeld) {
                    wl.release()
                    PicoLog.i("keepalive partial wake lock released")
                }
            }.onFailure { t ->
                PicoLog.w("keepalive wake lock release failed: " + t.message)
            }
        }
    }
}

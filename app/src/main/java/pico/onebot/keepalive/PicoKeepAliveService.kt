package pico.onebot.keepalive

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import pico.onebot.PicoBoot
import pico.onebot.core.PicoLog

/** Optional accessibility service used only to raise the process keep-alive priority. */
class PicoKeepAliveService : AccessibilityService() {

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                notificationTimeout = 1_000
                packageNames = arrayOf(packageName)
            }
            PicoBoot.start(applicationContext)
            acquireWakeLock()
            PicoLog.i("keepalive accessibility service connected")
        } catch (t: Throwable) {
            PicoLog.w("keepalive accessibility service setup failed: " + t.message)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        PicoLog.w("keepalive accessibility service interrupted")
    }

    override fun onDestroy() {
        releaseWakeLock()
        PicoLog.i("keepalive accessibility service destroyed")
        super.onDestroy()
    }

    private fun acquireWakeLock() {
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

    private fun releaseWakeLock() {
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

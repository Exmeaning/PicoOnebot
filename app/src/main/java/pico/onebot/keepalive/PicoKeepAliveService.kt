package pico.onebot.keepalive

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import pico.onebot.PicoBoot
import pico.onebot.core.PicoLog

/** Optional accessibility service used only to raise the process keep-alive priority. */
class PicoKeepAliveService : AccessibilityService() {

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
        PicoLog.i("keepalive accessibility service destroyed")
        super.onDestroy()
    }
}

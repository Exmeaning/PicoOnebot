package pico.onebot.core

import java.io.File

object DeploymentAutomation {
    private const val MARKER_CONTENT = "pico-auto-accept-privacy-v1"

    val autoAcceptPrivacy: Boolean
        get() = markerMatches(File("/system/etc/pico-auto-accept-privacy")) ||
            markerMatches(File("/data/local/tmp/pico-auto-accept-privacy")) ||
            markerMatches(File("/sdcard/pico-onebot/auto-accept-privacy"))

    private fun markerMatches(file: File): Boolean = try {
        file.isFile && file.readText().trim() == MARKER_CONTENT
    } catch (_: Throwable) {
        false
    }
}

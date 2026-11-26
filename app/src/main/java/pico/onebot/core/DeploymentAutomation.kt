package pico.onebot.core

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream

object DeploymentAutomation {
    private const val MARKER_CONTENT = "pico-auto-accept-privacy-v1"
    private const val MAX_MARKER_BYTES = 64

    val autoAcceptPrivacy: Boolean
        get() = markerMatches(File("/system/etc/pico-auto-accept-privacy"), allowShellOwner = false) ||
            markerMatches(File("/data/local/tmp/pico-auto-accept-privacy"), allowShellOwner = true)

    private fun markerMatches(file: File, allowShellOwner: Boolean): Boolean = try {
        val info = Os.lstat(file.path)
        val trustedUid = info.st_uid == 0 || (allowShellOwner && info.st_uid == 2000)
        val safePerms = (info.st_mode and (OsConstants.S_IWGRP or OsConstants.S_IWOTH)) == 0
        if (!trustedUid || !OsConstants.S_ISREG(info.st_mode) || !safePerms || info.st_size !in 1..MAX_MARKER_BYTES) {
            false
        } else {
            val buf = ByteArray(MAX_MARKER_BYTES)
            val count = FileInputStream(file).use { it.read(buf) }
            count > 0 && String(buf, 0, count, Charsets.UTF_8).trim() == MARKER_CONTENT
        }
    } catch (_: Throwable) {
        false
    }
}

package pico.onebot.core

import android.system.Os
import android.system.OsConstants

object DeploymentAutomation {
    private const val MARKER_CONTENT = "pico-auto-accept-privacy-v1"
    private const val MAX_MARKER_BYTES = 64

    val autoAcceptPrivacy: Boolean
        get() = markerMatches("/system/etc/pico-auto-accept-privacy", allowShellOwner = false) ||
            markerMatches("/data/local/tmp/pico-auto-accept-privacy", allowShellOwner = true)

    /** 使用 O_NOFOLLOW 打开后基于同一 fd 做 fstat + 有界读取,消除 lstat->open TOCTOU。 */
    private fun markerMatches(path: String, allowShellOwner: Boolean): Boolean {
        val fd = try {
            Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        } catch (_: Throwable) {
            return false
        }
        return try {
            val info = Os.fstat(fd)
            val trustedUid = info.st_uid == 0 || (allowShellOwner && info.st_uid == 2000)
            val safePerms = (info.st_mode and (OsConstants.S_IWGRP or OsConstants.S_IWOTH)) == 0
            if (!trustedUid || !OsConstants.S_ISREG(info.st_mode) || !safePerms || info.st_size !in 1..MAX_MARKER_BYTES) {
                false
            } else {
                val buf = ByteArray(MAX_MARKER_BYTES + 1)
                var off = 0
                while (off < buf.size) {
                    val n = Os.read(fd, buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                off in 1..MAX_MARKER_BYTES && String(buf, 0, off, Charsets.UTF_8).trim() == MARKER_CONTENT
            }
        } catch (_: Throwable) {
            false
        } finally {
            try {
                Os.close(fd)
            } catch (_: Throwable) {
            }
        }
    }
}

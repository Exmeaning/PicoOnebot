package pico.onebot.core

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

object ContainerRestart {
    private const val COOLDOWN_NS = 30_000_000_000L
    private const val MAX_MARKER_BYTES = 64
    private const val MARKER_PATH = "/data/local/tmp/pico-qq-restart.ready"
    private const val CONTAINER_PATH = "/system/etc/pico-container"
    private var hasLastRequest = false
    private var lastRequestAtNs = 0L

    fun available(): Boolean =
        readTrustedMarker(CONTAINER_PATH, expectedContent = "pico-container-v1", maxAgeSec = null) &&
            readTrustedMarker(MARKER_PATH, expectedContent = "restart-qq-v1", maxAgeSec = 20L)

    /** 使用 O_NOFOLLOW + O_NONBLOCK 打开后基于同一 fd 做 fstat + 有界读取,消除 lstat->open TOCTOU 与 FIFO 阻塞。 */
    private fun readTrustedMarker(path: String, expectedContent: String, maxAgeSec: Long?): Boolean {
        val fd = try {
            Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        } catch (_: Throwable) {
            return false
        }
        return try {
            val info = Os.fstat(fd)
            val safePerms = (info.st_mode and (OsConstants.S_IWGRP or OsConstants.S_IWOTH)) == 0
            if (info.st_uid != 0 || !OsConstants.S_ISREG(info.st_mode) || !safePerms || info.st_size !in 1..MAX_MARKER_BYTES) {
                return false
            }
            if (maxAgeSec != null) {
                val age = System.currentTimeMillis() / 1000 - info.st_mtime
                if (age !in 0..maxAgeSec) return false
            }
            val buf = ByteArray(MAX_MARKER_BYTES + 1)
            var off = 0
            while (off < buf.size) {
                val n = Os.read(fd, buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            off in 1..MAX_MARKER_BYTES && String(buf, 0, off, Charsets.UTF_8).trim() == expectedContent
        } catch (_: Throwable) {
            false
        } finally {
            try {
                Os.close(fd)
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    fun request(): Boolean {
        check(available()) { "仅支持已启用重启守护服务的 PicoOnebot 容器" }
        val nowNs = System.nanoTime()
        val nowWallSec = System.currentTimeMillis() / 1000
        val request = File(Config.dataDir, "restart-qq.request")
        if (request.exists() || (hasLastRequest && (nowNs - lastRequestAtNs).coerceAtLeast(0L) < COOLDOWN_NS)) {
            return false
        }
        Config.dataDir.mkdirs()
        val temporary = File(Config.dataDir, "restart-qq.request.tmp")
        try {
            FileOutputStream(temporary).use { fos ->
                fos.write(nowWallSec.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            check(temporary.renameTo(request)) { "无法提交 QQ 重启请求" }
            hasLastRequest = true
            lastRequestAtNs = nowNs
        } finally {
            temporary.delete()
        }
        return true
    }
}

package pico.onebot.core

import android.system.Os
import android.system.OsConstants
import java.io.File

object ContainerRestart {
    private val marker = File("/data/local/tmp/pico-qq-restart.ready")
    private val container = File("/system/etc/pico-container")
    private var lastRequestAt = 0L

    fun available(): Boolean = try {
        val info = Os.lstat(marker.path)
        val age = System.currentTimeMillis() / 1000 - info.st_mtime
        container.readText().trim() == "pico-container-v1" &&
            info.st_uid == 0 && OsConstants.S_ISREG(info.st_mode) &&
            info.st_mode and (OsConstants.S_IWGRP or OsConstants.S_IWOTH) == 0 && age in 0..20 &&
            marker.readText().trim() == "restart-qq-v1"
    } catch (_: Throwable) {
        false
    }

    @Synchronized
    fun request(): Boolean {
        check(available()) { "仅支持已启用重启守护服务的 PicoOnebot 容器" }
        val now = System.currentTimeMillis()
        val request = File(Config.dataDir, "restart-qq.request")
        if (request.exists() || now - lastRequestAt < 30_000) return false
        val temporary = File(Config.dataDir, "restart-qq.request.tmp")
        try {
            temporary.writeText((now / 1000).toString())
            check(temporary.renameTo(request)) { "无法提交 QQ 重启请求" }
            lastRequestAt = now
        } finally {
            temporary.delete()
        }
        return true
    }
}

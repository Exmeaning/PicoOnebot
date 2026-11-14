package pico.onebot.kernel

import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * NT 内核用 `u_xxx` 字符串 uid,OneBot 用 QQ 号(uin)。三级来源:
 * ① 每条 MsgRecord 自带的 senderUid/senderUin 对(最廉价,持续喂);
 * ② `IKernelProfileService.getUinByUid` / `getUidByUin`(同步 HashMap);
 * ③ 群成员/好友列表批量灌(后续接)。
 * 双向缓存 + 落盘,冷启动不空窗。
 */
object UinUidStore {

    private const val NEGATIVE_TTL_MS = 30_000L
    private const val NEGATIVE_CAP = 2000

    private val uidToUin = ConcurrentHashMap<String, Long>()
    private val uinToUid = ConcurrentHashMap<Long, String>()
    private val negUidMiss = ConcurrentHashMap<String, Long>()
    private val negUinMiss = ConcurrentHashMap<Long, Long>()

    private val hits = AtomicLong()
    private val misses = AtomicLong()

    private var file: File? = null
    private val dirty = AtomicInteger()
    private val saveScheduled = AtomicBoolean(false)
    private val saveLock = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pico-uinuid-io").apply { isDaemon = true }
    }

    fun load() {
        val f = File(Config.dataDir, "uinuid.json")
        file = f
        if (!f.isFile) return
        try {
            val o = JSONObject(f.readText())
            val keys = o.keys()
            while (keys.hasNext()) {
                val uid = keys.next()
                val uin = o.optLong(uid, 0)
                if (uin > 0) feed(uid, uin, persist = false)
            }
            PicoLog.i("uid/uin cache loaded: " + uidToUin.size)
        } catch (t: Throwable) {
            PicoLog.w("uid/uin cache load failed", t)
        }
    }

    fun feed(uid: String?, uin: Long, persist: Boolean = true) {
        if (uid.isNullOrEmpty() || uin <= 0) return
        negUidMiss.remove(uid)
        negUinMiss.remove(uin)
        val known = uidToUin.put(uid, uin)
        uinToUid[uin] = uid
        if (persist && known == null && dirty.incrementAndGet() >= 50) {
            dirty.set(0)
            saveAsync()
        }
    }

    /** uid → uin,缓存未命中时问内核。 */
    fun uin(uid: String?): Long {
        if (uid.isNullOrEmpty()) return 0
        uidToUin[uid]?.let {
            hits.incrementAndGet()
            return it
        }
        val now = System.currentTimeMillis()
        negUidMiss[uid]?.let { failedAt ->
            if (now - failedAt < NEGATIVE_TTL_MS) {
                misses.incrementAndGet()
                return 0
            }
            negUidMiss.remove(uid, failedAt)
        }
        misses.incrementAndGet()
        val svc = KernelGate.profileService() ?: return 0
        return try {
            val map = svc.getUinByUid("", arrayListOf(uid))
            val uin = map?.get(uid) ?: 0L
            if (uin > 0) {
                feed(uid, uin)
            } else {
                recordUidMiss(uid, now)
            }
            uin
        } catch (t: Throwable) {
            recordUidMiss(uid, now)
            PicoLog.d("getUinByUid failed: " + t.message)
            0
        }
    }

    /** uin → uid,私聊发消息必须先拿到 uid。 */
    fun uid(uin: Long): String {
        if (uin <= 0) return ""
        uinToUid[uin]?.let {
            hits.incrementAndGet()
            return it
        }
        val now = System.currentTimeMillis()
        negUinMiss[uin]?.let { failedAt ->
            if (now - failedAt < NEGATIVE_TTL_MS) {
                misses.incrementAndGet()
                return ""
            }
            negUinMiss.remove(uin, failedAt)
        }
        misses.incrementAndGet()
        val svc = KernelGate.profileService() ?: return ""
        return try {
            val map = svc.getUidByUin("", arrayListOf(uin))
            val uid = map?.get(uin) ?: ""
            if (uid.isNotEmpty()) {
                feed(uid, uin)
            } else {
                recordUinMiss(uin, now)
            }
            uid
        } catch (t: Throwable) {
            recordUinMiss(uin, now)
            PicoLog.d("getUidByUin failed: " + t.message)
            ""
        }
    }

    private fun recordUidMiss(uid: String, now: Long) {
        if (negUidMiss.size >= NEGATIVE_CAP) negUidMiss.clear()
        negUidMiss[uid] = now
    }

    private fun recordUinMiss(uin: Long, now: Long) {
        if (negUinMiss.size >= NEGATIVE_CAP) negUinMiss.clear()
        negUinMiss[uin] = now
    }

    private fun saveAsync() {
        if (!saveScheduled.compareAndSet(false, true)) return
        ioExecutor.execute {
            try {
                save()
            } finally {
                saveScheduled.set(false)
            }
        }
    }

    fun stats(): JSONObject = JSONObject()
        .put("size", uidToUin.size)
        .put("hits", hits.get())
        .put("misses", misses.get())

    fun save() {
        val f = file ?: return
        dirty.set(0)
        synchronized(saveLock) {
            try {
                val o = JSONObject()
                for ((uid, uin) in uidToUin) o.put(uid, uin)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(o.toString())
                if (!tmp.renameTo(f)) {
                    f.writeText(o.toString())
                    tmp.delete()
                }
            } catch (t: Throwable) {
                PicoLog.w("uid/uin cache save failed", t)
            }
        }
    }
}

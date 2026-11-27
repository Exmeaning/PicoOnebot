package pico.onebot.kernel

import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import java.io.File
import java.io.FileOutputStream
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

    private const val MAX_ENTRIES = 50_000
    private const val NEGATIVE_TTL_NS = 30_000_000_000L
    private const val NEGATIVE_CAP = 2000

    private val mapLock = Any()
    private val uinToUid = HashMap<Long, String>()
    private val uidToUin = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            if (size <= MAX_ENTRIES) return false
            if (eldest != null && uinToUid[eldest.value] == eldest.key) {
                uinToUid.remove(eldest.value)
            }
            return true
        }
    }
    private val negLock = Any()
    private val negUidMiss = ConcurrentHashMap<String, Long>()
    private val negUinMiss = ConcurrentHashMap<Long, Long>()

    private val hits = AtomicLong()
    private val misses = AtomicLong()

    @Volatile
    private var file: File? = null
    private val dirty = AtomicInteger()
    private val needsSave = AtomicBoolean(false)
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
            PicoLog.i("uid/uin cache loaded: " + synchronized(mapLock) { uidToUin.size })
        } catch (t: Throwable) {
            PicoLog.w("uid/uin cache load failed", t)
        }
    }

    fun feed(uid: String?, uin: Long, persist: Boolean = true) {
        if (uid.isNullOrEmpty() || uin <= 0) return
        negUidMiss.remove(uid)
        negUinMiss.remove(uin)
        val changed = synchronized(mapLock) {
            val oldUin = uidToUin[uid]
            if (oldUin != null && oldUin != uin && uinToUid[oldUin] == uid) {
                uinToUid.remove(oldUin)
            }
            val oldUid = uinToUid[uin]
            if (oldUid != null && oldUid != uid && uidToUin[oldUid] == uin) {
                uidToUin.remove(oldUid)
            }
            uidToUin[uid] = uin
            uinToUid[uin] = uid
            oldUin != uin
        }
        if (persist && changed && dirty.incrementAndGet() >= 50) {
            dirty.set(0)
            needsSave.set(true)
            saveAsync()
        }
    }

    /** 切号/会话失效时清空 30s 负缓存，避免旧账号的查无结果阻断新账号回查。 */
    fun reset() {
        synchronized(negLock) {
            negUidMiss.clear()
            negUinMiss.clear()
        }
        PicoLog.d("uid/uin negative cache reset")
    }

    /** uid → uin,缓存未命中时问内核。 */
    fun uin(uid: String?): Long {
        if (uid.isNullOrEmpty()) return 0
        synchronized(mapLock) { uidToUin[uid] }?.let {
            hits.incrementAndGet()
            return it
        }
        val nowNs = System.nanoTime()
        negUidMiss[uid]?.let { failedAtNs ->
            if ((nowNs - failedAtNs).coerceAtLeast(0L) < NEGATIVE_TTL_NS) {
                misses.incrementAndGet()
                return 0
            }
            negUidMiss.remove(uid, failedAtNs)
        }
        misses.incrementAndGet()
        val epoch = KernelGate.currentEpoch()
        val svc = KernelGate.profileService() ?: return 0
        return try {
            val map = svc.getUinByUid("", arrayListOf(uid))
            val uin = map?.get(uid) ?: 0L
            if (uin > 0) {
                feed(uid, uin)
            } else {
                recordUidMiss(uid, System.nanoTime(), epoch)
            }
            uin
        } catch (t: Throwable) {
            recordUidMiss(uid, System.nanoTime(), epoch)
            PicoLog.d("getUinByUid failed: " + t.message)
            0
        }
    }

    /** uin → uid,私聊发消息必须先拿到 uid。 */
    fun uid(uin: Long): String {
        if (uin <= 0) return ""
        synchronized(mapLock) {
            uinToUid[uin]?.also { u ->
                // 同步触碰 LRU 访问序
                uidToUin[u]
            }
        }?.let {
            hits.incrementAndGet()
            return it
        }
        val nowNs = System.nanoTime()
        negUinMiss[uin]?.let { failedAtNs ->
            if ((nowNs - failedAtNs).coerceAtLeast(0L) < NEGATIVE_TTL_NS) {
                misses.incrementAndGet()
                return ""
            }
            negUinMiss.remove(uin, failedAtNs)
        }
        misses.incrementAndGet()
        val epoch = KernelGate.currentEpoch()
        val svc = KernelGate.profileService() ?: return ""
        return try {
            val map = svc.getUidByUin("", arrayListOf(uin))
            val uid = map?.get(uin) ?: ""
            if (uid.isNotEmpty()) {
                feed(uid, uin)
            } else {
                recordUinMiss(uin, System.nanoTime(), epoch)
            }
            uid
        } catch (t: Throwable) {
            recordUinMiss(uin, System.nanoTime(), epoch)
            PicoLog.d("getUidByUin failed: " + t.message)
            ""
        }
    }

    private fun recordUidMiss(uid: String, nowNs: Long, epoch: Long) {
        synchronized(negLock) {
            if (!KernelGate.isEpochCurrent(epoch)) return
            if (negUidMiss.size >= NEGATIVE_CAP) negUidMiss.clear()
            negUidMiss[uid] = nowNs
        }
    }

    private fun recordUinMiss(uin: Long, nowNs: Long, epoch: Long) {
        synchronized(negLock) {
            if (!KernelGate.isEpochCurrent(epoch)) return
            if (negUinMiss.size >= NEGATIVE_CAP) negUinMiss.clear()
            negUinMiss[uin] = nowNs
        }
    }

    private fun saveAsync() {
        if (!saveScheduled.compareAndSet(false, true)) return
        ioExecutor.execute {
            try {
                while (true) {
                    needsSave.set(false)
                    save()
                    if (!needsSave.get()) break
                }
            } finally {
                saveScheduled.set(false)
                if (needsSave.get()) saveAsync()
            }
        }
    }

    fun stats(): JSONObject = JSONObject()
        .put("size", synchronized(mapLock) { uidToUin.size })
        .put("hits", hits.get())
        .put("misses", misses.get())

    fun save() {
        val f = file ?: return
        dirty.set(0)
        synchronized(saveLock) {
            val tmp = File(f.parentFile, "${f.name}.${System.nanoTime()}.tmp")
            try {
                val snapshot = synchronized(mapLock) { HashMap(uidToUin) }
                val o = JSONObject()
                for ((uid, uin) in snapshot) o.put(uid, uin)
                f.parentFile?.mkdirs()
                FileOutputStream(tmp).use { fos ->
                    fos.write(o.toString().toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
                if (!tmp.renameTo(f)) {
                    PicoLog.w("uid/uin cache atomic rename failed: " + f.absolutePath)
                }
            } catch (t: Throwable) {
                PicoLog.w("uid/uin cache save failed", t)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}

package pico.onebot.kernel

import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * NT 内核用 `u_xxx` 字符串 uid,OneBot 用 QQ 号(uin)。三级来源:
 * ① 每条 MsgRecord 自带的 senderUid/senderUin 对(最廉价,持续喂);
 * ② `IKernelProfileService.getUinByUid` / `getUidByUin`(同步 HashMap);
 * ③ 群成员/好友列表批量灌(后续接)。
 * 双向缓存 + 落盘,冷启动不空窗。
 */
object UinUidStore {

    private val uidToUin = ConcurrentHashMap<String, Long>()
    private val uinToUid = ConcurrentHashMap<Long, String>()

    private val hits = AtomicLong()
    private val misses = AtomicLong()

    private var file: File? = null
    private var dirty = 0

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
        val known = uidToUin.put(uid, uin)
        uinToUid[uin] = uid
        if (persist && known == null && ++dirty >= 50) save()
    }

    /** uid → uin,缓存未命中时问内核。 */
    fun uin(uid: String?): Long {
        if (uid.isNullOrEmpty()) return 0
        uidToUin[uid]?.let {
            hits.incrementAndGet()
            return it
        }
        misses.incrementAndGet()
        val svc = KernelGate.profileService() ?: return 0
        return try {
            val map = svc.getUinByUid("", arrayListOf(uid))
            val uin = map?.get(uid) ?: 0L
            if (uin > 0) feed(uid, uin)
            uin
        } catch (t: Throwable) {
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
        misses.incrementAndGet()
        val svc = KernelGate.profileService() ?: return ""
        return try {
            val map = svc.getUidByUin("", arrayListOf(uin))
            val uid = map?.get(uin) ?: ""
            if (uid.isNotEmpty()) feed(uid, uin)
            uid
        } catch (t: Throwable) {
            PicoLog.d("getUidByUin failed: " + t.message)
            ""
        }
    }

    fun stats(): JSONObject = JSONObject()
        .put("size", uidToUin.size)
        .put("hits", hits.get())
        .put("misses", misses.get())

    @Synchronized
    fun save() {
        val f = file ?: return
        dirty = 0
        try {
            val o = JSONObject()
            for ((uid, uin) in uidToUin) o.put(uid, uin)
            f.writeText(o.toString())
        } catch (t: Throwable) {
            PicoLog.w("uid/uin cache save failed", t)
        }
    }
}

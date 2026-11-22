package pico.onebot.kernel

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * uid → 显示名(备注优先于昵称)。
 *
 * 存在的理由:NT 的**私聊** MsgRecord 既不填 `sendNickName` 也不填 `peerName`,
 * 直接用就会让所有私聊事件带着空昵称出去(群聊记录反而是带 `sendMemberName` 的)。
 *
 * `getBuddyRemark` / `getBuddyNick` 是**同步批量读本地缓存**的(直接返回 HashMap,
 * 不是回调),所以可以在消息管道线程里直接调;再加一层进程内缓存避免每条消息都过 JNI。
 * 查不到时**不缓存空值** —— 好友列表是慢慢热起来的,缓存空值会让它永远查不到。
 */
object NickStore {

    private const val CAP = 2000
    private const val NEGATIVE_TTL_MS = 30_000L
    private const val NEGATIVE_CAP = 2000

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CAP
    }
    private val negCache = object : LinkedHashMap<String, Long>(128, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > NEGATIVE_CAP
    }
    private val hits = AtomicLong()
    private val misses = AtomicLong()

    fun of(uid: String?): String {
        if (uid.isNullOrEmpty()) return ""
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[uid]?.let {
                hits.incrementAndGet()
                return it
            }
            negCache[uid]?.let { failedAt ->
                if (now - failedAt < NEGATIVE_TTL_MS) {
                    misses.incrementAndGet()
                    return ""
                }
                negCache.remove(uid)
            }
        }
        val found = lookup(uid)
        if (found.isEmpty()) {
            misses.incrementAndGet()
            synchronized(cache) { negCache[uid] = System.currentTimeMillis() }
            return ""
        }
        synchronized(cache) {
            negCache.remove(uid)
            cache[uid] = found
        }
        return found
    }

    /**
     * 权威来源写入(好友列表、备注/昵称变更回调、群成员信息):**覆盖旧值**。
     *
     * 早期实现是"已有值就 return",于是 `onNickUpdated` / `onBuddyRemarkUpdated`
     * 这类明确的名字变更被静默丢弃,用户在 QQ 里改名后机器人一直叫旧名字。
     */
    fun feed(uid: String?, name: String?) {
        if (uid.isNullOrEmpty() || name.isNullOrEmpty()) return
        synchronized(cache) {
            negCache.remove(uid)
            cache[uid] = name
        }
    }

    /**
     * 弱来源写入(逐条消息里的 sendMemberName 等):只在没值时补齐。
     *
     * 群名片会和好友备注打架 —— 一条群消息里的卡片名不该覆盖备注。真正的
     * 变更交给 [feed] 那条路径。
     */
    fun feedIfAbsent(uid: String?, name: String?) {
        if (uid.isNullOrEmpty() || name.isNullOrEmpty()) return
        synchronized(cache) {
            negCache.remove(uid)
            if (cache.containsKey(uid)) return
            cache[uid] = name
        }
    }

    private fun lookup(uid: String): String = try {
        val buddy = KernelGate.buddyService()
        val list = arrayListOf(uid)
        val remark = buddy?.getBuddyRemark(list)?.get(uid)
        if (!remark.isNullOrEmpty()) remark else buddy?.getBuddyNick(list)?.get(uid) ?: ""
    } catch (t: Throwable) {
        ""
    }

    fun stats(): JSONObject = JSONObject()
        .put("size", synchronized(cache) { cache.size })
        .put("hits", hits.get())
        .put("misses", misses.get())
}

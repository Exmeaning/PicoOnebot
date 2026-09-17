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

    private val cache = HashMap<String, String>()
    private val hits = AtomicLong()
    private val misses = AtomicLong()

    fun of(uid: String?): String {
        if (uid.isNullOrEmpty()) return ""
        synchronized(cache) {
            cache[uid]?.let {
                hits.incrementAndGet()
                return it
            }
        }
        val found = lookup(uid)
        if (found.isEmpty()) {
            misses.incrementAndGet()
            return ""
        }
        synchronized(cache) {
            if (cache.size >= CAP) cache.clear()
            cache[uid] = found
        }
        return found
    }

    /** 喂进已知的名字(例如群消息里内核已经给了 sendMemberName),省一次内核查询。 */
    fun feed(uid: String?, name: String?) {
        if (uid.isNullOrEmpty() || name.isNullOrEmpty()) return
        synchronized(cache) {
            if (cache.containsKey(uid)) return
            if (cache.size >= CAP) cache.clear()
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

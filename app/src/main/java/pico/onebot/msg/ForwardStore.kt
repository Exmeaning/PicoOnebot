package pico.onebot.msg

/**
 * 合并转发 `resId` → 承载它的那条消息(会话 + msgId)。
 *
 * 内核拉转发内容的接口是 `getMultiMsg(Contact, rootMsgId, parentMsgId, cb)` ——
 * 它要的是**会话和 msgId**,而 OneBot 的 `get_forward_msg` 标准参数是 `resId` 字符串。
 * 两者对不上,所以在解码转发元素时把这层对应关系记下来,否则收到 resId 也没法回查。
 */
object ForwardStore {

    private const val CAP = 512

    class Ref(val chatType: Int, val peerUid: String, val msgId: Long)

    private val map = LinkedHashMap<String, Ref>(64, 0.75f, false)

    @Synchronized
    fun put(resId: String?, chatType: Int, peerUid: String?, msgId: Long) {
        if (resId.isNullOrEmpty() || msgId == 0L) return
        map[resId] = Ref(chatType, peerUid ?: "", msgId)
        while (map.size > CAP) {
            val it = map.entries.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    @Synchronized
    fun locate(resId: String): Ref? = map[resId]

    @Synchronized
    fun size(): Int = map.size
}

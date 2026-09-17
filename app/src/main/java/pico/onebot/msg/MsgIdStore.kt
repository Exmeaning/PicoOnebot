package pico.onebot.msg

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import java.io.File

/**
 * OneBot 的 `message_id` 是 int32,NT 的消息标识是 `msgId`(u64)+ `msgSeq` + 会话。
 * 这里维护双向映射并落盘,重启后 `delete_msg` / `get_msg` / reply 仍能回溯。
 */
object MsgIdStore {

    class Entry(
        val messageId: Int,
        val msgId: Long,
        val msgSeq: Long,
        val chatType: Int,
        val peerUid: String,
        val senderUin: Long,
        val time: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("message_id", messageId)
            .put("msg_id", msgId)
            .put("msg_seq", msgSeq)
            .put("chat_type", chatType)
            .put("peer_uid", peerUid)
            .put("sender_uin", senderUin)
            .put("time", time)
    }

    private const val CAP = 10000

    private val byMessageId = LinkedHashMap<Int, Entry>(256, 0.75f, false)
    private val byMsgId = HashMap<Long, Entry>()

    /**
     * (chatType, peerUid, msgSeq) → Entry。回复引用只稳定给得出 seq,
     * msgId 在跨设备同步的记录里未必对得上,所以需要第二条查找路径。
     */
    private val bySeq = HashMap<String, Entry>()

    private fun seqKey(chatType: Int, peerUid: String, msgSeq: Long): String =
        chatType.toString() + ":" + peerUid + ":" + msgSeq

    private var counter = 1
    private var dirty = 0
    private var file: File? = null

    fun load() {
        val f = File(Config.dataDir, "msgid.json")
        file = f
        if (!f.isFile) return
        try {
            val root = JSONObject(f.readText())
            counter = root.optInt("counter", 1)
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val e = Entry(
                    o.optInt("message_id"),
                    o.optLong("msg_id"),
                    o.optLong("msg_seq"),
                    o.optInt("chat_type"),
                    o.optString("peer_uid"),
                    o.optLong("sender_uin"),
                    o.optLong("time")
                )
                put(e)
            }
            PicoLog.i("msgid store loaded: " + byMessageId.size + " entries")
        } catch (t: Throwable) {
            PicoLog.w("msgid store load failed", t)
        }
    }

    @Synchronized
    fun assign(msgId: Long, msgSeq: Long, chatType: Int, peerUid: String, senderUin: Long, time: Long): Entry {
        byMsgId[msgId]?.let { return it }
        val e = Entry(nextId(), msgId, msgSeq, chatType, peerUid, senderUin, time)
        put(e)
        if (++dirty >= 50) save()
        return e
    }

    @Synchronized
    fun byMessageId(messageId: Int): Entry? = byMessageId[messageId]

    @Synchronized
    fun byMsgId(msgId: Long): Entry? = byMsgId[msgId]

    @Synchronized
    fun bySeq(chatType: Int, peerUid: String, msgSeq: Long): Entry? =
        if (msgSeq == 0L) null else bySeq[seqKey(chatType, peerUid, msgSeq)]

    @Synchronized
    fun size(): Int = byMessageId.size

    private fun put(e: Entry) {
        byMessageId[e.messageId] = e
        byMsgId[e.msgId] = e
        if (e.msgSeq != 0L) bySeq[seqKey(e.chatType, e.peerUid, e.msgSeq)] = e
        if (e.messageId >= counter) counter = e.messageId + 1
        while (byMessageId.size > CAP) {
            val it = byMessageId.entries.iterator()
            if (!it.hasNext()) break
            val oldest = it.next().value
            it.remove()
            byMsgId.remove(oldest.msgId)
            if (oldest.msgSeq != 0L) bySeq.remove(seqKey(oldest.chatType, oldest.peerUid, oldest.msgSeq))
        }
    }

    private fun nextId(): Int {
        if (counter == Int.MAX_VALUE) counter = 1
        return counter++
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        dirty = 0
        try {
            val arr = JSONArray()
            for (e in byMessageId.values) arr.put(e.toJson())
            f.writeText(JSONObject().put("counter", counter).put("entries", arr).toString())
        } catch (t: Throwable) {
            PicoLog.w("msgid store save failed", t)
        }
    }
}

package pico.onebot.msg

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

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
    private var revision = 0L
    private var lastWrittenRev = -1L
    private var saveRunning = false
    private var resaveNeeded = false
    @Volatile
    private var file: File? = null
    private val saveLock = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pico-msgid-io").apply { isDaemon = true }
    }

    @Synchronized
    fun load() {
        val f = File(Config.dataDir, "msgid.json")
        file = f
        if (!f.isFile) return
        try {
            val root = JSONObject(f.readText())
            counter = root.optInt("counter", 1).coerceAtLeast(1)
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val messageId = o.optInt("message_id", 0)
                if (messageId <= 0) continue
                val peerUid = if (o.isNull("peer_uid")) "" else o.optString("peer_uid", "")
                val e = Entry(
                    messageId,
                    o.optLong("msg_id"),
                    o.optLong("msg_seq"),
                    o.optInt("chat_type"),
                    peerUid,
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
        if (msgId != 0L) {
            byMsgId[msgId]?.let { existing ->
                val newSeq = if (existing.msgSeq == 0L && msgSeq != 0L) msgSeq else existing.msgSeq
                val newChatType = if (existing.chatType == 0 && chatType != 0) chatType else existing.chatType
                val newPeerUid = if (existing.peerUid.isEmpty() && peerUid.isNotEmpty()) peerUid else existing.peerUid
                val newSenderUin = if (existing.senderUin == 0L && senderUin != 0L) senderUin else existing.senderUin
                val newTime = if (existing.time == 0L && time != 0L) time else existing.time
                if (newSeq == existing.msgSeq &&
                    newChatType == existing.chatType &&
                    newPeerUid == existing.peerUid &&
                    newSenderUin == existing.senderUin &&
                    newTime == existing.time
                ) {
                    return existing
                }
                val updated = Entry(
                    existing.messageId,
                    existing.msgId,
                    newSeq,
                    newChatType,
                    newPeerUid,
                    newSenderUin,
                    newTime
                )
                byMessageId[updated.messageId] = updated
                byMsgId[updated.msgId] = updated
                if (existing.msgSeq != 0L) {
                    val oldSk = seqKey(existing.chatType, existing.peerUid, existing.msgSeq)
                    if (bySeq[oldSk] === existing) bySeq.remove(oldSk)
                }
                if (updated.msgSeq != 0L) {
                    bySeq[seqKey(updated.chatType, updated.peerUid, updated.msgSeq)] = updated
                }
                markDirtyLocked()
                return updated
            }
        }
        val e = Entry(nextId(), msgId, msgSeq, chatType, peerUid, senderUin, time)
        put(e)
        markDirtyLocked()
        return e
    }

    @Synchronized
    fun byMessageId(messageId: Int): Entry? = byMessageId[messageId]

    @Synchronized
    fun byMsgId(msgId: Long): Entry? = if (msgId == 0L) null else byMsgId[msgId]

    @Synchronized
    fun bySeq(chatType: Int, peerUid: String, msgSeq: Long): Entry? =
        if (msgSeq == 0L) null else bySeq[seqKey(chatType, peerUid, msgSeq)]

    @Synchronized
    fun size(): Int = byMessageId.size

    private fun put(e: Entry) {
        byMessageId[e.messageId] = e
        if (e.msgId != 0L) byMsgId[e.msgId] = e
        if (e.msgSeq != 0L) bySeq[seqKey(e.chatType, e.peerUid, e.msgSeq)] = e
        if (e.messageId >= counter) counter = if (e.messageId == Int.MAX_VALUE) 1 else e.messageId + 1
        while (byMessageId.size > CAP) {
            val it = byMessageId.entries.iterator()
            if (!it.hasNext()) break
            val oldest = it.next().value
            it.remove()
            if (oldest.msgId != 0L && byMsgId[oldest.msgId] === oldest) byMsgId.remove(oldest.msgId)
            if (oldest.msgSeq != 0L) {
                val sk = seqKey(oldest.chatType, oldest.peerUid, oldest.msgSeq)
                if (bySeq[sk] === oldest) bySeq.remove(sk)
            }
        }
    }

    private fun nextId(): Int {
        while (byMessageId.containsKey(counter) || counter <= 0) {
            counter = if (counter >= Int.MAX_VALUE) 1 else counter + 1
        }
        val id = counter
        counter = if (counter >= Int.MAX_VALUE) 1 else counter + 1
        return id
    }

    private fun markDirtyLocked() {
        revision++
        dirty++
        if (dirty >= 50) {
            resaveNeeded = true
            if (!saveRunning) {
                saveRunning = true
                ioExecutor.execute { drainSaves() }
            }
        }
    }

    private fun drainSaves() {
        var completedNormally = false
        try {
            while (true) {
                val snap: Triple<Long, Int, List<Entry>>? = synchronized(this) {
                    if (!resaveNeeded && dirty == 0) {
                        saveRunning = false
                        completedNormally = true
                        null
                    } else {
                        resaveNeeded = false
                        dirty = 0
                        Triple(revision, counter, ArrayList(byMessageId.values))
                    }
                }
                if (snap == null) return
                writeSnapshot(snap.first, snap.second, snap.third)
            }
        } finally {
            if (!completedNormally) {
                synchronized(this) { saveRunning = false }
            }
        }
    }

    fun save() {
        val snap = synchronized(this) {
            resaveNeeded = false
            dirty = 0
            Triple(revision, counter, ArrayList(byMessageId.values))
        }
        writeSnapshot(snap.first, snap.second, snap.third)
    }

    private fun writeSnapshot(rev: Long, curCounter: Int, entries: List<Entry>) {
        val f = file ?: return
        synchronized(saveLock) {
            if (rev < lastWrittenRev) return
            if (rev == lastWrittenRev && f.isFile) return
            var tmp: File? = null
            try {
                val arr = JSONArray()
                for (e in entries) arr.put(e.toJson())
                val payload = JSONObject().put("counter", curCounter).put("entries", arr).toString()
                val parent = f.parentFile
                if (parent != null && !parent.isDirectory) parent.mkdirs()
                val tempFile = File.createTempFile(f.name + ".", ".tmp", parent)
                tmp = tempFile
                FileOutputStream(tempFile).use { fos ->
                    fos.write(payload.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
                if (!tempFile.renameTo(f)) {
                    PicoLog.w("msgid store atomic rename failed: " + f.absolutePath)
                } else {
                    lastWrittenRev = rev
                }
            } catch (t: Throwable) {
                PicoLog.w("msgid store save failed", t)
            } finally {
                tmp?.delete()
            }
        }
    }
}

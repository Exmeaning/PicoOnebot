package pico.onebot.msg

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * OneBot 的 `message_id` 是 int32,NT 的消息标识是 `msgId`(u64)+ `msgSeq` + 会话。
 * 这里维护双向映射并按登录账号 (`selfUin`) 分文件落盘,重启后 `delete_msg` / `get_msg` / reply
 * 仍能回溯,同时避免多账号切换时串号污染。
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

    private class SaveSnap(
        val targetFile: File,
        val rev: Long,
        val curCounter: Int,
        val entries: List<Entry>
    )

    private const val CAP = 10000
    private const val LEGACY_FILE_NAME = "msgid.json"
    private const val LEGACY_MIGRATED_NAME = "msgid.json.migrated"

    private val byMessageId = LinkedHashMap<Int, Entry>(256, 0.75f, false)
    private val byMsgId = HashMap<Long, Entry>()

    /**
     * (chatType, peerUid, msgSeq) → Entry。回复引用只稳定给得出 seq,
     * msgId 在跨设备同步的记录里未必对得上,所以需要第二条查找路径。
     */
    private val bySeq = HashMap<String, Entry>()

    private fun seqKey(chatType: Int, peerUid: String, msgSeq: Long): String =
        chatType.toString() + ":" + peerUid + ":" + msgSeq

    private var activeUin = 0L
    private var counter = 1
    private var dirty = 0
    private var revision = 0L
    private var saveRunning = false
    private var resaveNeeded = false
    @Volatile
    private var file: File? = null
    private val saveLock = Any()
    private val lastWrittenRevByPath = HashMap<String, Long>()
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pico-msgid-io").apply { isDaemon = true }
    }

    private fun accountFile(uin: Long): File = File(Config.dataDir, "msgid-$uin.json")
    private fun legacyFile(): File = File(Config.dataDir, LEGACY_FILE_NAME)

    fun load() {
        val uin = KernelGate.selfUin()
        if (uin > 0L) {
            switchAccount(uin)
        }
    }

    /**
     * 切换或绑定当前登录账号的消息 ID 持久化命名空间 (`msgid-<uin>.json`)。
     * 若新账号文件尚未存在但存在旧版 `msgid.json`，执行一次性兼容加载并迁移，
     * 避免后续切换至其他账号时重复导入旧账号记录。
     */
    fun switchAccount(uin: Long, expectedEpoch: Long? = null) {
        if (uin <= 0L) return
        if (expectedEpoch != null && !KernelGate.isEpochCurrent(expectedEpoch)) return
        val prevSnap: SaveSnap? = synchronized(this) {
            if (expectedEpoch != null && !KernelGate.isEpochCurrent(expectedEpoch)) return
            if (activeUin == uin && file != null) return
            val snap = captureDirtySnapLocked()
            clearMemoryLocked()
            activeUin = 0L
            file = null
            snap
        }
        if (prevSnap != null) {
            writeSnapshot(prevSnap)
        }
        if (expectedEpoch != null && !KernelGate.isEpochCurrent(expectedEpoch)) return

        val acctFile = accountFile(uin)
        val legacy = legacyFile()
        val sourceFile = when {
            acctFile.isFile -> acctFile
            legacy.isFile -> legacy
            else -> null
        }
        val fromLegacy = (sourceFile != null && sourceFile == legacy)
        val parsed = sourceFile?.let { readEntriesFromFile(it) }

        val migratedSnap: SaveSnap? = synchronized(this) {
            if (expectedEpoch != null && !KernelGate.isEpochCurrent(expectedEpoch)) return
            if (activeUin == uin && file != null) return
            clearMemoryLocked()
            activeUin = uin
            file = acctFile
            if (parsed != null) {
                counter = parsed.first
                for (e in parsed.second) put(e)
            }
            revision++
            if (fromLegacy && byMessageId.isNotEmpty()) {
                SaveSnap(acctFile, revision, counter, ArrayList(byMessageId.values))
            } else {
                null
            }
        }

        if (sourceFile != null) {
            PicoLog.i(
                "msgid store loaded for uin=$uin: ${size()} entries" +
                    (if (fromLegacy) " (migrated from $LEGACY_FILE_NAME)" else "")
            )
        }

        if (fromLegacy) {
            val persisted = if (migratedSnap != null) writeSnapshot(migratedSnap) else true
            if (persisted) {
                val backup = File(Config.dataDir, LEGACY_MIGRATED_NAME)
                if (!legacy.renameTo(backup)) {
                    PicoLog.w("msgid legacy file rename failed: " + legacy.absolutePath)
                }
            }
        }
    }

    /** 切号/会话丢失时先将当前账号脏数据落盘，再清空内存映射，防止跨账号串号查询。 */
    fun onSessionLost() {
        val snap: SaveSnap? = synchronized(this) {
            val pending = captureDirtySnapLocked()
            clearMemoryLocked()
            activeUin = 0L
            file = null
            pending
        }
        if (snap != null) {
            writeSnapshot(snap)
        }
    }

    private fun clearMemoryLocked() {
        byMessageId.clear()
        byMsgId.clear()
        bySeq.clear()
        counter = 1
        dirty = 0
        resaveNeeded = false
    }

    private fun captureDirtySnapLocked(): SaveSnap? {
        val target = file ?: return null
        if (dirty <= 0 && !resaveNeeded) return null
        resaveNeeded = false
        dirty = 0
        return SaveSnap(target, revision, counter, ArrayList(byMessageId.values))
    }

    private fun readEntriesFromFile(f: File): Pair<Int, List<Entry>>? {
        if (!f.isFile) return null
        return try {
            val root = JSONObject(f.readText())
            val savedCounter = root.optInt("counter", 1).coerceAtLeast(1)
            val arr = root.optJSONArray("entries") ?: JSONArray()
            val list = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val messageId = o.optInt("message_id", 0)
                if (messageId <= 0) continue
                val peerUid = if (o.isNull("peer_uid")) "" else o.optString("peer_uid", "")
                list.add(
                    Entry(
                        messageId,
                        o.optLong("msg_id"),
                        o.optLong("msg_seq"),
                        o.optInt("chat_type"),
                        peerUid,
                        o.optLong("sender_uin"),
                        o.optLong("time")
                    )
                )
            }
            savedCounter to list
        } catch (t: Throwable) {
            PicoLog.w("msgid store load failed (${f.name})", t)
            null
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
        if (dirty >= 50 && file != null) {
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
                val snap: SaveSnap? = synchronized(this) {
                    val target = file
                    if (target == null || (!resaveNeeded && dirty == 0)) {
                        saveRunning = false
                        completedNormally = true
                        null
                    } else {
                        resaveNeeded = false
                        dirty = 0
                        SaveSnap(target, revision, counter, ArrayList(byMessageId.values))
                    }
                }
                if (snap == null) return
                writeSnapshot(snap)
            }
        } finally {
            if (!completedNormally) {
                synchronized(this) { saveRunning = false }
            }
        }
    }

    fun save() {
        val snap = synchronized(this) {
            val target = file ?: return
            resaveNeeded = false
            dirty = 0
            SaveSnap(target, revision, counter, ArrayList(byMessageId.values))
        }
        writeSnapshot(snap)
    }

    private fun writeSnapshot(snap: SaveSnap): Boolean {
        val f = snap.targetFile
        val path = f.absolutePath
        synchronized(saveLock) {
            val prevRev = lastWrittenRevByPath[path] ?: -1L
            if (snap.rev < prevRev) return true
            if (snap.rev == prevRev && f.isFile) return true
            var tmp: File? = null
            try {
                val arr = JSONArray()
                for (e in snap.entries) arr.put(e.toJson())
                val payload = JSONObject().put("counter", snap.curCounter).put("entries", arr).toString()
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
                    return false
                }
                lastWrittenRevByPath[path] = snap.rev
                return true
            } catch (t: Throwable) {
                PicoLog.w("msgid store save failed", t)
                return false
            } finally {
                tmp?.delete()
            }
        }
    }
}

package pico.onebot.kernel

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.event.EventBus
import pico.onebot.msg.MsgCodec
import pico.onebot.msg.MsgIdStore
import pico.onebot.proto.Ob
import java.util.LinkedHashSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 收消息的三道闸门(见 docs/PicoOneBot-架构与流程.md §3.2):
 *  1) 去重闸 —— 同一条消息会从 onRecvMsg / onMsgInfoListAdd 各来一次,离线补拉还会再来;
 *  2) 自发闸 —— 自己发的走 message_sent,绝不再当 message 投一次(否则机器人自问自答);
 *  3) 顺序闸 —— 回调只入队,单线程出队转换,内核回调线程绝不被 IO 拖住。
 */
object MsgPipeline {

    private const val DEDUP_CAP = 4096

    private val seen = LinkedHashSet<Long>()
    private val recalled = LinkedHashSet<Long>()


    private lateinit var queue: ArrayBlockingQueue<Job>

    private val enqueued = AtomicLong()
    private val droppedIn = AtomicLong()
    private val droppedJunk = AtomicLong()

    private val sendErrors = AtomicLong()
    private val noMsgId = AtomicLong()
    private val converted = AtomicLong()

    @Volatile
    private var running = false

    private class Job(val kind: Int, val records: List<MsgRecord>)

    private const val KIND_INCOMING = 0
    private const val KIND_UPDATE = 1

    fun start() {
        if (running) return
        queue = ArrayBlockingQueue(Config.eventQueueSize)
        running = true
        Thread({ pump() }, "pico-msgpipe").apply { isDaemon = true }.start()
    }

    // ---------------- 内核回调入口(必须极快返回) ----------------

    fun onIncoming(source: String, records: ArrayList<MsgRecord>) {
        if (records.isEmpty()) return
        for (r in records) SendTracker.onRecordSeen(r)
        offer(Job(KIND_INCOMING, ArrayList(records)))
    }

    fun onUpdate(records: ArrayList<MsgRecord>) {
        if (records.isEmpty()) return
        for (r in records) SendTracker.onRecordSeen(r)
        offer(Job(KIND_UPDATE, ArrayList(records)))
    }

    fun onSelfSent(record: MsgRecord) {
        SendTracker.onRecordSeen(record)
        offer(Job(KIND_INCOMING, listOf(record)))
    }

    /**
     * 发送失败的异步通知。除了让等待中的调用方立刻失败,还要**主动告诉上游** ——
     * 否则上游只看见一条 `message_sent`,永远不知道那条消息其实没发出去。
     */
    fun onSendError(msgId: Long, contact: Any?, code: Int, msg: String) {
        val c = contact as? com.tencent.qqnt.kernelpublic.nativeinterface.Contact
        val chatType = c?.chatType ?: 0
        val peerUid = c?.peerUid ?: ""
        sendErrors.incrementAndGet()
        PicoLog.w("send failed msgId=$msgId chat=$chatType peer=$peerUid code=$code $msg")
        SendTracker.onSendError(msgId, chatType, peerUid, code, msg)

        val entry = MsgIdStore.byMsgId(msgId)
        val event = Ob.event("notice")
            .put("notice_type", "pico_send_failed")
            .put("message_id", entry?.messageId ?: 0)
            .put("msg_id", msgId.toString())
            .put("error_code", code)
            .put("error_message", msg)
        if (chatType == 2) {
            event.put("group_id", peerUid.toLongOrNull() ?: 0L)
        } else {
            event.put("user_id", UinUidStore.uin(peerUid))
        }
        EventBus.post(event)
    }

    fun onSysMsg(payload: Any?) {
        // P2:onRecvSysMsg 给的是 protobuf 字节(ArrayList<Byte>),解出来才能产 request 事件
        val size = (payload as? java.util.ArrayList<*>)?.size ?: 0
        PicoLog.d("onRecvSysMsg bytes=$size (request 事件解析待 M5)")
    }

    private fun offer(job: Job) {
        if (!running) return
        enqueued.incrementAndGet()
        if (!queue.offer(job)) {
            queue.poll()
            droppedIn.incrementAndGet()
            queue.offer(job)
        }
    }

    // ---------------- 单线程转换 ----------------

    private fun pump() {
        while (running) {
            val job = try {
                queue.take()
            } catch (t: InterruptedException) {
                return
            }
            for (r in job.records) {
                try {
                    if (job.kind == KIND_INCOMING) handleIncoming(r) else handleUpdate(r)
                } catch (t: Throwable) {
                    PicoLog.w("msg convert failed msgId=" + r.msgId, t)
                }
            }
        }
    }

    private fun handleIncoming(rec: MsgRecord) {
        // 内核会为"发送失败 / 占位 / 已清空"的消息也回调 MsgRecord,elements 是空的。
        // 放它过去就会变成一条 user_id=0、message=[] 的垃圾事件,还白占一个 message_id。
        if ((rec.elements?.size ?: 0) == 0) {
            droppedJunk.incrementAndGet()
            PicoLog.d("drop empty record " + digest(rec))
            return
        }
        if (rec.msgId == 0L) noMsgId.incrementAndGet()
        if (!markSeen(rec.msgId)) return

        val isSelf = isSelfSent(rec)
        if (isSelf && !Config.reportSelfMessage) return
        PicoLog.d((if (isSelf) "self " else "recv ") + digest(rec))

        val body = MsgCodec.toMessageBody(rec)
        val event = Ob.event(if (isSelf) "message_sent" else "message")
        val keys = body.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k != "post_type") event.put(k, body.get(k))
        }
        if (isSelf) {
            event.put("post_type", "message_sent")
            // 私聊自发消息里 user_id 是**自己**,不补 target_id 的话"发给了谁"就彻底丢了
            // (群聊有 group_id 所以不受影响)。上游框架靠这个字段归会话。
            if (rec.chatType != 2) {
                val target = if (rec.peerUin > 0) rec.peerUin else UinUidStore.uin(rec.peerUid)
                if (target > 0) event.put("target_id", target)
            }
        }
        converted.incrementAndGet()
        pico.onebot.core.Counters.onRecv()
        EventBus.post(event)
    }

    private fun handleUpdate(rec: MsgRecord) {
        if (rec.recallTime > 0 && markRecalled(rec.msgId)) {
            val entry = MsgIdStore.byMsgId(rec.msgId)
            val messageId = entry?.messageId ?: 0
            val operator = if (rec.senderUin > 0) rec.senderUin else UinUidStore.uin(rec.senderUid)
            val event = Ob.event("notice")
            if (rec.chatType == 2) {
                event.put("notice_type", "group_recall")
                    .put("group_id", rec.peerUid?.toLongOrNull() ?: rec.peerUin)
                    .put("user_id", operator)
                    .put("operator_id", operator)
                    .put("message_id", messageId)
            } else {
                event.put("notice_type", "friend_recall")
                    .put("user_id", operator)
                    .put("message_id", messageId)
            }
            EventBus.post(event)
            return
        }
        // 未被去重闸放行过的更新(例如离线消息先 update 后 add)也要能成为 message
        if (!seenContains(rec.msgId) && rec.elements != null && rec.elements.isNotEmpty()) {
            handleIncoming(rec)
        }
    }

    /**
     * 自发判定**优先比 uid**:NT 的 MsgRecord 常常只填 senderUid 而把 senderUin 留成 0,
     * 只比 uin 就会把自己发的消息当成别人发的再投一条 message —— 机器人当场自问自答。
     */
    private fun isSelfSent(rec: MsgRecord): Boolean {
        val selfUid = KernelGate.selfUid()
        if (selfUid.isNotEmpty() && rec.senderUid == selfUid) return true
        val selfUin = KernelGate.selfUin()
        return selfUin > 0 && rec.senderUin == selfUin
    }

    /** sendStatus / sendType 没有公开枚举,取值只能靠线上样本认,所以原样打出来。 */
    private fun digest(rec: MsgRecord): String =
        "msgId=" + rec.msgId + " seq=" + rec.msgSeq + " chat=" + rec.chatType +
            " elems=" + (rec.elements?.size ?: 0) + " sendStatus=" + rec.sendStatus +
            " sendType=" + rec.sendType + " senderUin=" + rec.senderUin +
            " senderUid=" + shortUid(rec.senderUid) + " peerUid=" + (rec.peerUid ?: "") +
            " peerUin=" + rec.peerUin + " online=" + rec.isOnlineMsg

    private fun shortUid(uid: String?): String =
        if (uid.isNullOrEmpty()) "" else if (uid.length <= 6) uid else uid.take(6) + "***"

    // ---------------- 去重 ----------------

    private fun markSeen(msgId: Long): Boolean = synchronized(seen) {
        if (msgId == 0L) return true
        if (!seen.add(msgId)) return false
        trim(seen)
        true
    }

    private fun seenContains(msgId: Long): Boolean = synchronized(seen) { seen.contains(msgId) }

    private fun markRecalled(msgId: Long): Boolean = synchronized(recalled) {
        if (!recalled.add(msgId)) return false
        trim(recalled)
        true
    }

    private fun trim(set: LinkedHashSet<Long>) {
        while (set.size > DEDUP_CAP) {
            val it = set.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    fun stats(): JSONObject = JSONObject()
        .put("enqueued", enqueued.get())
        .put("converted", converted.get())
        .put("dropped", droppedIn.get())
        .put("dropped_junk", droppedJunk.get())

        .put("send_errors", sendErrors.get())
        .put("no_msgid", noMsgId.get())
        .put("pending", if (running) queue.size else 0)
        .put("dedup_size", synchronized(seen) { seen.size })
}

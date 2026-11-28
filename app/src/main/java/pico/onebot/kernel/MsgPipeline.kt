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

    private val queueLock = Any()
    private val pipelineEpoch = AtomicLong(0L)

    private lateinit var queue: ArrayBlockingQueue<Job>

    private val enqueued = AtomicLong()
    private val droppedIn = AtomicLong()
    private val droppedJunk = AtomicLong()

    private val sendErrors = AtomicLong()
    private val noMsgId = AtomicLong()
    private val converted = AtomicLong()

    @Volatile
    private var running = false

    private class SendErrorPayload(
        val msgId: Long,
        val chatType: Int,
        val peerUid: String,
        val code: Int,
        val msg: String
    )

    private class Job(
        val kind: Int,
        val epoch: Long,
        val records: List<MsgRecord> = emptyList(),
        val sendError: SendErrorPayload? = null
    )

    private const val KIND_INCOMING = 0
    private const val KIND_UPDATE = 1
    private const val KIND_SELF_SENT = 2
    private const val KIND_SEND_ERROR = 3

    fun start() {
        synchronized(this) {
            if (running) return
            queue = ArrayBlockingQueue(Config.eventQueueSize.coerceAtLeast(16))
            running = true
        }
        Thread({ pump() }, "pico-msgpipe").apply { isDaemon = true }.start()
    }

    /** 会话丢失/切号时递增代际并清空队列与去重表,阻止旧账号消息串发到新会话。 */
    fun reset() {
        pipelineEpoch.incrementAndGet()
        if (running) {
            synchronized(queueLock) {
                queue.clear()
            }
        }
        synchronized(seen) { seen.clear() }
        synchronized(recalled) { recalled.clear() }
        PicoLog.d("msg pipeline reset")
    }

    // ---------------- 内核回调入口(必须极快返回) ----------------

    fun onIncoming(source: String, records: ArrayList<MsgRecord>) {
        if (records.isEmpty()) return
        for (r in records) SendTracker.onRecordSeen(r)
        offer(Job(KIND_INCOMING, pipelineEpoch.get(), ArrayList(records)))
    }

    fun onUpdate(records: ArrayList<MsgRecord>) {
        if (records.isEmpty()) return
        for (r in records) SendTracker.onRecordSeen(r)
        offer(Job(KIND_UPDATE, pipelineEpoch.get(), ArrayList(records)))
    }

    fun onSelfSent(record: MsgRecord) {
        SendTracker.onRecordSeen(record)
        offer(Job(KIND_SELF_SENT, pipelineEpoch.get(), listOf(record)))
    }

    /**
     * 发送失败的异步通知。快路径立即唤醒 SendTracker,耗时的查表/JNI 富化卸载到管道线程执行。
     */
    fun onSendError(msgId: Long, contact: Any?, code: Int, msg: String) {
        val c = contact as? com.tencent.qqnt.kernelpublic.nativeinterface.Contact
        val chatType = c?.chatType ?: 0
        val peerUid = c?.peerUid ?: ""
        sendErrors.incrementAndGet()
        PicoLog.w("send failed msgId=$msgId chat=$chatType peer=$peerUid code=$code $msg")
        SendTracker.onSendError(msgId, chatType, peerUid, code, msg)
        offer(
            Job(
                kind = KIND_SEND_ERROR,
                epoch = pipelineEpoch.get(),
                sendError = SendErrorPayload(msgId, chatType, peerUid, code, msg)
            )
        )
    }

    fun onSysMsg(payload: Any?) {
        // P2:onRecvSysMsg 给的是 protobuf 字节(ArrayList<Byte>),解出来才能产 request 事件
        val size = (payload as? java.util.ArrayList<*>)?.size ?: 0
        PicoLog.d("onRecvSysMsg bytes=$size (request 事件解析待 M5)")
    }

    private fun offer(job: Job) {
        if (!running) return
        enqueued.incrementAndGet()
        synchronized(queueLock) {
            while (!queue.offer(job)) {
                if (queue.poll() != null) droppedIn.incrementAndGet()
            }
        }
    }

    // ---------------- 单线程转换 ----------------

    private fun pump() {
        while (running) {
            val job = try {
                queue.take()
            } catch (t: InterruptedException) {
                if (!running) return
                PicoLog.w("msgpipe interrupted while running, continuing")
                continue
            } catch (t: Throwable) {
                PicoLog.w("msgpipe take failed", t)
                continue
            }
            if (job.epoch != pipelineEpoch.get()) continue
            if (job.kind == KIND_SEND_ERROR) {
                try {
                    job.sendError?.let { handleSendError(it, job.epoch) }
                } catch (t: Throwable) {
                    PicoLog.w("send error enrich failed msgId=" + (job.sendError?.msgId ?: 0L), t)
                }
                continue
            }
            for (r in job.records) {
                if (job.epoch != pipelineEpoch.get()) break
                try {
                    when (job.kind) {
                        KIND_INCOMING -> handleIncoming(r, job.epoch, forceSelf = false)
                        KIND_SELF_SENT -> handleIncoming(r, job.epoch, forceSelf = true)
                        else -> handleUpdate(r, job.epoch)
                    }
                } catch (t: Throwable) {
                    PicoLog.w("msg convert failed msgId=" + r.msgId, t)
                }
            }
        }
    }

    private fun handleSendError(p: SendErrorPayload, epoch: Long) {
        if (epoch != pipelineEpoch.get()) return
        val entry = MsgIdStore.byMsgId(p.msgId)
        val event = Ob.event("notice")
            .put("notice_type", "pico_send_failed")
            .put("message_id", entry?.messageId ?: 0)
            .put("msg_id", p.msgId.toString())
            .put("error_code", p.code)
            .put("error_message", p.msg)
        if (p.chatType == 2) {
            event.put("group_id", p.peerUid.toLongOrNull() ?: 0L)
        } else {
            event.put("user_id", UinUidStore.uin(p.peerUid))
        }
        if (epoch != pipelineEpoch.get()) return
        EventBus.post(event)
    }

    private fun handleIncoming(rec: MsgRecord, epoch: Long, forceSelf: Boolean = false) {
        if (epoch != pipelineEpoch.get()) return
        // 内核会为"发送失败 / 占位 / 已清空"的消息也回调 MsgRecord,elements 是空的。
        // 放它过去就会变成一条 user_id=0、message=[] 的垃圾事件,还白占一个 message_id。
        if ((rec.elements?.size ?: 0) == 0) {
            droppedJunk.incrementAndGet()
            PicoLog.d("drop empty record " + digest(rec))
            return
        }
        if (rec.msgId == 0L) noMsgId.incrementAndGet()
        if (seenContains(rec.msgId)) return

        val isSelf = forceSelf || isSelfSent(rec)
        if (isSelf && !Config.reportSelfMessage) {
            markSeen(rec.msgId)
            return
        }
        PicoLog.d((if (isSelf) "self " else "recv ") + digest(rec))

        val body = MsgCodec.toMessageBody(rec) ?: run {
            droppedJunk.incrementAndGet()
            PicoLog.d("drop empty decoded record " + digest(rec))
            return
        }
        if (epoch != pipelineEpoch.get()) return
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
        if (epoch != pipelineEpoch.get()) return
        if (!markSeen(rec.msgId)) return
        converted.incrementAndGet()
        pico.onebot.core.Counters.onRecv()
        if (!isSelf) {
            val peer = rec.peerUid ?: ""
            MsgWatchdog.onInboundMessage(rec.chatType, peer, rec.msgSeq)
            ReadReporter.onInboundMessage(rec.chatType, peer)
        }
        EventBus.post(event)
    }

    private fun handleUpdate(rec: MsgRecord, epoch: Long) {
        if (epoch != pipelineEpoch.get()) return
        if (rec.recallTime > 0 && markRecalled(rec.msgId)) {
            val entry = MsgIdStore.byMsgId(rec.msgId)
            val messageId = entry?.messageId ?: 0
            val operator = if (rec.senderUin > 0) rec.senderUin else UinUidStore.uin(rec.senderUid)
            if (epoch != pipelineEpoch.get()) return
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
        // 未被去重闸放行过的更新(例如离线消息先 update 后 add,或初始空记录后续补齐)也要能成为 message
        if (rec.recallTime <= 0 && !seenContains(rec.msgId) && (rec.elements?.size ?: 0) > 0) {
            handleIncoming(rec, epoch, forceSelf = false)
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
        .put("last_recv_at", pico.onebot.core.Counters.lastRecvAt())
}

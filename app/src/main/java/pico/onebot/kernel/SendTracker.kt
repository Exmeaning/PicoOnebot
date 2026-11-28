package pico.onebot.kernel

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import pico.onebot.core.Promise
import java.util.concurrent.ConcurrentHashMap

/**
 * 发送回执关联:我们**预生成** msgId 传给 `sendMsg`,内核随后会在
 * `onAddSendMsg` / `onMsgInfoListAdd` 里带着同一个 msgId 把落库记录送回来。
 * 于是"发送结果"和"事件流"能精确对上,不靠时间窗 + 内容 hash 猜。
 */
object SendTracker {

    private const val FAILURE_CAP = 256
    private const val FAILURE_TTL_NS = 60_000_000_000L

    private val waiting = ConcurrentHashMap<Long, Promise<MsgRecord>>()

    fun expect(msgId: Long): Promise<MsgRecord> {
        val p = Promise<MsgRecord>()
        waiting[msgId] = p
        return p
    }

    fun forget(msgId: Long) {
        waiting.remove(msgId)
    }

    fun onRecordSeen(record: MsgRecord) {
        val p = waiting.remove(record.msgId) ?: return
        p.resolve(record)
    }

    fun pending(): Int = waiting.size

    /** 会话丢失/切号时释放全部在途发送等待并清空旧账号失败记录。 */
    fun reset() {
        val pendingList = ArrayList<Promise<MsgRecord>>()
        val it = waiting.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (waiting.remove(e.key, e.value)) {
                pendingList.add(e.value)
            }
        }
        synchronized(failuresLock) {
            failures.clear()
        }
        for (p in pendingList) p.resolve(null)
    }

    // ---------------- 真实发送失败 ----------------

    /**
     * `IOperateCallback(code=0)` 只说明内核**本地受理**了,真正的投递失败是异步回来的
     * (`onSendMsgError`)。实测合并转发就栽在这上面:回调回 0、本地记录齐全、事件也发了,
     * 但群里根本没收到。所以失败必须单独记账,不能只看回调。
     */
    class SendFailure(
        val msgId: Long,
        val chatType: Int,
        val peerUid: String,
        val code: Int,
        val msg: String
    ) {
        val at: Long = System.currentTimeMillis()
        internal val atNs: Long = System.nanoTime()
    }

    /**
     * 按 `msgId` 索引,以插入序保存(最旧在前),避免哈希序淘汰误删刚写入的失败记录。
     */
    private val failuresLock = Any()
    private val failures = LinkedHashMap<Long, SendFailure>()

    fun onSendError(msgId: Long, chatType: Int, peerUid: String, code: Int, msg: String) {
        if (msgId == 0L) return
        val f = SendFailure(msgId, chatType, peerUid, code, msg)
        val nowNs = f.atNs
        synchronized(failuresLock) {
            failures.remove(msgId)
            failures[msgId] = f
            if (failures.size > FAILURE_CAP) {
                val ttlIt = failures.entries.iterator()
                while (ttlIt.hasNext()) {
                    val e = ttlIt.next()
                    if (e.key != msgId && (nowNs - e.value.atNs).coerceAtLeast(0L) > FAILURE_TTL_NS) {
                        ttlIt.remove()
                    }
                }
                val evictIt = failures.entries.iterator()
                while (failures.size > FAILURE_CAP && evictIt.hasNext()) {
                    val e = evictIt.next()
                    if (e.key != msgId) evictIt.remove()
                }
            }
        }
        // 正在等这条 msgId 落库的调用方不该再干等到超时
        waiting.remove(msgId)?.resolve(null)
    }

    /** 取走这条 msgId 的异步投递失败(取走即清)。 */
    fun takeFailure(msgId: Long): SendFailure? {
        if (msgId == 0L) return null
        return synchronized(failuresLock) { failures.remove(msgId) }
    }
}

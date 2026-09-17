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
    }

    private val failures = ConcurrentHashMap<String, SendFailure>()

    private fun failKey(chatType: Int, peerUid: String) = chatType.toString() + ":" + peerUid

    fun onSendError(msgId: Long, chatType: Int, peerUid: String, code: Int, msg: String) {
        val f = SendFailure(msgId, chatType, peerUid, code, msg)
        failures[failKey(chatType, peerUid)] = f
        // 正在等这条 msgId 落库的调用方不该再干等到超时
        waiting.remove(msgId)?.resolve(null)
    }

    /** 取走 [since] 之后、该会话上的发送失败(取走即清,避免影响下一次发送)。 */
    fun takeFailureSince(chatType: Int, peerUid: String, since: Long): SendFailure? {
        val k = failKey(chatType, peerUid)
        val f = failures[k] ?: return null
        if (f.at < since) return null
        failures.remove(k)
        return f
    }
}

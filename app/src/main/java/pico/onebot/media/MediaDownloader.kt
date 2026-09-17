package pico.onebot.media

import com.tencent.qqnt.kernel.nativeinterface.FileTransNotifyInfo
import com.tencent.qqnt.kernel.nativeinterface.RichMediaElementGetReq
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.core.Promise
import pico.onebot.kernel.KernelGate
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 把收到的富媒体真正**落到本地磁盘**。
 *
 * 收消息时元素里给的 `filePath` / `sourcePath` 经常是空的 —— 内核只在用户真去点开
 * 那条消息时才下载。所以 `get_record` / `get_image` 这类动作必须主动触发一次下载,
 * 否则上游永远只拿得到一个空路径。
 *
 * 下载是**异步**的:`downloadRichMedia` 立刻返回,完成通过
 * `IKernelMsgListener.onRichMediaDownloadComplete(FileTransNotifyInfo)` 回来,
 * 靠 (msgId, elementId) 配对 —— 和发送侧预生成 msgId 是同一个思路,不猜不等时间窗。
 */
object MediaDownloader {

    private val waiting = ConcurrentHashMap<String, Promise<String>>()
    private val started = AtomicLong()
    private val done = AtomicLong()
    private val failed = AtomicLong()

    private fun key(msgId: Long, elementId: Long) = msgId.toString() + ":" + elementId

    class DownloadError(msg: String) : RuntimeException(msg)

    /**
     * @param existing 元素里自带的路径,存在就直接用,不白跑一趟网络。
     * @return 落地后的文件。
     */
    fun fetch(
        chatType: Int,
        peerUid: String,
        msgId: Long,
        elementId: Long,
        existing: String?,
        thumb: Boolean = false
    ): File {
        if (!existing.isNullOrEmpty()) {
            val f = File(existing)
            if (f.isFile && f.length() > 0) return f
        }
        val svc = KernelGate.msgService() ?: throw DownloadError("内核未就绪")
        if (msgId == 0L || elementId == 0L) {
            throw DownloadError("缺少 msgId/elementId,无法定位要下载的元素")
        }

        val k = key(msgId, elementId)
        val promise = waiting.getOrPut(k) { Promise() }
        val req = RichMediaElementGetReq()
        req.msgId = msgId
        req.chatType = chatType
        req.peerUid = peerUid
        req.elementId = elementId
        req.thumbSize = if (thumb) 750 else 0
        req.downloadType = if (thumb) 2 else 1
        req.filePath = ""
        req.triggerType = 1
        req.downSourceType = 0
        req.fileModelId = 0

        started.incrementAndGet()
        try {
            svc.downloadRichMedia(req)
        } catch (t: Throwable) {
            waiting.remove(k)
            throw DownloadError("downloadRichMedia 抛出: " + t.javaClass.simpleName + " " + t.message)
        }

        val path = promise.await(Config.kernelTimeoutMs)
        waiting.remove(k)
        if (path.isNullOrEmpty()) {
            failed.incrementAndGet()
            throw DownloadError("下载超时或内核未回调 (msgId=" + msgId + " elementId=" + elementId + ")")
        }
        val f = File(path)
        if (!f.isFile) {
            failed.incrementAndGet()
            throw DownloadError("内核报完成但文件不在: " + path)
        }
        done.incrementAndGet()
        return f
    }

    /** 由 MsgListenerProxy 在内核回调线程上调用 —— 只做配对和唤醒,不做任何 IO。 */
    fun onComplete(info: Any?) {
        val i = info as? FileTransNotifyInfo ?: return
        PicoLog.d(
            "richmedia done msgId=" + i.msgId + " elemId=" + i.msgElementId +
                " status=" + i.trasferStatus + " err=" + i.fileErrCode +
                " path=" + (i.filePath ?: "")
        )
        // 同一次下载会回调**两次**:先 status=4 带路径的成功,再 status=5 空路径的失败
        // (实测 err=2006024)。谁先到没有保证,所以空路径一律不当结论 —— 认了它就会把
        // 已经下好的文件报成失败。真失败交给调用方的超时兜底。
        if (i.filePath.isNullOrEmpty()) return
        val p = waiting[key(i.msgId, i.msgElementId)] ?: return
        p.resolve(i.filePath)
    }

    fun stats(): Map<String, Long> = linkedMapOf(
        "started" to started.get(),
        "done" to done.get(),
        "failed" to failed.get(),
        "waiting" to waiting.size.toLong()
    )
}

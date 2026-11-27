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
    private val waitingLock = Any()
    private val activeWaiters = HashMap<Promise<String>, Int>()
    private val started = AtomicLong()
    private val done = AtomicLong()
    private val failed = AtomicLong()

    @Volatile
    private var thumbSizeFieldChecked = false

    @Volatile
    private var thumbSizeField: java.lang.reflect.Field? = null

    @Volatile
    private var fileDownTypeFieldChecked = false

    @Volatile
    private var fileDownTypeField: java.lang.reflect.Field? = null

    private fun key(msgId: Long, elementId: Long, thumb: Boolean) =
        msgId.toString() + ":" + elementId + ":" + thumb

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

        val k = key(msgId, elementId, thumb)
        val promise: Promise<String>
        val isInitiator: Boolean
        synchronized(waitingLock) {
            val cur = waiting[k]
            if (cur != null) {
                promise = cur
                isInitiator = false
            } else {
                promise = Promise()
                waiting[k] = promise
                isInitiator = true
            }
            activeWaiters[promise] = (activeWaiters[promise] ?: 0) + 1
        }

        try {
            if (isInitiator) {
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
                    synchronized(waitingLock) {
                        waiting.remove(k, promise)
                    }
                    failed.incrementAndGet()
                    promise.resolve("")
                    throw DownloadError("downloadRichMedia 抛出: " + t.javaClass.simpleName + " " + t.message)
                }
            }

            val path = try {
                promise.await(Config.kernelTimeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                failed.incrementAndGet()
                throw DownloadError("下载等待被中断 (msgId=" + msgId + " elementId=" + elementId + ")")
            }
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
        } finally {
            synchronized(waitingLock) {
                val left = (activeWaiters[promise] ?: 1) - 1
                if (left <= 0) {
                    activeWaiters.remove(promise)
                    waiting.remove(k, promise)
                } else {
                    activeWaiters[promise] = left
                }
            }
        }
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
        val path = i.filePath
        if (path.isNullOrEmpty()) return

        val inferredThumb = inferThumb(i, path)
        val target: Promise<String>? = synchronized(waitingLock) {
            val found = if (inferredThumb != null) {
                val exactKey = key(i.msgId, i.msgElementId, inferredThumb)
                waiting[exactKey]?.let { exactKey to it }
            } else {
                val originKey = key(i.msgId, i.msgElementId, false)
                val thumbKey = key(i.msgId, i.msgElementId, true)
                waiting[originKey]?.let { originKey to it }
                    ?: waiting[thumbKey]?.let { thumbKey to it }
            }
            if (found != null) {
                waiting.remove(found.first, found.second)
                found.second
            } else {
                null
            }
        }
        target?.resolve(path)
    }

    /** 切号/会话失效时唤醒并清空所有挂起下载，避免调用线程干等 kernelTimeoutMs。 */
    fun reset() {
        val snapshot: List<Pair<String, Promise<String>>> = synchronized(waitingLock) {
            val list = ArrayList<Pair<String, Promise<String>>>(waiting.size)
            for ((k, p) in waiting) {
                list.add(k to p)
            }
            for ((k, p) in list) {
                waiting.remove(k, p)
            }
            list
        }
        for ((_, p) in snapshot) {
            p.resolve("")
        }
    }

    private fun lookupIntField(cls: Class<*>, name: String): java.lang.reflect.Field? = try {
        cls.getField(name)
    } catch (_: Throwable) {
        try {
            cls.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readIntField(target: Any, field: java.lang.reflect.Field?): Int {
        if (field == null) return -1
        return try {
            field.getInt(target)
        } catch (_: Throwable) {
            -1
        }
    }

    private fun inferThumb(i: Any, path: String): Boolean? {
        if (!fileDownTypeFieldChecked) {
            fileDownTypeField = lookupIntField(i.javaClass, "fileDownType")
            fileDownTypeFieldChecked = true
        }
        if (!thumbSizeFieldChecked) {
            thumbSizeField = lookupIntField(i.javaClass, "thumbSize")
            thumbSizeFieldChecked = true
        }
        val downType = readIntField(i, fileDownTypeField)
        val thumbSize = readIntField(i, thumbSizeField)

        // fileDownType == 2 明确为缩略图（含 thumbSize == 0 的视频缩略图）
        if (downType == 2) return true
        if (thumbSize > 0) return true
        // 优先于 thumbSize == 0 检查 /Thumb/ 路径，防止视频缩略图落入死代码
        if (path.contains("/Thumb/", ignoreCase = true)) return true
        // fileDownType == 1 且未命中缩略图信号，明确为原件
        if (downType == 1) return false
        if (path.contains("_750")) return true
        if (thumbSize == 0) return false
        return null
    }

    fun stats(): Map<String, Long> = linkedMapOf(
        "started" to started.get(),
        "done" to done.get(),
        "failed" to failed.get(),
        "waiting" to waiting.size.toLong()
    )
}

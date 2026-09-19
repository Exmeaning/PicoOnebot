package pico.onebot.net

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.action.ActionRouter
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HTTP 事件上报(CQHTTP 的 `post_url`)。
 *
 * 为什么还要留这条路:老一代插件根本不连 WebSocket,只在自己那头开个 HTTP 服务等我们 POST。
 * 反向 WS 再好也救不了它们。
 *
 * 纪律与 [pico.onebot.event.EventBus] 一致:**绝不阻塞事件分发线程**。
 * 上报自己排队、自己重试放弃,对端挂了只丢自己的队列,不影响 WS 那一路。
 */
object HttpReporter {

    private data class Target(val url: String, val token: String, val secret: String)

    private lateinit var queue: ArrayBlockingQueue<String>

    private val posted = AtomicLong()
    private val failed = AtomicLong()
    private val dropped = AtomicLong()
    private val quickOps = AtomicLong()

    @Volatile
    private var running = false

    @Volatile
    private var targets: List<Target> = emptyList()

    @Synchronized
    fun start() {
        val configured = Config.httpClients()
        targets = (0 until configured.length()).mapNotNull { i ->
            val item = configured.optJSONObject(i) ?: return@mapNotNull null
            if (!item.optBoolean("enabled", true)) return@mapNotNull null
            val url = item.optString("url", "")
            if (url.isBlank()) return@mapNotNull null
            Target(url, item.optString("token", ""), item.optString("secret", ""))
        }
        if (running || targets.isEmpty()) return
        queue = ArrayBlockingQueue(Config.int("post_queue_size", 1024))
        running = true
        Thread({ pump() }, "pico-report").apply { isDaemon = true }.start()
        PicoLog.i("http report targets: " + targets.joinToString(", ") { it.url })
    }

    /** 当前真正会收到事件的上报地址数量。 */
    fun targetCount(): Int = if (running) targets.size else 0

    /** 这个地址此刻是否真的在上报队列里 —— 配置写了不代表生效。 */
    fun reporting(url: String): Boolean = running && targets.any { it.url == url }

    /** 事件分发线程调用:只入队。队列满时丢**最老的**,保证新事件优先送达。 */
    fun offer(text: String) {
        if (!running) return
        if (!queue.offer(text)) {
            queue.poll()
            dropped.incrementAndGet()
            queue.offer(text)
        }
    }

    private fun pump() {
        while (running) {
            val text = try {
                queue.take()
            } catch (t: InterruptedException) {
                return
            }
            for (target in targets) {
                try {
                    val reply = postOnce(target, text)
                    posted.incrementAndGet()
                    if (!reply.isNullOrBlank()) applyQuickOperation(text, reply)
                } catch (t: Throwable) {
                    failed.incrementAndGet()
                    PicoLog.w("report to ${target.url} failed: " + t.javaClass.simpleName + " " + t.message)
                }
            }
        }
    }

    private fun postOnce(target: Target, text: String): String? {
        val body = text.toByteArray(Charsets.UTF_8)
        val conn = URL(target.url).openConnection() as HttpURLConnection
        val timeout = Config.int("post_timeout_ms", 10000)
        conn.requestMethod = "POST"
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("User-Agent", "PicoOnebot/" + pico.onebot.PicoBoot.VERSION)
        conn.setRequestProperty("X-Self-ID", KernelGate.selfUin().toString())
        if (target.token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer " + target.token)
        if (target.secret.isNotEmpty()) {
            conn.setRequestProperty("X-Signature", "sha1=" + hmacSha1(target.secret, body))
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.use { read(it) } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code " + resp.take(200))
            return resp
        } finally {
            conn.disconnect()
        }
    }

    private fun read(ins: java.io.InputStream): String {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        while (true) {
            val n = ins.read(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
            if (buf.size() > 1 shl 20) break
        }
        return buf.toString("UTF-8")
    }

    private fun hmacSha1(secret: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val sig = mac.doFinal(body)
        val sb = StringBuilder(sig.size * 2)
        for (b in sig) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    // ---------------- 快速操作 ----------------

    /**
     * CQHTTP 的"快速操作":对端直接在 HTTP 响应体里回一个动作,省掉一次反向调用。
     * 只支持 `reply`(带 `at_sender` / `auto_escape`)和 `delete` —— 其余(禁言、踢人、
     * 同意入群)手表端本来就做不到,静默忽略比假装成功好。
     */
    private fun applyQuickOperation(eventText: String, replyText: String) {
        val op = try {
            JSONObject(replyText)
        } catch (t: Throwable) {
            return // 对端回了空 / 非 JSON,是正常的
        }
        if (op.length() == 0) return
        val event = try {
            JSONObject(eventText)
        } catch (t: Throwable) {
            return
        }
        if (event.optString("post_type") != "message") return

        if (op.optBoolean("delete", false)) {
            quickOps.incrementAndGet()
            ActionRouter.call("delete_msg", JSONObject().put("message_id", event.optInt("message_id")))
        }

        val reply = op.opt("reply") ?: return
        val isGroup = event.optString("message_type") == "group"
        val params = JSONObject()
            .put("message_type", if (isGroup) "group" else "private")
            .put("auto_escape", op.optBoolean("auto_escape", false))
        if (isGroup) params.put("group_id", event.optLong("group_id")) else params.put("user_id", event.optLong("user_id"))

        // at_sender 只在群里有意义,并且要在**已有内容前面**插,不能整条替换掉
        if (isGroup && op.optBoolean("at_sender", false)) {
            val segs = JSONArray()
                .put(
                    JSONObject().put("type", "at")
                        .put("data", JSONObject().put("qq", event.optLong("user_id").toString()))
                )
                .put(JSONObject().put("type", "text").put("data", JSONObject().put("text", " ")))
            for (s in pico.onebot.msg.MsgCodec.normalize(reply)) segs.put(s)
            params.put("message", segs)
        } else {
            params.put("message", reply)
        }
        quickOps.incrementAndGet()
        val r = ActionRouter.call("send_msg", params)
        if (r.optInt("retcode", -1) != 0) PicoLog.w("quick reply failed: " + r.optString("message"))
    }

    private operator fun JSONArray.iterator(): Iterator<Any> = object : Iterator<Any> {
        private var i = 0
        override fun hasNext(): Boolean = i < length()
        override fun next(): Any = get(i++)
    }

    fun stats(): JSONObject = JSONObject()
        .put("targets", targets.size)
        .put("posted", posted.get())
        .put("failed", failed.get())
        .put("dropped", dropped.get())
        .put("quick_ops", quickOps.get())
        .put("pending", if (running) queue.size else 0)
}

package pico.onebot.net

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.action.ActionRouter
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * 每个上报目标拥有独立的有界队列、独立工作线程与独立失败熔断退避,单个失效目标
 * 绝不会通过队头阻塞拖慢或饿死其它健康目标。
 */
object HttpReporter {

    private data class Target(val url: String, val token: String, val secret: String)

    private class HttpStatusException(val code: Int, message: String) : java.io.IOException(message)

    private const val MIN_TIMEOUT_MS = 1_000
    private const val MAX_TIMEOUT_MS = 60_000
    private const val MIN_QUEUE_SIZE = 16
    private const val MAX_QUEUE_SIZE = 65_536
    private const val CIRCUIT_FAIL_THRESHOLD = 5
    private const val CIRCUIT_BASE_COOLDOWN_MS = 5_000L
    private const val CIRCUIT_MAX_COOLDOWN_MS = 60_000L

    private class TargetWorker(
        val target: Target,
        val displayUrl: String,
        queueCap: Int,
        private val gen: Long,
        index: Int
    ) {
        val queue = ArrayBlockingQueue<String>(queueCap)
        private val queueLock = Any()

        @Volatile
        private var running = true

        @Volatile
        private var activeConn: HttpURLConnection? = null

        private var consecutiveFailures = 0
        private var circuitOpenUntilNs = 0L

        private val thread = Thread({ pump() }, "pico-report-$gen-$index").apply {
            isDaemon = true
        }

        fun start() {
            thread.start()
        }

        fun offer(text: String) {
            if (!running) return
            synchronized(queueLock) {
                if (!running) return
                if (!queue.offer(text)) {
                    queue.poll()
                    dropped.incrementAndGet()
                    queue.offer(text)
                }
            }
        }

        fun stop() {
            running = false
            try {
                activeConn?.disconnect()
            } catch (_: Throwable) {
            }
            activeConn = null
            thread.interrupt()
            synchronized(queueLock) {
                queue.clear()
            }
        }

        private fun pump() {
            while (running && HttpReporter.running && workerGen.get() == gen) {
                val text = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    return
                }
                if (!running || !HttpReporter.running || workerGen.get() != gen) return
                val nowNs = System.nanoTime()
                if (circuitOpenUntilNs != 0L && (circuitOpenUntilNs - nowNs) > 0L) {
                    dropped.incrementAndGet()
                    continue
                }
                val ok = postWithRetry(text)
                if (ok) {
                    if (consecutiveFailures >= CIRCUIT_FAIL_THRESHOLD) {
                        PicoLog.i("report target recovered: $displayUrl")
                    }
                    consecutiveFailures = 0
                    circuitOpenUntilNs = 0L
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= CIRCUIT_FAIL_THRESHOLD) {
                        val shift = (consecutiveFailures - CIRCUIT_FAIL_THRESHOLD).coerceAtMost(4)
                        val cooldownMs = minOf(CIRCUIT_BASE_COOLDOWN_MS shl shift, CIRCUIT_MAX_COOLDOWN_MS)
                        circuitOpenUntilNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cooldownMs)
                        PicoLog.w(
                            "report circuit open for $displayUrl (${cooldownMs}ms cooldown after $consecutiveFailures failures)"
                        )
                    }
                }
            }
        }

        private fun postWithRetry(text: String): Boolean {
            val backoffs = longArrayOf(200L, 500L)
            var attempt = 0
            while (running && HttpReporter.running && workerGen.get() == gen) {
                try {
                    val reply = postOnce(text)
                    posted.incrementAndGet()
                    if (!reply.isNullOrBlank()) dispatchQuickOp(text, reply)
                    return true
                } catch (e: HttpStatusException) {
                    if (e.code in 300..499 || attempt >= backoffs.size || !running || !HttpReporter.running || workerGen.get() != gen) {
                        failed.incrementAndGet()
                        PicoLog.w("report to $displayUrl failed: HTTP ${e.code}")
                        return false
                    }
                    PicoLog.d("report to $displayUrl retry #${attempt + 1}: HTTP ${e.code}")
                } catch (t: Throwable) {
                    val safeMsg = sanitizeError(t, target.url, displayUrl)
                    if (t !is java.io.IOException || attempt >= backoffs.size || !running || !HttpReporter.running || workerGen.get() != gen) {
                        failed.incrementAndGet()
                        PicoLog.w("report to $displayUrl failed: $safeMsg")
                        return false
                    }
                    PicoLog.d("report to $displayUrl retry #${attempt + 1}: $safeMsg")
                }
                try {
                    Thread.sleep(backoffs[attempt++])
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failed.incrementAndGet()
                    return false
                }
            }
            return false
        }

        private fun postOnce(text: String): String? {
            if (!running || !HttpReporter.running || workerGen.get() != gen) {
                throw InterruptedIOException("reporter stopped")
            }
            val parsed = URL(target.url)
            val proto = parsed.protocol.lowercase(Locale.ROOT)
            if (proto != "http" && proto != "https") {
                throw IllegalArgumentException("unsupported report URL scheme: $proto")
            }
            val body = text.toByteArray(Charsets.UTF_8)
            val conn = parsed.openConnection() as HttpURLConnection
            val timeout = Config.int("post_timeout_ms", 10_000).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
            conn.instanceFollowRedirects = false
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
            activeConn = conn
            try {
                if (!running || !HttpReporter.running || workerGen.get() != gen) {
                    throw InterruptedIOException("reporter stopped")
                }
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.use { read(it) } ?: ""
                if (code !in 200..299) throw HttpStatusException(code, "HTTP $code")
                return resp
            } finally {
                if (activeConn === conn) activeConn = null
                conn.disconnect()
            }
        }
    }

    private val posted = AtomicLong()
    private val failed = AtomicLong()
    private val dropped = AtomicLong()
    private val quickOps = AtomicLong()
    private val quickOpSeq = AtomicInteger()
    private val workerGen = AtomicLong(0)

    private val quickOpExecutor = ThreadPoolExecutor(
        4,
        8,
        60L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(128),
        ThreadFactory { r ->
            Thread(r, "pico-quickop-" + quickOpSeq.incrementAndGet()).apply { isDaemon = true }
        }
    ).apply { allowCoreThreadTimeOut(true) }

    @Volatile
    private var running = false

    @Volatile
    private var workers: List<TargetWorker> = emptyList()

    private fun isHttpScheme(raw: String): Boolean = try {
        val scheme = URI(raw).scheme?.lowercase(Locale.ROOT)
        scheme == "http" || scheme == "https"
    } catch (_: Throwable) {
        false
    }

    private fun safeUrl(raw: String): String = try {
        val u = URI(raw)
        val scheme = u.scheme ?: ""
        val host = u.host ?: ""
        val portPart = if (u.port > 0) ":${u.port}" else ""
        val pathPart = u.rawPath ?: ""
        if (scheme.isNotEmpty() && host.isNotEmpty()) "$scheme://$host$portPart$pathPart"
        else redactUrlFallback(raw)
    } catch (_: Throwable) {
        redactUrlFallback(raw)
    }

    private fun redactUrlFallback(raw: String): String {
        val noQuery = raw.substringBefore('?').substringBefore('#')
        val schemeEnd = noQuery.indexOf("://")
        if (schemeEnd < 0) return noQuery
        val authStart = schemeEnd + 3
        val pathStart = noQuery.indexOf('/', authStart).let { if (it < 0) noQuery.length else it }
        val authority = noQuery.substring(authStart, pathStart)
        val atIdx = authority.lastIndexOf('@')
        if (atIdx < 0) return noQuery
        return noQuery.substring(0, authStart) + authority.substring(atIdx + 1) + noQuery.substring(pathStart)
    }

    private fun sanitizeError(t: Throwable, rawUrl: String, displayUrl: String): String {
        val rawMsg = if (t is URISyntaxException) t.reason else (t.message ?: "")
        val scrubbed = if (rawUrl.isNotEmpty() && rawMsg.contains(rawUrl)) {
            rawMsg.replace(rawUrl, displayUrl)
        } else {
            rawMsg
        }
        return if (scrubbed.isEmpty()) t.javaClass.simpleName else t.javaClass.simpleName + " " + scrubbed
    }

    @Synchronized
    fun start() {
        val configured = Config.httpClients()
        val nextTargets = (0 until configured.length()).mapNotNull { i ->
            val item = configured.optJSONObject(i) ?: return@mapNotNull null
            if (!item.optBoolean("enabled", true)) return@mapNotNull null
            val url = item.optString("url", "").trim()
            if (url.isBlank()) return@mapNotNull null
            if (!isHttpScheme(url)) {
                PicoLog.w("skipping http report target with unsupported scheme: " + safeUrl(url))
                return@mapNotNull null
            }
            Target(url, item.optString("token", ""), item.optString("secret", ""))
        }
        if (nextTargets.isEmpty()) {
            stop()
            return
        }
        if (running && workers.map { it.target } == nextTargets) return

        val oldWorkers = workers
        workers = emptyList()
        val gen = workerGen.incrementAndGet()
        for (w in oldWorkers) w.stop()

        val configuredCap = Config.int("post_queue_size", 1024).coerceIn(MIN_QUEUE_SIZE, MAX_QUEUE_SIZE)
        val perTargetCap = minOf(configuredCap, MAX_QUEUE_SIZE / maxOf(1, nextTargets.size))
            .coerceAtLeast(MIN_QUEUE_SIZE)
        running = true
        val startedWorkers = nextTargets.mapIndexed { idx, target ->
            TargetWorker(target, safeUrl(target.url), perTargetCap, gen, idx + 1).also { it.start() }
        }
        workers = startedWorkers
        PicoLog.i("http report targets: " + startedWorkers.joinToString(", ") { it.displayUrl })
    }

    @Synchronized
    fun stop() {
        running = false
        workerGen.incrementAndGet()
        val oldWorkers = workers
        workers = emptyList()
        for (w in oldWorkers) w.stop()
    }

    /** 当前真正会收到事件的上报地址数量。 */
    fun targetCount(): Int = if (running) workers.size else 0

    /** 这个地址此刻是否真的在上报队列里 —— 配置写了不代表生效。 */
    fun reporting(url: String): Boolean = running && workers.any { it.target.url == url }

    /** 事件分发线程调用:按目标独立入队。单个目标队列满时只丢它自己最老的,不影响其它目标。 */
    fun offer(text: String) {
        if (!running) return
        val snapshot = workers
        if (snapshot.isEmpty()) return
        for (w in snapshot) {
            w.offer(text)
        }
    }

    private fun dispatchQuickOp(eventText: String, replyText: String) {
        try {
            quickOpExecutor.execute {
                try {
                    applyQuickOperation(eventText, replyText)
                } catch (t: Throwable) {
                    PicoLog.w("quick op failed: " + t.message)
                }
            }
        } catch (_: Throwable) {
            PicoLog.w("quick op queue full, dropping quick operation")
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
        for (b in sig) sb.append(String.format(Locale.ROOT, "%02x", b))
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
        } catch (_: Throwable) {
            return // 对端回了空 / 非 JSON,是正常的
        }
        if (op.length() == 0) return
        val event = try {
            JSONObject(eventText)
        } catch (_: Throwable) {
            return
        }
        if (event.optString("post_type") != "message") return

        if (op.optBoolean("delete", false)) {
            quickOps.incrementAndGet()
            ActionRouter.call("delete_msg", JSONObject().put("message_id", event.optInt("message_id")))
        }

        if (op.isNull("reply")) return
        val reply = op.opt("reply") ?: return
        if (reply === JSONObject.NULL) return
        if (reply is String && reply.isEmpty()) return

        val isGroup = event.optString("message_type") == "group"
        val autoEscape = op.optBoolean("auto_escape", false)
        val params = JSONObject()
            .put("message_type", if (isGroup) "group" else "private")
            .put("auto_escape", autoEscape)
        if (isGroup) params.put("group_id", event.optLong("group_id")) else params.put("user_id", event.optLong("user_id"))

        // at_sender 只在群里有意义,并且要在**已有内容前面**插,不能整条替换掉。
        // 当 auto_escape=true 且 reply 为字符串时必须直接封装为纯 text 段,
        // 避免 MsgCodec.normalize() 解析其中的 CQ 码造成 CQ 注入。
        val atUserId = event.optLong("user_id")
        if (isGroup && op.optBoolean("at_sender", false) && atUserId > 0) {
            val segs = JSONArray()
                .put(
                    JSONObject().put("type", "at")
                        .put("data", JSONObject().put("qq", atUserId.toString()))
                )
                .put(JSONObject().put("type", "text").put("data", JSONObject().put("text", " ")))
            if (autoEscape && reply is String) {
                segs.put(
                    JSONObject().put("type", "text")
                        .put("data", JSONObject().put("text", reply))
                )
            } else {
                for (s in pico.onebot.msg.MsgCodec.normalize(reply)) segs.put(s)
            }
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

    fun stats(): JSONObject {
        val snapshot = workers
        var pendingTotal = 0
        for (w in snapshot) pendingTotal += w.queue.size
        return JSONObject()
            .put("targets", snapshot.size)
            .put("posted", posted.get())
            .put("failed", failed.get())
            .put("dropped", dropped.get())
            .put("quick_ops", quickOps.get())
            .put("pending", if (running) pendingTotal else 0)
    }
}

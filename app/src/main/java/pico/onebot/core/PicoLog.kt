package pico.onebot.core

import android.util.Log
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * logcat tag `PicoOB` + 进程内结构化环形缓冲。
 *
 * - `/api/logs` 读 [entries] 拿结构化 `{id,ts,level,tag,msg}`(WebUI 契约);
 * - `/api/pico/logs`(旧)与 `_pico_logs` 读 [snapshot] 拿格式化文本行;
 * - `WS /api/logs/stream` 用 [subscribe] 实时推送每一条新日志。
 *
 * 敏感字段统一打码,避免票据/令牌进日志。
 */
object PicoLog {

    const val TAG = "PicoOB"
    private const val RING_CAP = 4096

    /** 一条结构化日志。level 为 WebUI 口径:debug/info/warn/error。 */
    data class Entry(val id: Long, val ts: Long, val level: String, val tag: String, val msg: String) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("ts", ts).put("level", level).put("tag", tag).put("msg", msg)
    }

    private val ring = ArrayDeque<Entry>()
    private val seq = AtomicLong()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()

    private val URL_USERINFO_REGEX = Regex("(?i)\\b(https?|wss?)://([^/\\s@]+)@")
    private val KV_SECRET_REGEX = Regex(
        "(?i)(?<![A-Za-z0-9_])(\"?" +
            "(?:access_token|authorization|password|secret|token|qrsig|a2|d2|d2key|p_skey|skey|pt4_token)" +
            "\"?\\s*[=:]\\s*\"?)(?:(Bearer|Basic)\\s+)?([^\"'&,\\s}\\]]+)"
    )
    private val AUTH_SCHEME_REGEX = Regex("(?i)\\b(Bearer|Basic)\\s+([^\"'&,\\s}\\]]+)")

    fun d(msg: String) = write("debug", msg, null)
    fun i(msg: String) = write("info", msg, null)
    fun w(msg: String, t: Throwable? = null) = write("warn", msg, t)
    fun e(msg: String, t: Throwable? = null) = write("error", msg, t)

    private fun write(level: String, msg: String, t: Throwable?) {
        val maskedMsg = mask(msg)
        val safe = if (t != null) {
            val maskedErr = mask(t.message ?: "")
            "$maskedMsg | ${t.javaClass.simpleName}: $maskedErr"
        } else {
            maskedMsg
        }
        val entry = Entry(seq.incrementAndGet(), System.currentTimeMillis(), level, TAG, safe)
        synchronized(ring) {
            while (ring.size >= RING_CAP) ring.pollFirst()
            ring.addLast(entry)
        }
        for (l in listeners) {
            try { l(entry) } catch (ignored: Throwable) {}
        }
        when (level) {
            "error" -> if (t != null) Log.e(TAG, "$maskedMsg\n${formatThrowable(t)}") else Log.e(TAG, maskedMsg)
            "warn" -> if (t != null) Log.w(TAG, "$maskedMsg\n${formatThrowable(t)}") else Log.w(TAG, maskedMsg)
            "debug" -> Log.d(TAG, maskedMsg)
            else -> Log.i(TAG, maskedMsg)
        }
    }

    private fun formatThrowable(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return mask(sw.toString().trimEnd())
    }

    /** 最近 [limit] 行格式化文本,旧的在前(旧接口用)。 */
    fun snapshot(limit: Int): List<String> {
        val es = entriesInternal(limit)
        return es.map { e ->
            synchronized(fmt) { fmt.format(Date(e.ts)) } + " " + levelLetter(e.level) + "/" + e.msg
        }
    }

    /** 最近 [limit] 条结构化日志,旧的在前(WebUI /api/logs 用)。 */
    fun entries(limit: Int): List<Entry> = entriesInternal(limit)

    private fun entriesInternal(limit: Int): List<Entry> {
        synchronized(ring) {
            val all = ArrayList(ring)
            if (all.size <= limit) return all
            return all.subList(all.size - limit, all.size)
        }
    }

    /** 订阅实时日志流;返回退订函数。 */
    fun subscribe(listener: (Entry) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun levelLetter(level: String): String = when (level) {
        "error" -> "E"; "warn" -> "W"; "debug" -> "D"; else -> "I"
    }

    /** 预编译正则统一打码 URL userinfo、Bearer/Basic 凭据以及常见敏感键值。 */
    private fun mask(s: String): String {
        if (s.isEmpty()) return s
        var out = URL_USERINFO_REGEX.replace(s) { m -> "${m.groupValues[1]}://***@" }
        out = KV_SECRET_REGEX.replace(out) { m ->
            val prefix = m.groupValues[1]
            val scheme = m.groupValues[2]
            if (scheme.isNotEmpty()) "$prefix$scheme ***" else "$prefix***"
        }
        out = AUTH_SCHEME_REGEX.replace(out) { m -> "${m.groupValues[1]} ***" }
        return out
    }
}

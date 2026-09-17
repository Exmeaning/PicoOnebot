package pico.onebot.media

import android.util.Base64
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * OneBot 的 `file` 参数落成本地文件,以及反过来把本地文件暴露成可访问的 URL。
 *
 * 上游框架给的 `file` 有四种形态,**必须全支持**,否则大部分现成插件都发不出图:
 * `base64://…` / `http(s)://…` / `file:///…` / 裸绝对路径。
 *
 * 反方向同样关键:内核下下来的媒体是**安卓容器里的本地路径**,对跑在另一台机器上的
 * 框架毫无意义。所以每个落地文件都登记一个 token,由内置 HTTP 服务 `/media/<token>`
 * 提供字节流 —— 上游拿到的才是真能下载的 URL。
 */
object MediaFiles {

    private const val MAX_DOWNLOAD = 100L * 1024 * 1024

    private val tokens = ConcurrentHashMap<String, File>()
    private val resolved = AtomicLong()
    private val fetched = AtomicLong()

    val inboxDir: File get() = File(Config.dataDir, "media/in").apply { mkdirs() }
    val outboxDir: File get() = File(Config.dataDir, "media/out").apply { mkdirs() }

    class ResolveError(msg: String) : RuntimeException(msg)

    // ---------------- OneBot file 参数 → 本地文件 ----------------

    fun resolve(spec: String?, hintName: String = ""): File {
        val s = (spec ?: "").trim()
        if (s.isEmpty()) throw ResolveError("file 参数为空")
        resolved.incrementAndGet()
        return when {
            s.startsWith("base64://") -> fromBase64(s.substring(9), hintName)
            s.startsWith("http://") || s.startsWith("https://") -> fromUrl(s, hintName)
            s.startsWith("file://") -> localOrDie(File(URL(s).path))
            else -> localOrDie(File(s))
        }
    }

    private fun localOrDie(f: File): File {
        if (!f.isFile) throw ResolveError("本地文件不存在: " + f.absolutePath)
        if (!f.canRead()) throw ResolveError("本地文件不可读(安卓沙箱?): " + f.absolutePath)
        return f
    }

    private fun fromBase64(data: String, hintName: String): File {
        val payload = data.substringAfter("base64,", data)
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (t: Throwable) {
            throw ResolveError("base64 解码失败: " + t.message)
        }
        if (bytes.isEmpty()) throw ResolveError("base64 解出来是空的")
        val f = File(outboxDir, "b64-" + md5Bytes(bytes) + extOf(hintName, sniffExt(bytes)))
        if (!f.isFile || f.length() != bytes.size.toLong()) {
            FileOutputStream(f).use { it.write(bytes) }
        }
        return f
    }

    private fun fromUrl(url: String, hintName: String): File {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            // 不少图床对默认 UA 直接 403
            setRequestProperty("User-Agent", "Mozilla/5.0 PicoOnebot")
        }
        try {
            val code = conn.responseCode
            if (code / 100 != 2) throw ResolveError("下载失败 HTTP " + code + ": " + url)
            val tmp = File(outboxDir, "dl-" + System.nanoTime() + ".part")
            val n = conn.inputStream.use { ins -> FileOutputStream(tmp).use { copyLimited(ins, it) } }
            if (n <= 0L) {
                tmp.delete()
                throw ResolveError("下载到 0 字节: " + url)
            }
            val md5 = md5File(tmp)
            val ext = extOf(hintName, extFromUrl(url).ifEmpty { sniffExt(head(tmp)) })
            val dst = File(outboxDir, "url-" + md5 + ext)
            if (dst.isFile) {
                tmp.delete()
            } else if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            fetched.incrementAndGet()
            return dst
        } finally {
            conn.disconnect()
        }
    }

    private fun copyLimited(ins: InputStream, out: FileOutputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_DOWNLOAD) throw ResolveError("文件超过 " + (MAX_DOWNLOAD / 1048576) + "MB 上限")
            out.write(buf, 0, n)
        }
        return total
    }

    // ---------------- 本地文件 → 可访问 URL ----------------

    /** @return `/media/<token>`,由 HttpServer 提供;调用方拼上 host:port。 */
    fun publish(f: File): String {
        val token = md5Str(f.absolutePath) + "-" + f.length()
        tokens[token] = f
        return "/media/" + token
    }

    fun lookup(token: String): File? = tokens[token]?.takeIf { it.isFile }

    // ---------------- 工具 ----------------

    fun md5File(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun md5Bytes(b: ByteArray): String = hex(MessageDigest.getInstance("MD5").digest(b))

    private fun md5Str(s: String): String = md5Bytes(s.toByteArray(Charsets.UTF_8))

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    fun copyTo(src: File, dstPath: String): Boolean = try {
        val dst = File(dstPath)
        if (dst.absolutePath == src.absolutePath) {
            true
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            true
        }
    } catch (t: Throwable) {
        PicoLog.w("copy to kernel path failed: " + dstPath, t)
        false
    }

    fun head(f: File, n: Int = 16): ByteArray = try {
        f.inputStream().use { ins ->
            val buf = ByteArray(n)
            val read = ins.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }
    } catch (t: Throwable) {
        ByteArray(0)
    }

    private fun extFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || name.length - dot > 6) return ""
        return name.substring(dot).lowercase()
    }

    private fun extOf(hintName: String, fallback: String): String {
        val dot = hintName.lastIndexOf('.')
        if (dot > 0 && hintName.length - dot <= 6) return hintName.substring(dot).lowercase()
        return fallback
    }

    private fun b(c: Char): Byte = c.code.toByte()

    /** 按文件头认类型 —— 上游经常不给扩展名,而内核要靠它决定 picType / formatType。 */
    fun sniffExt(x: ByteArray): String = when {
        x.size >= 3 && x[0] == 0xFF.toByte() && x[1] == 0xD8.toByte() -> ".jpg"
        x.size >= 8 && x[0] == 0x89.toByte() && x[1] == b('P') -> ".png"
        x.size >= 6 && x[0] == b('G') && x[1] == b('I') && x[2] == b('F') -> ".gif"
        x.size >= 12 && x[8] == b('W') && x[9] == b('E') && x[10] == b('B') -> ".webp"
        x.size >= 12 && x[4] == b('f') && x[5] == b('t') && x[6] == b('y') -> ".mp4"
        x.size >= 2 && x[0] == 0x02.toByte() && x[1] == b('#') -> ".silk"
        x.size >= 9 && x[0] == b('#') && x[1] == b('!') && x[2] == b('S') -> ".silk"
        x.size >= 6 && x[0] == b('#') && x[1] == b('!') && x[2] == b('A') -> ".amr"
        x.size >= 3 && x[0] == b('I') && x[1] == b('D') && x[2] == b('3') -> ".mp3"
        x.size >= 2 && x[0] == 0xFF.toByte() && (x[1].toInt() and 0xE0) == 0xE0 -> ".mp3"
        x.size >= 4 && x[0] == b('R') && x[1] == b('I') && x[2] == b('F') -> ".wav"
        x.size >= 4 && x[0] == b('O') && x[1] == b('g') && x[2] == b('g') -> ".ogg"
        else -> ""
    }

    fun stats(): JSONObject = JSONObject()
        .put("resolved", resolved.get())
        .put("downloaded", fetched.get())
        .put("published", tokens.size)
}

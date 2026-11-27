package pico.onebot.media

import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
    private const val MAX_BASE64_DECODED = 32L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val PUBLISH_CAP = 2048
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 60000

    private const val OUTBOX_MAX_BYTES = 256L * 1024 * 1024
    private const val OUTBOX_TARGET_BYTES = 192L * 1024 * 1024
    private const val OUTBOX_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val OUTBOX_TEMP_MAX_AGE_MS = 10L * 60L * 1000L
    private const val OUTBOX_RECENT_PROTECT_MS = 60L * 1000L
    private const val OUTBOX_PRUNE_INTERVAL_NS = 5L * 60L * 1_000_000_000L
    private const val OUTBOX_PRUNE_WRITE_BYTES = 32L * 1024 * 1024

    private val SAFE_EXT = Regex("^\\.[a-z0-9]{1,5}$")
    private val GENERIC_EXTS = setOf(".tmp", ".dat", ".bin", ".part", ".bak", ".cache", ".file", ".raw")
    private val IPV4_LITERAL_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val EXTERNAL_PICO_REGEX = Regex("^/storage/emulated/[^/]+/pico-onebot(?:/.*)?$")

    private val BLOCKED_SYSTEM_PREFIXES = arrayOf(
        "/proc", "/sys", "/etc", "/dev",
        "/system", "/system/etc", "/vendor", "/product", "/apex", "/oem", "/odm",
        "/data/system", "/data/misc", "/data/local",
        "/data/pico", "/data/anr", "/data/tombstones", "/data/property",
        "/root", "/sbin", "/acct", "/config"
    )

    private val BLOCKED_APP_SEGMENTS = setOf(
        "databases", "shared_prefs", "pico-onebot",
        "msf", "msf_core", "wtlogin", "nt_db", "mmkv",
        "qimei", "beacon", "turing", "code_cache", "no_backup",
        "lib", "app_webview", "cookies"
    )

    private val BLOCKED_APP_EXTENSIONS = arrayOf(
        ".db", ".db-wal", ".db-shm", ".db-journal",
        ".sqlite", ".sqlite3", ".key", ".pem", ".jks", ".p12", ".keystore"
    )

    private val ALLOWED_APP_MEDIA_PREFIXES = arrayOf(
        "cache/",
        "files/tencent/",
        "files/nt_qq/nt_data/",
        "files/nt_qq/nt_temp/",
        "files/nt_data/",
        "files/nt_temp/",
        "files/chatpic/",
        "files/ptt/",
        "files/shortvideo/",
        "files/media/",
        "files/richmedia/",
        "files/file_recv/",
        "files/image/",
        "files/video/",
        "files/audio/",
        "files/qq_images/",
        "files/qqfile_recv/"
    )

    private val NT_QQ_MEDIA_SEGMENTS = setOf(
        "nt_data", "nt_temp", "pic", "ptt", "video", "file", "emoji", "thumb", "ori"
    )

    /**
     * token -> 文件,**按插入顺序**淘汰。
     *
     * 必须是 `LinkedHashMap` 而不是 `ConcurrentHashMap`:后者的迭代顺序是无序的,
     * 取 `keys.iterator().next()` 可能正好挑中刚刚生成的 token 自己删掉 → 上游拿到
     * URL 立刻 404。容量只有 2048,访问频度是"每个文件发布一次",加锁足够。
     */
    private val tokens = LinkedHashMap<String, File>()
    private val tokensLock = Any()
    private val activeOutboxLock = Any()
    private val activeOutboxFiles = HashMap<String, Int>()
    private val outboxPruneLock = Any()
    @Volatile
    private var lastOutboxPruneNs = 0L
    private val outboxWrittenBytes = AtomicLong()
    private val resolved = AtomicLong()
    private val fetched = AtomicLong()

    val inboxDir: File get() = File(Config.dataDir, "media/in").apply { mkdirs() }
    val outboxDir: File get() = File(Config.dataDir, "media/out").apply { mkdirs() }

    class ResolveError(msg: String) : RuntimeException(msg)

    internal fun pinOutboxFile(file: File) {
        val path = file.absolutePath
        synchronized(activeOutboxLock) {
            activeOutboxFiles[path] = (activeOutboxFiles[path] ?: 0) + 1
        }
    }

    internal fun unpinOutboxFile(file: File) {
        val path = file.absolutePath
        synchronized(activeOutboxLock) {
            val cur = activeOutboxFiles[path] ?: return
            if (cur <= 1) activeOutboxFiles.remove(path) else activeOutboxFiles[path] = cur - 1
        }
    }

    private fun isPinnedOutboxPath(path: String): Boolean {
        val active = synchronized(activeOutboxLock) { activeOutboxFiles.containsKey(path) }
        if (active) return true
        return synchronized(tokensLock) { tokens.values.any { it.absolutePath == path } }
    }

    // ---------------- OneBot file 参数 → 本地文件 ----------------

    fun resolve(spec: String?, hintName: String = ""): File {
        val s = (spec ?: "").trim()
        if (s.isEmpty()) throw ResolveError("file 参数为空")
        resolved.incrementAndGet()
        return when {
            s.startsWith("base64://", ignoreCase = true) -> fromBase64(s.substring(9), hintName)
            s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true) ->
                fromUrl(s, hintName)
            s.startsWith("file://", ignoreCase = true) -> localOrDie(parseFileUri(s))
            else -> localOrDie(File(s))
        }
    }

    private fun parseFileUri(spec: String): File {
        val rawPath = try {
            val uri = URI(spec)
            if (!uri.scheme.equals("file", ignoreCase = true)) {
                throw ResolveError("不支持的 file URI scheme: " + uri.scheme)
            }
            val auth = uri.authority
            if (!auth.isNullOrEmpty() && !auth.equals("localhost", ignoreCase = true)) {
                throw ResolveError("file URI 不支持远端主机: " + auth)
            }
            uri.rawPath ?: throw ResolveError("file URI 缺少路径")
        } catch (e: ResolveError) {
            throw e
        } catch (_: Throwable) {
            // 容忍含未编码空格或中文的 file:///... 写法，但仍拒绝对端主机名
            var rest = spec.substring(7).substringBefore('?').substringBefore('#')
            if (rest.startsWith("localhost/", ignoreCase = true)) {
                rest = rest.substring("localhost".length)
            }
            if (!rest.startsWith("/")) {
                throw ResolveError("file URI 缺少本地根路径: " + spec)
            }
            rest
        }
        val decoded = percentDecodePath(rawPath)
        if (decoded.isEmpty() || decoded.contains('\u0000')) {
            throw ResolveError("file URI 路径非法")
        }
        return File(decoded)
    }

    /** 仅解码 RFC 3986 的 `%XX` 字节序列（UTF-8），整段编码非转义切片以保留非 BMP UTF-16 代理对，不把 `+` 错改为空格。 */
    private fun percentDecodePath(raw: String): String {
        if (!raw.contains('%')) return raw
        val out = ByteArrayOutputStream(raw.length)
        var litStart = 0
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%' && i + 2 < raw.length) {
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    if (i > litStart) {
                        out.write(raw.substring(litStart, i).toByteArray(Charsets.UTF_8))
                    }
                    out.write((hi shl 4) or lo)
                    i += 3
                    litStart = i
                    continue
                }
            }
            i++
        }
        if (litStart < raw.length) {
            out.write(raw.substring(litStart).toByteArray(Charsets.UTF_8))
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun localOrDie(f: File): File {
        if (f.path.contains('\u0000')) throw ResolveError("本地路径包含非法字符")
        val lexical = normalizeLexicalPath(f.absolutePath)
        val canon = try {
            f.canonicalFile
        } catch (t: Throwable) {
            throw ResolveError("无法解析本地路径: " + f.path)
        }
        if (!isAllowedLocalFile(lexical, canon)) {
            throw ResolveError("拒绝访问受保护的本地路径: " + f.path)
        }
        if (!canon.isFile) throw ResolveError("本地文件不存在: " + f.absolutePath)
        if (!canon.canRead()) throw ResolveError("本地文件不可读(安卓沙箱?): " + f.absolutePath)
        return canon
    }

    private fun normalizeLexicalPath(rawPath: String): String {
        val parts = ArrayList<String>()
        for (seg in rawPath.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    private fun isBlockedSystemPath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        for (prefix in BLOCKED_SYSTEM_PREFIXES) {
            if (isPathWithin(path, prefix) || isPathWithin(lower, prefix)) return true
        }
        return false
    }

    private fun isBlockedPicoConfigPath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        if (lower.contains("/pico-onebot/") || lower.endsWith("/pico-onebot")) return true
        if (isPathWithin(lower, "/sdcard/pico-onebot")) return true
        if (EXTERNAL_PICO_REGEX.matches(lower)) return true
        return false
    }

    private fun isAllowedLocalFile(lexicalPath: String, canon: File): Boolean {
        val p = canon.path
        // 规范化前后均检查系统敏感前缀，防止 /etc -> /system/etc 等软链绕过
        if (isBlockedSystemPath(lexicalPath) || isBlockedSystemPath(p)) return false

        val outboxCanon = safeCanonical { outboxDir }
        if (outboxCanon != null && isStrictlyInside(canon, outboxCanon)) return true
        val inboxCanon = safeCanonical { inboxDir }
        if (inboxCanon != null && isStrictlyInside(canon, inboxCanon)) return true

        val dataDirCanon = safeCanonical { Config.dataDir }
        if (dataDirCanon != null && isWithin(canon, dataDirCanon)) return false
        val activeConfigDirCanon = safeCanonical { Config.activeConfigFile?.parentFile }
        if (activeConfigDirCanon != null && isWithin(canon, activeConfigDirCanon)) return false
        val sdcardPico = safeCanonical { File("/sdcard/pico-onebot") }
        if (sdcardPico != null) {
            val sdLower = sdcardPico.path.lowercase(Locale.ROOT)
            if (isPathWithin(p.lowercase(Locale.ROOT), sdLower) ||
                isPathWithin(lexicalPath.lowercase(Locale.ROOT), sdLower)
            ) {
                return false
            }
        }
        if (isBlockedPicoConfigPath(lexicalPath) || isBlockedPicoConfigPath(p)) return false

        if (isPathWithin(p, "/data/data") || isPathWithin(p, "/data/user") || isPathWithin(p, "/data/user_de")) {
            val appRoot = dataDirCanon?.parentFile?.parentFile ?: return false
            val rel = relativeToAppRoot(canon, appRoot) ?: return false
            return isAllowedAppMediaRelPath(rel)
        }

        return true
    }

    private fun relativeToAppRoot(canon: File, appRoot: File): String? {
        if (isStrictlyInside(canon, appRoot)) {
            return canon.path.substring(appRoot.path.trimEnd('/').length + 1)
        }
        val pkg = appRoot.name
        if (pkg.isNotEmpty() && appRoot.path.startsWith("/data/user/0/")) {
            val altPrefix = "/data/data/$pkg/"
            if (canon.path.startsWith(altPrefix)) {
                return canon.path.substring(altPrefix.length)
            }
        }
        return null
    }

    private fun isAllowedAppMediaRelPath(rel: String): Boolean {
        val lower = rel.lowercase(Locale.ROOT)
        val segments = lower.split('/')
        for (seg in segments) {
            if (seg in BLOCKED_APP_SEGMENTS) return false
        }
        if (BLOCKED_APP_EXTENSIONS.any { lower.endsWith(it) }) return false
        if (ALLOWED_APP_MEDIA_PREFIXES.any { lower.startsWith(it) }) return true
        if (lower.startsWith("files/nt_qq/") && segments.any { it in NT_QQ_MEDIA_SEGMENTS }) return true
        return false
    }

    private fun safeCanonical(supplier: () -> File?): File? = try {
        supplier()?.canonicalFile
    } catch (_: Throwable) {
        null
    }

    private fun isWithin(child: File, parent: File): Boolean =
        isPathWithin(child.path, parent.path)

    private fun isStrictlyInside(child: File, parent: File): Boolean {
        val pp = parent.path.trimEnd('/')
        return child.path.startsWith("$pp/")
    }

    private fun isPathWithin(canonPath: String, prefix: String): Boolean {
        val p = prefix.trimEnd('/')
        return canonPath == p || canonPath.startsWith("$p/")
    }

    private fun fromBase64(data: String, hintName: String): File {
        val payload = data.substringAfter("base64,", data)
        val maxEncoded = ((MAX_BASE64_DECODED + 2L) / 3L) * 4L
        if (payload.length.toLong() > maxEncoded) {
            var nonWs = 0L
            for (i in 0 until payload.length) {
                if (!payload[i].isWhitespace()) {
                    nonWs++
                    if (nonWs > maxEncoded) {
                        throw ResolveError("base64 数据超过 " + (MAX_BASE64_DECODED / 1048576) + "MB 上限")
                    }
                }
            }
        }
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (t: Throwable) {
            throw ResolveError("base64 解码失败: " + t.message)
        }
        if (bytes.isEmpty()) throw ResolveError("base64 解出来是空的")
        if (bytes.size.toLong() > MAX_BASE64_DECODED) {
            throw ResolveError("base64 解码后超过 " + (MAX_BASE64_DECODED / 1048576) + "MB 上限")
        }
        val dir = outboxDir
        val dst = File(dir, "b64-" + md5Bytes(bytes) + extOf(hintName, sniffExt(bytes)))
        pinOutboxFile(dst)
        try {
            if (!dst.isFile || dst.length() != bytes.size.toLong()) {
                val tmp = File.createTempFile("b64-", ".part", dir)
                pinOutboxFile(tmp)
                try {
                    FileOutputStream(tmp).use { it.write(bytes) }
                    if (!tmp.renameTo(dst) && (!dst.isFile || dst.length() != bytes.size.toLong())) {
                        throw ResolveError("写入 base64 缓存文件失败: " + dst.name)
                    }
                    outboxWrittenBytes.addAndGet(bytes.size.toLong())
                } finally {
                    unpinOutboxFile(tmp)
                    tmp.delete()
                }
            } else {
                dst.setLastModified(System.currentTimeMillis())
            }
            maybePruneOutbox(dst)
            return dst
        } finally {
            unpinOutboxFile(dst)
        }
    }

    private fun validateUrlCharacters(raw: String, label: String) {
        if (raw.isEmpty()) throw ResolveError("$label 为空")
        for (i in 0 until raw.length) {
            val ch = raw[i]
            val code = ch.code
            if (code <= 0x1F || code == 0x7F) {
                throw ResolveError("$label 包含非法控制字符")
            }
            if (ch == '\\') {
                throw ResolveError("$label 包含非法反斜杠")
            }
        }
    }

    private fun fromUrl(url: String, hintName: String): File {
        var currentUrl = url
        var hops = 0
        while (true) {
            validateUrlCharacters(currentUrl, "URL")
            val parsed = try {
                URL(currentUrl)
            } catch (_: Throwable) {
                throw ResolveError("无效的 URL: " + currentUrl)
            }
            val authority = parsed.authority
            if (!authority.isNullOrEmpty()) {
                validateUrlCharacters(authority, "URL authority")
            }
            val scheme = parsed.protocol.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                throw ResolveError("仅支持 http/https URL: " + scheme)
            }
            if (parsed.port != -1 && parsed.port !in 1..65535) {
                throw ResolveError("无效的端口: " + parsed.port)
            }
            val cleanHost = (parsed.host?.trim() ?: "").removePrefix("[").removeSuffix("]")
            val addrs = resolveAndValidateRemoteHost(cleanHost)

            val (conn, code) = connectPinned(parsed, scheme, cleanHost, addrs)
            try {
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) {
                        throw ResolveError("下载重定向缺少 Location (HTTP $code): " + currentUrl)
                    }
                    validateUrlCharacters(loc, "重定向 Location")
                    if (hops >= MAX_REDIRECTS) {
                        throw ResolveError("下载重定向次数超过 $MAX_REDIRECTS 次限制")
                    }
                    currentUrl = try {
                        URL(parsed, loc.trim()).toString()
                    } catch (_: Throwable) {
                        throw ResolveError("无效的重定向目标: " + loc)
                    }
                    hops++
                    continue
                }
                if (code / 100 != 2) throw ResolveError("下载失败 HTTP " + code + ": " + currentUrl)

                val dir = outboxDir
                val tmp = File.createTempFile("dl-", ".part", dir)
                pinOutboxFile(tmp)
                try {
                    val n = conn.inputStream.use { ins -> FileOutputStream(tmp).use { copyLimited(ins, it) } }
                    if (n <= 0L) {
                        throw ResolveError("下载到 0 字节: " + currentUrl)
                    }
                    val md5 = md5File(tmp)
                    val sniffed = sniffExt(head(tmp))
                    val urlExt = extFromUrl(currentUrl)
                    val fallbackExt = if (urlExt.isNotEmpty() && urlExt !in GENERIC_EXTS) urlExt else sniffed.ifEmpty { urlExt }
                    val ext = extOf(hintName, fallbackExt)
                    val dst = File(dir, "url-" + md5 + ext)
                    pinOutboxFile(dst)
                    try {
                        if (dst.isFile && dst.length() == n) {
                            dst.setLastModified(System.currentTimeMillis())
                        } else if (!tmp.renameTo(dst) && (!dst.isFile || dst.length() != n)) {
                            throw ResolveError("写入下载缓存文件失败: " + dst.name)
                        } else {
                            outboxWrittenBytes.addAndGet(n)
                        }
                        maybePruneOutbox(dst)
                        fetched.incrementAndGet()
                        return dst
                    } finally {
                        unpinOutboxFile(dst)
                    }
                } finally {
                    unpinOutboxFile(tmp)
                    tmp.delete()
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 使用已校验的 [InetAddress] 建立连接，彻底消除 DNS Rebinding TOCTOU：
     * - HTTP：直连 IP 字面量 URL 并显式设置 `Host` 请求头；
     * - HTTPS：直连 IP 字面量 URL + 显式 `Host` 头 + 自定义 [PinnedSSLSocketFactory]（固定连接到已校验 IP
     *   并以原始主机名发送 TLS SNI）+ 标准默认 [HostnameVerifier] 对原始主机名校验证书。
     */
    private fun connectPinned(
        parsed: URL,
        scheme: String,
        cleanHost: String,
        addrs: Array<InetAddress>
    ): Pair<HttpURLConnection, Int> {
        val formattedOriginalHost = if (cleanHost.contains(':')) "[$cleanHost]" else cleanHost
        val effectivePort = if (parsed.port != -1) parsed.port else parsed.defaultPort
        val hostHeader = if (parsed.port != -1 && parsed.port != parsed.defaultPort) {
            "$formattedOriginalHost:${parsed.port}"
        } else {
            formattedOriginalHost
        }

        var lastError: IOException? = null
        for (idx in addrs.indices) {
            val addr = addrs[idx]
            val rawIp = addr.hostAddress?.substringBefore('%')
                ?: throw ResolveError("无法格式化目标 IP 地址: " + cleanHost)
            val ipHost = if (rawIp.contains(':')) "[$rawIp]" else rawIp
            val pinnedUrl = URL(scheme, ipHost, parsed.port, parsed.file)
            val conn = (pinnedUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Host", hostHeader)
                // 不少图床对默认 UA 直接 403
                setRequestProperty("User-Agent", "Mozilla/5.0 PicoOnebot")
            }
            if (conn is HttpsURLConnection) {
                val baseFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
                conn.sslSocketFactory = PinnedSSLSocketFactory(
                    delegate = baseFactory,
                    originalHost = cleanHost,
                    pinnedAddr = addr,
                    defaultPort = effectivePort,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS
                )
                val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                conn.hostnameVerifier = HostnameVerifier { _, session ->
                    defaultVerifier.verify(cleanHost, session)
                }
            }
            try {
                val code = conn.responseCode
                return conn to code
            } catch (e: IOException) {
                conn.disconnect()
                lastError = e
                if (idx + 1 >= addrs.size) {
                    throw ResolveError("连接失败 ($cleanHost): " + (e.message ?: e.javaClass.simpleName))
                }
            } catch (t: Throwable) {
                conn.disconnect()
                throw t
            }
        }
        throw ResolveError("连接失败 ($cleanHost): " + (lastError?.message ?: "无可用地址"))
    }

    /**
     * 每跳解析一次 DNS 并校验所有返回地址，拦截回环/链路本地(含云元数据)/阿里云与 AWS IPv6 IMDS/未指定/多播地址；
     * 默认保留 RFC1918 局域网/Docker 网段，避免破坏自托管局域网 Bot 框架的图片 URL。
     */
    private fun resolveAndValidateRemoteHost(cleanHost: String): Array<InetAddress> {
        if (cleanHost.isEmpty()) throw ResolveError("URL 缺少主机名")
        val addrs = try {
            InetAddress.getAllByName(cleanHost)
        } catch (t: Throwable) {
            throw ResolveError("DNS 解析失败: " + cleanHost + " (" + (t.message ?: t.javaClass.simpleName) + ")")
        }
        if (addrs.isEmpty()) throw ResolveError("DNS 解析无结果: " + cleanHost)
        for (addr in addrs) {
            if (isBlockedAddress(addr)) {
                throw ResolveError("拒绝访问受限目标地址: " + cleanHost)
            }
        }
        return addrs
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || IPV4_LITERAL_REGEX.matches(host)

    private fun applySni(socket: SSLSocket, sniHost: String?) {
        if (sniHost.isNullOrEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Api24Tls.setSni(socket, sniHost)
        } else {
            try {
                socket.javaClass.getMethod("setHostname", String::class.java).invoke(socket, sniHost)
            } catch (_: Throwable) {
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private object Api24Tls {
        fun setSni(socket: SSLSocket, host: String) {
            try {
                val params = socket.sslParameters
                params.serverNames = listOf(SNIHostName(host))
                socket.sslParameters = params
            } catch (_: Throwable) {
            }
        }
    }

    private class PinnedSSLSocketFactory(
        private val delegate: SSLSocketFactory,
        private val originalHost: String,
        private val pinnedAddr: InetAddress,
        private val defaultPort: Int,
        private val connectTimeoutMs: Int
    ) : SSLSocketFactory() {

        private val sniHost: String? = if (isIpLiteral(originalHost)) null else originalHost

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(): Socket {
            val ssl = delegate.createSocket() as SSLSocket
            applySni(ssl, sniHost)
            return PinnedSniSSLSocket(ssl, sniHost, pinnedAddr, defaultPort)
        }

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            if (s.inetAddress == pinnedAddr) {
                return wrapConnected(s, port, autoClose)
            }
            if (autoClose) {
                try {
                    s.close()
                } catch (_: Throwable) {
                }
            }
            return connectAndWrap(port)
        }

        override fun createSocket(host: String, port: Int): Socket = connectAndWrap(port)

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            connectAndWrap(port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket = connectAndWrap(port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = connectAndWrap(port, localAddress, localPort)

        private fun connectAndWrap(port: Int, localAddr: InetAddress? = null, localPort: Int = 0): Socket {
            val raw = Socket()
            try {
                if (localAddr != null) {
                    raw.bind(InetSocketAddress(localAddr, localPort))
                }
                raw.connect(InetSocketAddress(pinnedAddr, port), connectTimeoutMs)
                return wrapConnected(raw, port, autoClose = true)
            } catch (t: Throwable) {
                try {
                    raw.close()
                } catch (_: Throwable) {
                }
                throw t
            }
        }

        private fun wrapConnected(raw: Socket, port: Int, autoClose: Boolean): Socket {
            val ssl = delegate.createSocket(raw, originalHost, port, autoClose) as SSLSocket
            applySni(ssl, sniHost)
            return PinnedSniSSLSocket(ssl, sniHost, pinnedAddr, port)
        }
    }

    /**
     * 代理底层 [SSLSocket] 并在握手前强制保持原始主机名 SNI，防止 Android 内置 OkHttp
     * 用直连 IP 字面量覆盖 `setHostname`。
     */
    private class PinnedSniSSLSocket(
        private val delegate: SSLSocket,
        private val sniHost: String?,
        private val pinnedAddr: InetAddress,
        private val defaultPort: Int
    ) : SSLSocket() {

        override fun startHandshake() {
            applySni(delegate, sniHost)
            delegate.startHandshake()
        }

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun getEnabledCipherSuites(): Array<String> = delegate.enabledCipherSuites
        override fun setEnabledCipherSuites(suites: Array<String>?) { delegate.enabledCipherSuites = suites }
        override fun getSupportedProtocols(): Array<String> = delegate.supportedProtocols
        override fun getEnabledProtocols(): Array<String> = delegate.enabledProtocols
        override fun setEnabledProtocols(protocols: Array<String>?) { delegate.enabledProtocols = protocols }
        override fun getSession(): SSLSession = delegate.session
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) {
            delegate.addHandshakeCompletedListener(listener)
        }
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) {
            delegate.removeHandshakeCompletedListener(listener)
        }
        override fun setUseClientMode(mode: Boolean) { delegate.useClientMode = mode }
        override fun getUseClientMode(): Boolean = delegate.useClientMode
        override fun setNeedClientAuth(need: Boolean) { delegate.needClientAuth = need }
        override fun getNeedClientAuth(): Boolean = delegate.needClientAuth
        override fun setWantClientAuth(want: Boolean) { delegate.wantClientAuth = want }
        override fun getWantClientAuth(): Boolean = delegate.wantClientAuth
        override fun setEnableSessionCreation(flag: Boolean) { delegate.enableSessionCreation = flag }
        override fun getEnableSessionCreation(): Boolean = delegate.enableSessionCreation
        override fun getSSLParameters(): SSLParameters = delegate.sslParameters
        override fun setSSLParameters(params: SSLParameters?) {
            delegate.sslParameters = params
            applySni(delegate, sniHost)
        }

        override fun connect(endpoint: SocketAddress?) {
            val port = (endpoint as? InetSocketAddress)?.port ?: defaultPort
            delegate.connect(InetSocketAddress(pinnedAddr, port))
        }
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val port = (endpoint as? InetSocketAddress)?.port ?: defaultPort
            delegate.connect(InetSocketAddress(pinnedAddr, port), timeout)
        }
        override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
        override fun getInetAddress(): InetAddress? = delegate.inetAddress
        override fun getLocalAddress(): InetAddress = delegate.localAddress
        override fun getPort(): Int = delegate.port
        override fun getLocalPort(): Int = delegate.localPort
        override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
        override fun getChannel(): SocketChannel? = delegate.channel
        override fun getInputStream(): InputStream = delegate.inputStream
        override fun getOutputStream(): OutputStream = delegate.outputStream
        override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
        override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
        override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
        override fun getSoLinger(): Int = delegate.soLinger
        override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)
        override fun setOOBInline(on: Boolean) { delegate.oobInline = on }
        override fun getOOBInline(): Boolean = delegate.oobInline
        override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
        override fun getSendBufferSize(): Int = delegate.sendBufferSize
        override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
        override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
        override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
        override fun getKeepAlive(): Boolean = delegate.keepAlive
        override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }
        override fun getTrafficClass(): Int = delegate.trafficClass
        override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
        override fun getReuseAddress(): Boolean = delegate.reuseAddress
        override fun close() = delegate.close()
        override fun shutdownInput() = delegate.shutdownInput()
        override fun shutdownOutput() = delegate.shutdownOutput()
        override fun toString(): String = delegate.toString()
        override fun isConnected(): Boolean = delegate.isConnected
        override fun isBound(): Boolean = delegate.isBound
        override fun isClosed(): Boolean = delegate.isClosed
        override fun isInputShutdown(): Boolean = delegate.isInputShutdown
        override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
        override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) {
            delegate.setPerformancePreferences(connectionTime, latency, bandwidth)
        }
    }

    private fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) {
            return true
        }
        val b = addr.address
        if (b.size == 4) {
            return isBlockedIpv4(
                b[0].toInt() and 0xFF,
                b[1].toInt() and 0xFF,
                b[2].toInt() and 0xFF,
                b[3].toInt() and 0xFF
            )
        }
        if (b.size == 16) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            // fe80::/10 link-local
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
            // ff00::/8 multicast
            if (b0 == 0xFF) return true
            // fd00:ec2::254 (AWS IPv6 IMDS)
            if (b0 == 0xFD && b1 == 0x00 &&
                (b[2].toInt() and 0xFF) == 0x0E && (b[3].toInt() and 0xFF) == 0xC2 &&
                (4 until 14).all { b[it].toInt() == 0 } &&
                (b[14].toInt() and 0xFF) == 0x02 && (b[15].toInt() and 0xFF) == 0x54
            ) {
                return true
            }
            // :: (unspecified) and ::1 (loopback)
            var first10Zero = true
            for (i in 0 until 10) {
                if (b[i].toInt() != 0) {
                    first10Zero = false
                    break
                }
            }
            if (first10Zero) {
                val b10 = b[10].toInt() and 0xFF
                val b11 = b[11].toInt() and 0xFF
                val b12 = b[12].toInt() and 0xFF
                val b13 = b[13].toInt() and 0xFF
                val b14 = b[14].toInt() and 0xFF
                val b15 = b[15].toInt() and 0xFF
                // ::ffff:a.b.c.d (IPv4-mapped IPv6)
                if (b10 == 0xFF && b11 == 0xFF) {
                    return isBlockedIpv4(b12, b13, b14, b15)
                }
                // :: / ::1 / ::a.b.c.d (IPv4-compatible IPv6)
                if (b10 == 0 && b11 == 0) {
                    if (b12 == 0 && b13 == 0 && b14 == 0 && (b15 == 0 || b15 == 1)) return true
                    return isBlockedIpv4(b12, b13, b14, b15)
                }
            }
            // ::ffff:0:a.b.c.d (IPv4-translated IPv6)
            var first8Zero = true
            for (i in 0 until 8) {
                if (b[i].toInt() != 0) {
                    first8Zero = false
                    break
                }
            }
            if (first8Zero &&
                (b[8].toInt() and 0xFF) == 0xFF && (b[9].toInt() and 0xFF) == 0xFF &&
                b[10].toInt() == 0 && b[11].toInt() == 0
            ) {
                return isBlockedIpv4(
                    b[12].toInt() and 0xFF,
                    b[13].toInt() and 0xFF,
                    b[14].toInt() and 0xFF,
                    b[15].toInt() and 0xFF
                )
            }
            // 64:ff9b::/96 (NAT64 well-known prefix)
            if (b0 == 0x00 && b1 == 0x64 &&
                (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B &&
                (4 until 12).all { b[it].toInt() == 0 }
            ) {
                return isBlockedIpv4(
                    b[12].toInt() and 0xFF,
                    b[13].toInt() and 0xFF,
                    b[14].toInt() and 0xFF,
                    b[15].toInt() and 0xFF
                )
            }
        }
        return false
    }

    private fun isBlockedIpv4(b0: Int, b1: Int, b2: Int, b3: Int): Boolean {
        if (b0 == 127) return true // 127.0.0.0/8 loopback
        if (b0 == 0) return true // 0.0.0.0/8 unspecified
        if (b0 == 169 && b1 == 254) return true // 169.254.0.0/16 link-local & cloud metadata
        if (b0 == 100 && b1 == 100 && b2 == 100 && b3 == 200) return true // Alibaba Cloud IMDS
        if ((b0 and 0xF0) == 0xE0) return true // 224.0.0.0/4 multicast
        if (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) return true // limited broadcast
        return false
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

    private fun maybePruneOutbox(exclude: File? = null) {
        val nowNs = System.nanoTime()
        val prevNs = lastOutboxPruneNs
        val elapsedNs = if (prevNs == 0L) Long.MAX_VALUE else (nowNs - prevNs).coerceAtLeast(0L)
        if (elapsedNs < OUTBOX_PRUNE_INTERVAL_NS && outboxWrittenBytes.get() < OUTBOX_PRUNE_WRITE_BYTES) return
        synchronized(outboxPruneLock) {
            val recheckNs = System.nanoTime()
            val curPrevNs = lastOutboxPruneNs
            val reElapsedNs = if (curPrevNs == 0L) Long.MAX_VALUE else (recheckNs - curPrevNs).coerceAtLeast(0L)
            if (reElapsedNs < OUTBOX_PRUNE_INTERVAL_NS && outboxWrittenBytes.get() < OUTBOX_PRUNE_WRITE_BYTES) return
            lastOutboxPruneNs = recheckNs
            outboxWrittenBytes.set(0L)
            try {
                pruneOutboxLocked(exclude)
            } catch (t: Throwable) {
                PicoLog.d("outbox prune failed: " + t.message)
            }
        }
    }

    private fun pruneOutboxLocked(exclude: File?) {
        val dir = outboxDir
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return

        val pinned = synchronized(tokensLock) {
            tokens.values.mapTo(HashSet(tokens.size + 8)) { it.absolutePath }
        }
        synchronized(activeOutboxLock) {
            pinned.addAll(activeOutboxFiles.keys)
        }
        exclude?.absolutePath?.let { pinned.add(it) }

        val nowMs = System.currentTimeMillis()
        val evictable = ArrayList<File>(files.size)
        var totalBytes = 0L
        for (f in files) {
            val path = f.absolutePath
            if (path in pinned) {
                totalBytes += f.length()
                continue
            }
            val ageMs = (nowMs - f.lastModified()).coerceAtLeast(0L)
            val isTemp = f.name.endsWith(".part") || f.name.startsWith("thumb-")
            val maxAge = if (isTemp) OUTBOX_TEMP_MAX_AGE_MS else OUTBOX_MAX_AGE_MS
            if (ageMs > maxAge) {
                if (!isPinnedOutboxPath(path)) {
                    f.delete()
                } else {
                    totalBytes += f.length()
                }
                continue
            }
            totalBytes += f.length()
            // 活跃临时文件（dl-*.part / b64-*.part / thumb-*.jpg）与近 60s 内写入文件不纳入高水位容量淘汰
            if (!isTemp && ageMs >= OUTBOX_RECENT_PROTECT_MS) {
                evictable.add(f)
            }
        }

        if (totalBytes <= OUTBOX_MAX_BYTES) return
        evictable.sortBy { it.lastModified() }
        for (f in evictable) {
            if (totalBytes <= OUTBOX_TARGET_BYTES) break
            if (isPinnedOutboxPath(f.absolutePath)) continue
            val len = f.length()
            if (f.delete()) {
                totalBytes -= len
            }
        }
    }

    // ---------------- 本地文件 → 可访问 URL ----------------

    private val tokenRng = java.security.SecureRandom()

    /**
     * 发布一个可下载的媒体句柄。
     *
     * token 必须是**不可预测**的能力值:早期实现用 `md5(绝对路径)+长度`,而路径形如
     * `media/out/url-<md5>.<ext>`,在开源仓库里完全可推,等于谁都能枚举下载。
     *
     * @return `/media/<token>`,由 HttpServer 提供;调用方拼上 host:port。
     */
    fun publish(f: File): String {
        val raw = ByteArray(16).also(tokenRng::nextBytes)
        val token = hex(raw)
        synchronized(tokensLock) {
            tokens[token] = f
            // 同一文件重复发布会不断堆积 token,按**插入序**截断最老的。
            // 刚插入的这条位于队尾,不会被自己淘汰 —— 这正是旧实现使用
            // ConcurrentHashMap 无序迭代时踩到的问题。
            while (tokens.size > PUBLISH_CAP) {
                val oldest = tokens.keys.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
        return "/media/" + token
    }

    fun lookup(token: String): File? =
        synchronized(tokensLock) { tokens[token] }?.takeIf { it.isFile }

    /** 切号/会话失效时清空已发布的 `/media/<token>` 映射，避免旧账号文件被跨会话读取。 */
    fun clearServed() {
        synchronized(tokensLock) {
            tokens.clear()
        }
    }

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
        val same = try {
            dst.canonicalFile == src.canonicalFile
        } catch (_: Throwable) {
            dst.absolutePath == src.absolutePath
        }
        if (same) {
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

    private fun sanitizeExt(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT)
        return if (SAFE_EXT.matches(lower)) lower else ""
    }

    private fun extFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ""
        return sanitizeExt(name.substring(dot))
    }

    private fun extOf(hintName: String, fallback: String): String {
        val fb = sanitizeExt(fallback)
        val base = hintName.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        if (dot > 0) {
            val ext = sanitizeExt(base.substring(dot))
            if (ext.isNotEmpty() && (ext !in GENERIC_EXTS || fb.isEmpty())) return ext
        }
        return fb
    }

    private fun b(c: Char): Byte = c.code.toByte()

    /** 校验 3+ 字节 ADTS AAC 帧头（12-bit 0xFFF 同步字、layer=00、非保留 profile 与有效采样率索引 0..12）。 */
    private fun isAdtsAac(x: ByteArray): Boolean {
        if (x.size < 3 || x[0] != 0xFF.toByte()) return false
        val b1 = x[1].toInt() and 0xFF
        if ((b1 and 0xF6) != 0xF0) return false
        val b2 = x[2].toInt() and 0xFF
        val profile = (b2 ushr 6) and 0x03
        val sampleRateIdx = (b2 ushr 2) and 0x0F
        return profile != 3 && sampleRateIdx in 0..12
    }

    /** 校验 3+ 字节裸 MPEG Audio Layer III (MP3) 帧头，避免把 UTF-16LE BOM (`0xFF 0xFE`) 误判为 MP3。 */
    private fun isBareMp3(x: ByteArray): Boolean {
        if (x.size < 3 || x[0] != 0xFF.toByte()) return false
        val b1 = x[1].toInt() and 0xFF
        if ((b1 and 0xE0) != 0xE0) return false
        val version = (b1 ushr 3) and 0x03
        val layer = (b1 ushr 1) and 0x03
        // version=01 保留；仅识别 Layer III (01) 作为 MP3（UTF-16LE BOM 0xFF 0xFE 的 layer=11 会被排除）
        if (version == 1 || layer != 1) return false
        val b2 = x[2].toInt() and 0xFF
        val bitrateIdx = (b2 ushr 4) and 0x0F
        val sampleRateIdx = (b2 ushr 2) and 0x03
        return bitrateIdx in 1..14 && sampleRateIdx != 3
    }

    /** 按文件头认类型 —— 上游经常不给扩展名,而内核要靠它决定 picType / formatType。 */
    fun sniffExt(x: ByteArray): String = when {
        x.size >= 3 && x[0] == 0xFF.toByte() && x[1] == 0xD8.toByte() -> ".jpg"
        x.size >= 8 && x[0] == 0x89.toByte() && x[1] == b('P') && x[2] == b('N') && x[3] == b('G') -> ".png"
        x.size >= 6 && x[0] == b('G') && x[1] == b('I') && x[2] == b('F') -> ".gif"
        x.size >= 12 && x[0] == b('R') && x[1] == b('I') && x[2] == b('F') && x[3] == b('F') &&
            x[8] == b('W') && x[9] == b('E') && x[10] == b('B') && x[11] == b('P') -> ".webp"
        x.size >= 12 && x[4] == b('f') && x[5] == b('t') && x[6] == b('y') && x[7] == b('p') -> {
            val brand = String(x, 8, 4, Charsets.US_ASCII)
            if (brand == "M4A " || brand == "M4B " || brand == "M4P ") ".m4a"
            else ".mp4"
        }
        x.size >= 2 && x[0] == 0x02.toByte() && x[1] == b('#') -> ".silk"
        x.size >= 9 && x[0] == b('#') && x[1] == b('!') && x[2] == b('S') -> ".silk"
        x.size >= 6 && x[0] == b('#') && x[1] == b('!') && x[2] == b('A') -> ".amr"
        x.size >= 3 && x[0] == b('I') && x[1] == b('D') && x[2] == b('3') -> ".mp3"
        isAdtsAac(x) -> ".aac"
        isBareMp3(x) -> ".mp3"
        x.size >= 4 && x[0] == b('R') && x[1] == b('I') && x[2] == b('F') -> ".wav"
        x.size >= 4 && x[0] == b('O') && x[1] == b('g') && x[2] == b('g') -> ".ogg"
        else -> ""
    }

    fun stats(): JSONObject = JSONObject()
        .put("resolved", resolved.get())
        .put("downloaded", fetched.get())
        .put("published", synchronized(tokensLock) { tokens.size })
}

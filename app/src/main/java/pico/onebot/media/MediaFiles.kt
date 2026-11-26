package pico.onebot.media

import android.util.Base64
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
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
    private const val MAX_BASE64_DECODED = 32L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val PUBLISH_CAP = 2048

    private const val OUTBOX_MAX_BYTES = 256L * 1024 * 1024
    private const val OUTBOX_TARGET_BYTES = 192L * 1024 * 1024
    private const val OUTBOX_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val OUTBOX_TEMP_MAX_AGE_MS = 10L * 60L * 1000L
    private const val OUTBOX_PRUNE_INTERVAL_NS = 5L * 60L * 1_000_000_000L
    private const val OUTBOX_PRUNE_WRITE_BYTES = 32L * 1024 * 1024

    private val SAFE_EXT = Regex("^\\.[a-z0-9]{1,5}$")

    private val BLOCKED_SYSTEM_PREFIXES = arrayOf(
        "/proc", "/sys", "/etc", "/dev",
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
    private val outboxPruneLock = Any()
    private var lastOutboxPruneNs = 0L
    private val outboxWrittenBytes = AtomicLong()
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

    /** 仅解码 RFC 3986 的 `%XX` 字节序列（UTF-8），不把 `+` 错改为空格。 */
    private fun percentDecodePath(raw: String): String {
        if (!raw.contains('%')) return raw
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%' && i + 2 < raw.length) {
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun localOrDie(f: File): File {
        if (f.path.contains('\u0000')) throw ResolveError("本地路径包含非法字符")
        val canon = try {
            f.canonicalFile
        } catch (t: Throwable) {
            throw ResolveError("无法解析本地路径: " + f.path)
        }
        if (!isAllowedLocalFile(canon)) {
            throw ResolveError("拒绝访问受保护的本地路径: " + f.path)
        }
        if (!canon.isFile) throw ResolveError("本地文件不存在: " + f.absolutePath)
        if (!canon.canRead()) throw ResolveError("本地文件不可读(安卓沙箱?): " + f.absolutePath)
        return canon
    }

    private fun isAllowedLocalFile(canon: File): Boolean {
        val p = canon.path
        val outboxCanon = safeCanonical { outboxDir }
        if (outboxCanon != null && isStrictlyInside(canon, outboxCanon)) return true
        val inboxCanon = safeCanonical { inboxDir }
        if (inboxCanon != null && isStrictlyInside(canon, inboxCanon)) return true

        val dataDirCanon = safeCanonical { Config.dataDir }
        if (dataDirCanon != null && isWithin(canon, dataDirCanon)) return false
        val activeConfigDirCanon = safeCanonical { Config.activeConfigFile?.parentFile }
        if (activeConfigDirCanon != null && isWithin(canon, activeConfigDirCanon)) return false
        val sdcardPico = safeCanonical { File("/sdcard/pico-onebot") }
        if (sdcardPico != null && isWithin(canon, sdcardPico)) return false
        if (p.contains("/pico-onebot/") || p.endsWith("/pico-onebot")) return false

        for (prefix in BLOCKED_SYSTEM_PREFIXES) {
            if (isPathWithin(p, prefix)) return false
        }

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
        if (!dst.isFile || dst.length() != bytes.size.toLong()) {
            val tmp = File.createTempFile("b64-", ".part", dir)
            try {
                FileOutputStream(tmp).use { it.write(bytes) }
                if (!tmp.renameTo(dst) && (!dst.isFile || dst.length() != bytes.size.toLong())) {
                    throw ResolveError("写入 base64 缓存文件失败: " + dst.name)
                }
                outboxWrittenBytes.addAndGet(bytes.size.toLong())
            } finally {
                tmp.delete()
            }
        } else {
            dst.setLastModified(System.currentTimeMillis())
        }
        maybePruneOutbox(dst)
        return dst
    }

    private fun fromUrl(url: String, hintName: String): File {
        var currentUrl = url
        var hops = 0
        while (true) {
            val parsed = try {
                URL(currentUrl)
            } catch (_: Throwable) {
                throw ResolveError("无效的 URL: " + currentUrl)
            }
            val scheme = parsed.protocol.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                throw ResolveError("仅支持 http/https URL: " + scheme)
            }
            validateRemoteHost(parsed)

            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = false
                // 不少图床对默认 UA 直接 403
                setRequestProperty("User-Agent", "Mozilla/5.0 PicoOnebot")
            }
            try {
                val code = conn.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) {
                        throw ResolveError("下载重定向缺少 Location (HTTP $code): " + currentUrl)
                    }
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
                try {
                    val n = conn.inputStream.use { ins -> FileOutputStream(tmp).use { copyLimited(ins, it) } }
                    if (n <= 0L) {
                        throw ResolveError("下载到 0 字节: " + currentUrl)
                    }
                    val md5 = md5File(tmp)
                    val ext = extOf(hintName, extFromUrl(currentUrl).ifEmpty { sniffExt(head(tmp)) })
                    val dst = File(dir, "url-" + md5 + ext)
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
                    tmp.delete()
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 每跳解析 DNS 并拦截回环/链路本地(含云元数据)/未指定/多播地址，防止 SSRF 打本机或云元数据；
     * 默认保留 RFC1918 局域网/Docker 网段，避免破坏自托管局域网 Bot 框架的图片 URL。
     */
    private fun validateRemoteHost(parsed: URL) {
        val rawHost = parsed.host?.trim() ?: ""
        val cleanHost = rawHost.removePrefix("[").removeSuffix("]")
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
        val elapsedNs = if (lastOutboxPruneNs == 0L) Long.MAX_VALUE else (nowNs - lastOutboxPruneNs).coerceAtLeast(0L)
        if (elapsedNs < OUTBOX_PRUNE_INTERVAL_NS && outboxWrittenBytes.get() < OUTBOX_PRUNE_WRITE_BYTES) return
        synchronized(outboxPruneLock) {
            val recheckNs = System.nanoTime()
            val reElapsedNs = if (lastOutboxPruneNs == 0L) Long.MAX_VALUE else (recheckNs - lastOutboxPruneNs).coerceAtLeast(0L)
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
            tokens.values.mapTo(HashSet(tokens.size)) { it.absolutePath }
        }
        exclude?.absolutePath?.let { pinned.add(it) }

        val nowMs = System.currentTimeMillis()
        val survivors = ArrayList<File>(files.size)
        var totalBytes = 0L
        for (f in files) {
            if (f.absolutePath in pinned) {
                totalBytes += f.length()
                continue
            }
            val ageMs = (nowMs - f.lastModified()).coerceAtLeast(0L)
            val isTemp = f.name.endsWith(".part") || f.name.startsWith("thumb-")
            val maxAge = if (isTemp) OUTBOX_TEMP_MAX_AGE_MS else OUTBOX_MAX_AGE_MS
            if (ageMs > maxAge) {
                f.delete()
                continue
            }
            survivors.add(f)
            totalBytes += f.length()
        }

        if (totalBytes <= OUTBOX_MAX_BYTES) return
        survivors.sortBy { it.lastModified() }
        for (f in survivors) {
            if (totalBytes <= OUTBOX_TARGET_BYTES) break
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
        val base = hintName.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        if (dot > 0) {
            val ext = sanitizeExt(base.substring(dot))
            if (ext.isNotEmpty()) return ext
        }
        return sanitizeExt(fallback)
    }

    private fun b(c: Char): Byte = c.code.toByte()

    /** 按文件头认类型 —— 上游经常不给扩展名,而内核要靠它决定 picType / formatType。 */
    fun sniffExt(x: ByteArray): String = when {
        x.size >= 3 && x[0] == 0xFF.toByte() && x[1] == 0xD8.toByte() -> ".jpg"
        x.size >= 8 && x[0] == 0x89.toByte() && x[1] == b('P') && x[2] == b('N') && x[3] == b('G') -> ".png"
        x.size >= 6 && x[0] == b('G') && x[1] == b('I') && x[2] == b('F') -> ".gif"
        x.size >= 12 && x[0] == b('R') && x[1] == b('I') && x[2] == b('F') && x[3] == b('F') &&
            x[8] == b('W') && x[9] == b('E') && x[10] == b('B') && x[11] == b('P') -> ".webp"
        x.size >= 12 && x[4] == b('f') && x[5] == b('t') && x[6] == b('y') && x[7] == b('p') -> {
            if (x[8] == b('M') && x[9] == b('4') && x[10] == b('A') && x[11] == b(' ')) ".m4a"
            else ".mp4"
        }
        x.size >= 2 && x[0] == 0x02.toByte() && x[1] == b('#') -> ".silk"
        x.size >= 9 && x[0] == b('#') && x[1] == b('!') && x[2] == b('S') -> ".silk"
        x.size >= 6 && x[0] == b('#') && x[1] == b('!') && x[2] == b('A') -> ".amr"
        x.size >= 3 && x[0] == b('I') && x[1] == b('D') && x[2] == b('3') -> ".mp3"
        // 裸 ADTS AAC: 12-bit 0xFFF 同步字 + layer=00 ((x[1] & 0xF6) == 0xF0)
        x.size >= 2 && x[0] == 0xFF.toByte() && (x[1].toInt() and 0xF6) == 0xF0 -> ".aac"
        // 裸 MPEG Audio (MP3): 11-bit 同步字 + layer != 00
        x.size >= 2 && x[0] == 0xFF.toByte() && (x[1].toInt() and 0xE0) == 0xE0 &&
            (x[1].toInt() and 0x06) != 0 -> ".mp3"
        x.size >= 4 && x[0] == b('R') && x[1] == b('I') && x[2] == b('F') -> ".wav"
        x.size >= 4 && x[0] == b('O') && x[1] == b('g') && x[2] == b('g') -> ".ogg"
        else -> ""
    }

    fun stats(): JSONObject = JSONObject()
        .put("resolved", resolved.get())
        .put("downloaded", fetched.get())
        .put("published", synchronized(tokensLock) { tokens.size })
}

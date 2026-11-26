package pico.onebot.action

import android.util.Base64
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.media.MediaDownloader
import pico.onebot.media.MediaFiles
import pico.onebot.media.MediaRefStore
import pico.onebot.msg.MsgCodec
import pico.onebot.proto.Ob
import java.io.File
import java.net.NetworkInterface

/**
 * 取回收到的媒体。
 *
 * 这里有个容易被忽略的要害:内核把文件下到**安卓容器内部的路径**,而上游框架通常跑在
 * 另一台机器上 —— 直接把 `/data/user/0/…/xxx.jpg` 返回去,对方根本打不开。所以每个
 * 落地文件都经内置 HTTP 服务发布成 `/media/<token>`,返回里同时给 `file`(本地路径)
 * 和 `url`(真能下载的地址),上游用哪个都行。
 *
 * 另一个要害是图片直链的 rkey 会过期,所以"能下载"必须以**本地落地**为准,
 * 而不是依赖消息里带的那个 `url`。
 */
object MediaActions {

    private const val MAX_BASE64_RESPONSE_BYTES = 16L * 1024 * 1024

    fun register() {
        ActionRouter.register("get_image") { p -> fetch(p, "image") }
        ActionRouter.register("get_record") { p -> fetch(p, "record") }
        ActionRouter.register("get_file") { p -> fetch(p, "file") }
        ActionRouter.register("get_media") { p -> fetch(p, "media") }
    }

    private fun fetch(p: JSONObject, kind: String): JSONObject {
        val key = listOf("file", "file_id", "id", "element_id", "md5")
            .map { MsgCodec.optNonNullString(p, it).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return Ob.failed(Ob.RC_BAD_PARAM, "需要 file / file_id / element_id")

        val ref = MediaRefStore.get(key)
            ?: return Ob.failed(
                Ob.RC_BAD_PARAM,
                "该媒体不在本实例的记录里(只有本次运行期间收到过的才查得到): $key"
            )

        val thumb = p.optBoolean("thumb", false)
        var kernelErr = ""
        var f: File? = try {
            MediaDownloader.fetch(
                ref.chatType, ref.peerUid, ref.msgId, ref.elementId, ref.localPath, thumb
            )
        } catch (t: Throwable) {
            kernelErr = t.message ?: t.javaClass.simpleName
            null
        }

        // 合并转发内部的媒体走不通内核下载(内层 msgId 不是会话里的真实消息,永不回调),
        // 但消息里带的那条 rkey 直链是能下到原文件的 —— 实测 md5 与原件一致。
        if (f == null && ref.url.isNotEmpty()) {
            f = try {
                MediaFiles.resolve(ref.url, ref.fileName)
            } catch (t: Throwable) {
                PicoLog.w("url fallback failed for " + ref.fileName, t)
                null
            }
            if (f != null) PicoLog.i("get_$kind 走直链回退: " + ref.fileName)
        }
        if (f == null) {
            return Ob.failed(
                Ob.RC_KERNEL_ERROR,
                "落地失败: " + kernelErr + (if (ref.url.isEmpty()) "(且无可用直链)" else "(直链回退也失败)")
            )
        }

        val fileLen = f.length()
        val data = JSONObject()
            .put("file", f.absolutePath)
            .put("path", f.absolutePath)
            .put("file_name", ref.fileName)
            .put("file_size", fileLen)
            .put("url", mediaUrl(MediaFiles.publish(f)))

        // 小文件默认顺手给 base64,省掉上游再发一次 HTTP;显式 base64=false 时一律不编码,
        // 显式 base64=true 也受硬上限约束,避免大文件整块读进宿主 QQ 堆内存导致 OOM。
        val defaultLimit = Config.long("media_base64_limit", 4L * 1024 * 1024)
            .coerceIn(0L, MAX_BASE64_RESPONSE_BYTES)
        val wantBase64 = if (!p.isNull("base64")) p.optBoolean("base64", false) else fileLen <= defaultLimit
        if (wantBase64) {
            if (fileLen > MAX_BASE64_RESPONSE_BYTES) {
                PicoLog.w(
                    "skip base64 for " + f.name + ": " + fileLen + "B exceeds hard ceiling " +
                        MAX_BASE64_RESPONSE_BYTES + "B"
                )
            } else {
                try {
                    data.put("base64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                } catch (t: Throwable) {
                    PicoLog.w("base64 encode failed for " + f.name, t)
                }
            }
        }
        PicoLog.i("get_$kind -> " + f.absolutePath + " " + fileLen + "B")
        return Ob.ok(data)
    }

    /**
     * 对外可达的基地址。
     *
     * 优先级:显式 `media_base_url` → 真实在听的 OneBot 监听 → 回环兜底。
     * 老实现固定用 `Config.port`(第一个正向 WS 配置项),纯反向 WS/纯 HTTP 部署下
     * 那个端口根本没人监听,返回的 URL 必然连不上。
     *
     * 监听项绑在通配地址上(`0.0.0.0` / `::`)时换成局域网 IP;绑在具体地址上时
     * 就用那个地址。
     */
    fun baseUrl(): String {
        val configured = Config.str("media_base_url", "")
        if (configured.isNotEmpty()) return configured.trimEnd('/')
        val endpoint = pico.onebot.PicoBoot.activeOnebotEndpoint()
        val port = endpoint?.second ?: Config.port
        val host = endpoint?.first?.let(::externallyReachableHost) ?: localAddress()
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://" + formattedHost + ":" + port
    }

    /** 监听地址本身能不能直接写进外链:通配地址 / 回环要替换成实际可达的 IP。 */
    private fun externallyReachableHost(bindHost: String): String {
        val h = bindHost.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || h == "0.0.0.0" || h == "::" || h == "0:0:0:0:0:0:0:0") return localAddress()
        return h
    }

    /**
     * `/media/<token>` 的完整 URL。
     *
     * `/media/<token>` 由 128 位 `SecureRandom` 能力令牌直接鉴权，不再把监听器的主
     * `serverToken` 拼进 `?access_token=`，避免跨监听端越权或泄露到日志/Referer。
     */
    private fun mediaUrl(tokenPath: String): String = baseUrl() + tokenPath

    private fun localAddress(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    val host = a.hostAddress ?: continue
                    if (a.isLoopbackAddress || host.contains(':')) continue // 跳过回环与 IPv6
                    return host
                }
            }
        } catch (t: Throwable) {
            PicoLog.d("localAddress failed: " + t.message)
        }
        return "127.0.0.1"
    }
}

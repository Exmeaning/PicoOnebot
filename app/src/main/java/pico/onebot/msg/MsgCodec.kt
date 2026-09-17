package pico.onebot.msg

import com.tencent.qqnt.kernel.nativeinterface.FaceElement
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.ReplyElement
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.NickStore
import pico.onebot.kernel.UinUidStore
import pico.onebot.media.MediaBuilder
import pico.onebot.media.MediaFiles
import pico.onebot.media.MediaRefStore

/**
 * OneBot message segment / CQ 码  ⇄  NT `MsgElement[]`。
 *
 * 发送侧支持 text / at / face / reply / image / record / video / file;
 * 媒体走 [pico.onebot.media.MediaBuilder](拷进内核缓存路径 + 填元信息,上传由内核在
 * `sendMsg` 时顺带完成,**不需要预先上传**)。剩下真做不到的段仍旧**明确报 1404**,
 * 绝不静默丢内容。
 */
object MsgCodec {

    // NT elementType
    const val T_TEXT = 1
    const val T_PIC = 2
    const val T_FILE = 3
    const val T_PTT = 4
    const val T_VIDEO = 5
    const val T_FACE = 6
    const val T_REPLY = 7
    const val T_GREY_TIP = 8
    const val T_ARK = 10
    const val T_MFACE = 11
    const val T_MULTI_FORWARD = 16

    // TextElement.atType
    const val AT_NONE = 0
    const val AT_ALL = 1
    const val AT_ONE = 2

    class UnsupportedSegment(val type: String) : RuntimeException("segment not supported yet: $type")

    // ---------------- 内核 → OneBot ----------------

    fun toSegments(elements: List<MsgElement>?, ctx: MsgRecord? = null): JSONArray {
        val arr = JSONArray()
        if (elements == null) return arr
        for (e in elements) {
            val seg = try {
                elementToSegment(e, ctx)
            } catch (t: Throwable) {
                PicoLog.w("element convert failed type=" + e.elementType, t)
                null
            }
            if (seg != null) arr.put(seg)
        }
        return arr
    }

    private fun elementToSegment(e: MsgElement, ctx: MsgRecord?): JSONObject? = when (e.elementType) {
        T_TEXT -> {
            val t = e.textElement
            when {
                t == null -> null
                t.atType == AT_ALL -> seg("at", JSONObject().put("qq", "all"))
                t.atType != AT_NONE -> {
                    val uin = if (t.atUid > 0) t.atUid else UinUidStore.uin(t.atNtUid)
                    seg("at", JSONObject().put("qq", uin.toString()).put("name", t.content ?: ""))
                }
                else -> seg("text", JSONObject().put("text", t.content ?: ""))
            }
        }

        T_FACE -> e.faceElement?.let {
            seg("face", JSONObject().put("id", it.faceIndex.toString()))
        }

        T_REPLY -> e.replyElement?.let { re ->
            // 字段名是坑:sourceMsgIdInRecords 是"记录数组内的编号",不是全局 msgId,
            // 拿它查映射表必然落空 —— 旧代码就是这么把 reply.id 打成 0 的。真正的是 replayMsgId。
            val srcMsgId = if (re.replayMsgId != 0L) re.replayMsgId else (re.sourceMsgIdInRecords ?: 0L)
            val srcSeq = re.replayMsgSeq ?: 0L
            var entry = if (srcMsgId != 0L) MsgIdStore.byMsgId(srcMsgId) else null
            // 跨设备同步过来的记录 msgId 可能对不上,seq 在同一会话内是稳的。
            if (entry == null && ctx != null) {
                entry = MsgIdStore.bySeq(ctx.chatType, ctx.peerUid ?: "", srcSeq)
            }
            // 被引用的消息早于本进程启动时,映射表里本来就没有它。但我们手上有真实
            // msgId,就地登记一个,上游才拿得到非 0 的 id,之后 get_msg 也能查回来。
            if (entry == null && srcMsgId != 0L && ctx != null) {
                entry = MsgIdStore.assign(
                    srcMsgId, srcSeq, ctx.chatType, ctx.peerUid ?: "", 0L, re.replyMsgTime ?: 0L
                )
            }
            seg(
                "reply",
                JSONObject()
                    .put("id", (entry?.messageId ?: 0).toString())
                    .put("msg_id", srcMsgId.toString())
                    .put("seq", srcSeq.toString())
            )
        }

        T_PIC -> e.picElement?.let {
            // 合并转发内部的图片**不带 fileName**,直接透出去上游按 `file` 取图必然落空;
            // 用 md5 补一个稳定的名字。
            val name = it.fileName?.takeIf { n -> n.isNotEmpty() }
                ?: (it.md5HexStr?.let { m -> m + picExt(it.picType) } ?: "")
            val url = picUrl(it.originImageUrl)
            remember(ctx, e, T_PIC, it.sourcePath, name, it.fileSize, it.md5HexStr, it.fileUuid, url)
            seg(
                "image",
                JSONObject()
                    .put("file", name)
                    .put("url", url)
                    .put("size", it.fileSize)
                    .put("md5", it.md5HexStr ?: "")
                    .put("sub_type", it.picSubType)
                    .put("path", it.sourcePath ?: "")
                    .put("element_id", e.elementId.toString())
            )
        }

        T_PTT -> e.pttElement?.let {
            val name = it.fileName ?: (it.md5HexStr ?: "")
            remember(ctx, e, T_PTT, it.filePath, name, it.fileSize, it.md5HexStr, it.fileUuid)
            seg(
                "record",
                JSONObject()
                    .put("file", name)
                    .put("path", it.filePath ?: "")
                    .put("duration", it.duration)
                    .put("format", it.formatType)
                    .put("md5", it.md5HexStr ?: "")
                    .put("element_id", e.elementId.toString())
            )
        }

        T_VIDEO -> e.videoElement?.let {
            val name = it.fileName ?: (it.videoMd5 ?: "")
            remember(ctx, e, T_VIDEO, it.filePath, name, it.fileSize, it.videoMd5, it.fileUuid)
            seg(
                "video",
                JSONObject()
                    .put("file", name)
                    .put("path", it.filePath ?: "")
                    .put("size", it.fileSize)
                    .put("duration", it.fileTime)
                    .put("md5", it.videoMd5 ?: "")
                    .put("element_id", e.elementId.toString())
            )
        }

        T_FILE -> e.fileElement?.let {
            val name = it.fileName ?: ""
            remember(ctx, e, T_FILE, it.filePath, name, it.fileSize, it.fileMd5, it.fileUuid)
            seg(
                "file",
                JSONObject()
                    .put("file", name)
                    .put("name", name)
                    .put("size", it.fileSize)
                    .put("uuid", it.fileUuid ?: "")
                    .put("path", it.filePath ?: "")
                    .put("element_id", e.elementId.toString())
            )
        }

        T_ARK -> e.arkElement?.let {
            val raw = it.bytesData ?: ""
            // **自己发出去的**合并转发在记录里是一张 multimsg ark 卡片,不是
            // MultiForwardMsgElement —— 原样透出去上游只会看到一坨 json,认不出这是转发。
            val resId = multiMsgResId(raw)
            if (resId != null) {
                if (ctx != null) ForwardStore.put(resId, ctx.chatType, ctx.peerUid, ctx.msgId)
                seg("forward", JSONObject().put("id", resId))
            } else {
                seg("json", JSONObject().put("data", raw))
            }
        }

        T_MFACE -> e.marketFaceElement?.let {
            seg(
                "mface",
                JSONObject()
                    .put("emoji_id", it.emojiId ?: "")
                    .put("emoji_package_id", it.emojiPackageId)
                    .put("summary", it.faceName ?: "")
                    .put("key", it.key ?: "")
            )
        }

        T_MULTI_FORWARD -> e.multiForwardMsgElement?.let {
            // resId 只是个句柄,内容要另外用 getMultiMsg 拉;而那个接口要的是会话+msgId,
            // 所以这里把 resId → 承载消息的对应关系记下来,get_forward_msg 才回查得到。
            if (ctx != null) ForwardStore.put(it.resId, ctx.chatType, ctx.peerUid, ctx.msgId)
            seg("forward", JSONObject().put("id", it.resId ?: ""))
        }

        T_GREY_TIP -> null // 灰条(撤回提示/拍一拍)走 notice,不进 message

        else -> null
    }

    /**
     * 登记"这个媒体属于哪条消息的哪个元素"。
     * 不记的话 `get_image` / `get_record` 只拿到一个文件名,内核那边无从下手 ——
     * `downloadRichMedia` 要的是 (chatType, peerUid, msgId, elementId)。
     */
    private fun remember(
        ctx: MsgRecord?,
        e: MsgElement,
        elementType: Int,
        localPath: String?,
        fileName: String,
        fileSize: Long,
        md5: String?,
        uuid: String?,
        url: String = ""
    ) {
        if (ctx == null || e.elementId == 0L) return
        val ref = MediaRefStore.Ref(
            ctx.chatType, ctx.peerUid ?: "", ctx.msgId, e.elementId,
            elementType, localPath ?: "", fileName, fileSize, url
        )
        MediaRefStore.put(ref, fileName, md5, uuid, e.elementId.toString())
    }

    /** @return multimsg ark 卡片里的 resid;不是合并转发卡片则返回 null。 */
    private fun multiMsgResId(raw: String): String? {
        if (!raw.contains("com.tencent.multimsg")) return null
        return try {
            val detail = JSONObject(raw).optJSONObject("meta")?.optJSONObject("detail")
            detail?.optString("resid", "")?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    /** picType(1000=jpg/1001=png/…)反推扩展名,用于补齐转发内图片的空文件名。 */
    private fun picExt(picType: Int?): String = when (picType) {
        1001 -> ".png"
        1002 -> ".webp"
        1005 -> ".bmp"
        2000 -> ".gif"
        2001 -> ".apng"
        else -> ".jpg"
    }

    private fun picUrl(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return if (raw.startsWith("http")) raw else "https://gchat.qpic.cn$raw"
    }

    private fun seg(type: String, data: JSONObject): JSONObject =
        JSONObject().put("type", type).put("data", data)

    /**
     * 按 `message_format` 决定 `message` 字段的形态。
     * 段数组是 OneBot v11 的默认,但老 CQHTTP 系插件只会正则匹配 CQ 码字符串 ——
     * 两种都要能出,否则那一半插件根本接不上。
     */
    fun formatted(segments: JSONArray): Any =
        if (Config.messageFormat == "string") toCq(segments) else segments

    /** segments → CQ 码字符串(raw_message)。 */
    fun toCq(segments: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val s = segments.optJSONObject(i) ?: continue
            val type = s.optString("type")
            val data = s.optJSONObject("data") ?: JSONObject()
            if (type == "text") {
                sb.append(escapeText(data.optString("text")))
                continue
            }
            sb.append("[CQ:").append(type)
            val keys = data.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                sb.append(",").append(k).append("=").append(escapeParam(data.optString(k)))
            }
            sb.append("]")
        }
        return sb.toString()
    }

    private fun escapeText(s: String) = s.replace("&", "&amp;").replace("[", "&#91;").replace("]", "&#93;")
    private fun escapeParam(s: String) = escapeText(s).replace(",", "&#44;")
    private fun unescape(s: String) =
        s.replace("&#44;", ",").replace("&#91;", "[").replace("&#93;", "]").replace("&amp;", "&")

    // ---------------- OneBot → 内核 ----------------

    /** 接受 String(CQ 码)/ JSONArray(段数组)/ JSONObject(单段)。 */
    fun normalize(message: Any?): JSONArray = when (message) {
        null -> JSONArray()
        is JSONArray -> message
        is JSONObject -> JSONArray().put(message)
        is String -> parseCq(message)
        else -> parseCq(message.toString())
    }

    fun parseCq(text: String): JSONArray {
        val arr = JSONArray()
        var i = 0
        val buf = StringBuilder()
        while (i < text.length) {
            val start = text.indexOf("[CQ:", i)
            if (start < 0) {
                buf.append(text.substring(i))
                break
            }
            val end = text.indexOf(']', start)
            if (end < 0) {
                buf.append(text.substring(i))
                break
            }
            buf.append(text, i, start)
            if (buf.isNotEmpty()) {
                arr.put(seg("text", JSONObject().put("text", unescape(buf.toString()))))
                buf.setLength(0)
            }
            val body = text.substring(start + 4, end)
            val parts = body.split(",")
            val type = parts[0]
            val data = JSONObject()
            for (p in 1 until parts.size) {
                val kv = parts[p]
                val eq = kv.indexOf('=')
                if (eq > 0) data.put(kv.substring(0, eq), unescape(kv.substring(eq + 1)))
            }
            arr.put(seg(type, data))
            i = end + 1
        }
        if (buf.isNotEmpty()) arr.put(seg("text", JSONObject().put("text", unescape(buf.toString()))))
        return arr
    }

    /**
     * segments → MsgElement[]。
     * @throws UnsupportedSegment 遇到本阶段还做不到的段(调用方转成 retcode 1404)。
     */
    fun toElements(segments: JSONArray): ArrayList<MsgElement> {
        val out = ArrayList<MsgElement>()
        for (i in 0 until segments.length()) {
            val s = segments.optJSONObject(i) ?: continue
            val type = s.optString("type")
            val data = s.optJSONObject("data") ?: JSONObject()
            when (type) {
                "text" -> out.add(textElement(data.optString("text")))
                "at" -> out.add(atElement(data))
                "face" -> out.add(faceElement(data.optString("id").toIntOrNull() ?: 0))
                "reply" -> out.add(replyElement(data))
                "image" -> out.add(
                    MediaBuilder.image(
                        sourceOf(data, "image"),
                        data.optInt("sub_type", data.optInt("subType", 0)),
                        data.optString("summary", "")
                    )
                )
                "record", "voice" -> out.add(
                    MediaBuilder.record(sourceOf(data, "record"), data.optInt("duration", 0))
                )
                "video" -> out.add(MediaBuilder.video(sourceOf(data, "video")))
                "file" -> out.add(
                    MediaBuilder.file(sourceOf(data, "file"), data.optString("name", ""))
                )
                else -> throw UnsupportedSegment(type)
            }
        }
        return out
    }

    /**
     * 媒体段的来源。上游把路径塞在哪个键上并不统一(`file` / `url` / `path` 都见过),
     * 而且**转发收到的媒体时会把原样的 `file` 名字回传回来** —— 那种情况下它既不是
     * URL 也不是本地路径,只有查 [MediaRefStore] 才认得出来。
     */
    private fun sourceOf(data: JSONObject, kind: String): java.io.File {
        val spec = listOf("file", "url", "path", "src")
            .map { data.optString(it, "") }
            .firstOrNull { it.isNotEmpty() }
            ?: throw UnsupportedSegment("$kind(没有 file/url/path)")
        val looksLikeLocator = spec.startsWith("base64://") || spec.startsWith("http") ||
            spec.startsWith("file://") || spec.startsWith("/")
        if (!looksLikeLocator) {
            val ref = MediaRefStore.get(spec)
            if (ref != null && ref.localPath.isNotEmpty()) {
                val f = java.io.File(ref.localPath)
                if (f.isFile) return f
            }
        }
        return MediaFiles.resolve(spec, data.optString("name", ""))
    }

    /** 纯文本元素(合并转发拼接节点前缀时要用)。 */
    fun text(content: String): MsgElement = textElement(content)

    private fun textElement(content: String): MsgElement {
        val e = MsgElement()
        e.elementType = T_TEXT
        e.textElement = TextElement().also {
            it.content = content
            it.atType = AT_NONE
        }
        return e
    }

    private fun atElement(data: JSONObject): MsgElement {
        val qq = data.optString("qq")
        val e = MsgElement()
        e.elementType = T_TEXT
        val t = TextElement()
        if (qq == "all" || qq == "0") {
            t.atType = AT_ALL
            t.content = "@全体成员"
        } else {
            val uin = qq.toLongOrNull() ?: 0L
            val uid = UinUidStore.uid(uin)
            if (uid.isEmpty()) throw UnsupportedSegment("at(uid 未知: $qq)")
            t.atType = AT_ONE
            t.atUid = uin
            t.atNtUid = uid
            val name = data.optString("name", "")
            t.content = if (name.isEmpty()) "@$qq" else "@$name"
        }
        e.textElement = t
        return e
    }

    private fun faceElement(id: Int): MsgElement {
        val e = MsgElement()
        e.elementType = T_FACE
        e.faceElement = FaceElement().also {
            it.faceIndex = id
            it.faceType = if (id < 260) 1 else 2
        }
        return e
    }

    private fun replyElement(data: JSONObject): MsgElement {
        val id = data.optString("id").toIntOrNull() ?: 0
        val entry = MsgIdStore.byMessageId(id) ?: throw UnsupportedSegment("reply(未知 message_id: $id)")
        val e = MsgElement()
        e.elementType = T_REPLY
        e.replyElement = ReplyElement().also {
            it.replayMsgId = entry.msgId
            it.replayMsgSeq = entry.msgSeq
            it.sourceMsgIdInRecords = entry.msgId
            it.senderUid = entry.senderUin
        }
        return e
    }

    // ---------------- 事件组装 ----------------

    /** MsgRecord → OneBot message 事件体(不含 post_type,调用方决定 message / message_sent)。 */
    /**
     * NT 的**私聊**记录不填 sendNickName,只有 peerName —— 直接用就会让所有私聊事件
     * 带着空昵称出去。注意 peerName 是"会话对端"的名字,自发消息里它是**收件人**,
     * 拿它当发件人昵称是错的,所以自发时不回退。
     */
    private fun nickname(rec: MsgRecord): String {
        val selfUid = KernelGate.selfUid()
        val isSelfRec = !rec.senderUid.isNullOrEmpty() && rec.senderUid == selfUid
        val candidates = listOf(
            rec.sendNickName,
            rec.sendMemberName,
            rec.sendRemarkName,
            if (!isSelfRec && rec.chatType != 2) rec.peerName else null
        )
        val direct = candidates.firstOrNull { !it.isNullOrEmpty() }
        if (direct != null) {
            NickStore.feed(rec.senderUid, direct)
            return direct
        }
        // 私聊记录三个名字字段全是空(peerName 也是)—— 只能回查好友表。
        return NickStore.of(rec.senderUid)
    }

    fun toMessageBody(rec: MsgRecord): JSONObject {
        UinUidStore.feed(rec.senderUid, rec.senderUin)
        if (rec.chatType == 1) UinUidStore.feed(rec.peerUid, rec.peerUin)

        val segments = toSegments(rec.elements, rec)
        // senderUin 为 0 且 uid 映射还没热时,至少认得出"是我自己发的",
        // 否则自发消息会带着 user_id=0 出去,上游框架无从判断。
        val senderUin = if (rec.senderUin > 0) rec.senderUin else {
            val mapped = UinUidStore.uin(rec.senderUid)
            if (mapped > 0) mapped
            else if (!rec.senderUid.isNullOrEmpty() && rec.senderUid == KernelGate.selfUid()) KernelGate.selfUin()
            else 0L
        }
        val peerUin = if (rec.peerUin > 0) rec.peerUin else UinUidStore.uin(rec.peerUid)
        val entry = MsgIdStore.assign(
            rec.msgId, rec.msgSeq, rec.chatType, rec.peerUid ?: "", senderUin, rec.msgTime
        )

        val body = JSONObject()
            .put("message_id", entry.messageId)
            .put("message_seq", rec.msgSeq)
            .put("real_id", entry.messageId)
            .put("user_id", senderUin)
            .put("message", formatted(segments))
            .put("raw_message", toCq(segments))
            .put("font", 0)
            .put("message_format", Config.messageFormat)
            .put("post_type", "message")

        if (rec.chatType == 2) {
            body.put("message_type", "group")
                .put("sub_type", "normal")
                .put("group_id", rec.peerUid?.toLongOrNull() ?: peerUin)
        } else {
            body.put("message_type", "private")
                .put("sub_type", "friend")
        }

        body.put(
            "sender",
            JSONObject()
                .put("user_id", senderUin)
                .put("nickname", nickname(rec))
                .put("card", rec.sendMemberName ?: "")
                .put("role", "member")
        )
        return body
    }
}

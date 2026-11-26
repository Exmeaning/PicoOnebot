package pico.onebot.action

import com.tencent.qqnt.kernelpublic.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IGetMultiMsgCallback
import com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgAttributeInfo
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.core.Promise
import pico.onebot.core.RateLimiter
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.ReadReporter
import pico.onebot.kernel.SendTracker
import pico.onebot.kernel.UinUidStore
import pico.onebot.kernel.NickStore
import pico.onebot.msg.ForwardStore
import pico.onebot.msg.MsgCodec
import pico.onebot.msg.MsgIdStore
import pico.onebot.proto.Ob

/**
 * 消息类动作。发送路径按 docs §4 实现:**预生成 msgId → sendMsg → 等回执 + 等落库记录**。
 * 未登录时每个动作都明确回 1400(不是抛异常,也不是静默成功)。
 */
object MsgActions {

    private const val CHAT_PRIVATE = 1
    private const val CHAT_GROUP = 2

    private data class OpResult(val code: Int, val msg: String)
    private data class MsgListResult(val code: Int, val msg: String, val records: ArrayList<MsgRecord>?)

    // 取值器而非值:WebUI 改发送速率后立刻生效,不必重启
    private val limiter by lazy { RateLimiter { Config.sendRatePerSec } }

    fun register() {
        ActionRouter.register("send_msg") { p ->
            val rawType = MsgCodec.optNonNullString(p, "message_type").trim()
            val type = if (rawType.isNotEmpty()) {
                rawType
            } else if (optLongParam(p, "group_id") > 0L) {
                "group"
            } else {
                "private"
            }
            when (type) {
                "group" -> sendGroup(p)
                "private" -> sendPrivate(p)
                else -> Ob.failed(Ob.RC_BAD_PARAM, "非法 message_type: $type (仅支持 group / private)")
            }
        }
        ActionRouter.register("send_group_msg") { p -> sendGroup(p) }
        ActionRouter.register("send_private_msg") { p -> sendPrivate(p) }
        ActionRouter.register("delete_msg") { p -> deleteMsg(p) }
        ActionRouter.register("get_msg") { p -> getMsg(p) }
        ActionRouter.register("get_forward_msg") { p -> getForwardMsg(p) }
        ActionRouter.register("mark_msg_as_read") { p -> markMsgAsRead(p) }
    }

    private fun optLongParam(p: JSONObject, key: String): Long {
        if (p.isNull(key)) return 0L
        return MsgCodec.optNonNullString(p, key).trim().toLongOrNull() ?: p.optLong(key, 0L)
    }

    private fun optIntParam(p: JSONObject, key: String): Int {
        if (p.isNull(key)) return 0
        return MsgCodec.optNonNullString(p, key).trim().toIntOrNull() ?: p.optInt(key, 0)
    }

    // ---------------- 发送 ----------------

    private fun sendGroup(p: JSONObject): JSONObject {
        val groupId = optLongParam(p, "group_id")
        if (groupId <= 0) return Ob.failed(Ob.RC_BAD_PARAM, "group_id 缺失或非法")
        return send(Contact(CHAT_GROUP, groupId.toString(), ""), CHAT_GROUP, p)
    }

    private fun sendPrivate(p: JSONObject): JSONObject {
        val userId = optLongParam(p, "user_id")
        if (userId <= 0) return Ob.failed(Ob.RC_BAD_PARAM, "user_id 缺失或非法")
        if (!KernelGate.ready) return Ob.notReady("send_private_msg")
        // 私聊的 peerUid 要的是 uid 字符串,不是 QQ 号
        val uid = UinUidStore.uid(userId)
        if (uid.isEmpty()) {
            return Ob.failed(Ob.RC_NOT_READY, "拿不到 $userId 的 uid(需要先有一次交互或好友列表同步)")
        }
        return send(Contact(CHAT_PRIVATE, uid, ""), CHAT_PRIVATE, p)
    }

    private fun send(contact: Contact, chatType: Int, p: JSONObject): JSONObject {
        if (!KernelGate.ready) return Ob.notReady("send_msg")
        val msgService = KernelGate.msgService() ?: return Ob.notReady("send_msg(msgService)")

        val raw = if (p.isNull("message")) null else p.opt("message")
        val elements: ArrayList<MsgElement> = try {
            // auto_escape=true 时整条当纯文本,不解析 CQ 码
            val segments = if (p.optBoolean("auto_escape", false) && raw is String) {
                MsgCodec.normalize(
                    JSONObject().put("type", "text").put("data", JSONObject().put("text", raw))
                )
            } else {
                MsgCodec.normalize(raw)
            }
            MsgCodec.toElements(segments)
        } catch (t: MsgCodec.BadSegmentParam) {
            return Ob.failed(Ob.RC_BAD_PARAM, t.message ?: "消息段参数非法")
        } catch (t: MsgCodec.UnsupportedSegment) {
            return Ob.failed(Ob.RC_UNSUPPORTED, "消息段暂不支持: " + t.type)
        } catch (t: pico.onebot.media.MediaFiles.ResolveError) {
            // 取不到源文件是调用方的问题(路径/URL/base64),要说清楚是哪一步失败
            return Ob.failed(Ob.RC_BAD_PARAM, "媒体来源无法读取: " + t.message)
        } catch (t: pico.onebot.media.MediaDownloader.DownloadError) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "媒体下载失败: " + t.message)
        } catch (t: pico.onebot.media.MediaBuilder.BuildError) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "媒体元素构造失败: " + t.message)
        } catch (t: Throwable) {
            return Ob.failed(Ob.RC_BAD_PARAM, "message 解析失败: " + t.javaClass.simpleName + " " + t.message)
        }
        if (elements.isEmpty()) return Ob.failed(Ob.RC_BAD_PARAM, "message 为空")
        return sendElements(contact, chatType, elements)
    }

    /** 发送主干:预生成 msgId → sendMsg → 等回执 + 等落库。合并转发的降级路径也走这里。 */
    fun sendElements(contact: Contact, chatType: Int, elements: ArrayList<MsgElement>): JSONObject {
        if (!KernelGate.ready) return Ob.notReady("send_msg")
        val msgService = KernelGate.msgService() ?: return Ob.notReady("send_msg(msgService)")

        if (!limiter.acquire()) return Ob.failed(Ob.RC_TIMEOUT, "发送限速排队超时")

        val msgId = try {
            msgService.generateMsgUniqueId(chatType, System.currentTimeMillis())
        } catch (t: Throwable) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "generateMsgUniqueId 失败: " + t.message)
        }

        val recordPromise = SendTracker.expect(msgId)
        val result = Promise<OpResult>()
        val record: MsgRecord?
        try {
            try {
                msgService.sendMsg(
                    msgId,
                    contact,
                    elements,
                    HashMap<Int, MsgAttributeInfo>(),
                    IOperateCallback { code, msg ->
                        result.resolve(OpResult(code, msg ?: ""))
                    }
                )
            } catch (t: Throwable) {
                return Ob.failed(Ob.RC_KERNEL_ERROR, "sendMsg 抛出: " + t.javaClass.simpleName + " " + t.message)
            }

            val op = result.await(Config.kernelTimeoutMs)
                ?: return Ob.failed(Ob.RC_TIMEOUT, "sendMsg 回执超时(msgId=$msgId)")
            if (op.code != 0) {
                // 刚登录/刚重启时 BDH(富媒体上传通道)的 host 列表还没下发,发图必然失败
                // (内核日志:`Bdh host list is empty No IP use`)。这是**瞬态**的,但不能自动重试 ——
                // 失败时本地其实已经落了一条记录,重发会变成两条。
                val hint = if (op.msg.contains("rich media")) {
                    "(富媒体上传通道未就绪,常见于刚登录/刚重启后的头一两分钟,稍后重发即可)"
                } else ""
                return Ob.failed(Ob.RC_KERNEL_ERROR, "内核拒绝发送 code=" + op.code + " " + op.msg + hint)
            }

            // 落库记录用来拿 msgSeq;拿不到也不算失败(消息已被内核接受)
            record = recordPromise.await(5000)
            // 但**异步投递失败**必须在这里结账:onSendMsgError 会把 recordPromise 用 null 完成,
            // 与"超时"不可区分。只看回调就回 ok,等于向上游谎报发送成功。
            val failure = SendTracker.takeFailure(msgId)
            if (failure != null) {
                return Ob.failed(
                    Ob.RC_KERNEL_ERROR,
                    "内核受理后投递失败 code=" + failure.code + " " + failure.msg
                )
            }
        } finally {
            SendTracker.forget(msgId)
        }
        val entry = MsgIdStore.assign(
            msgId,
            record?.msgSeq ?: 0L,
            chatType,
            contact.peerUid ?: "",
            KernelGate.selfUin(),
            record?.msgTime ?: (System.currentTimeMillis() / 1000)
        )
        PicoLog.i("sent msgId=$msgId message_id=" + entry.messageId + " seq=" + (record?.msgSeq ?: -1))
        pico.onebot.core.Counters.onSent()
        return Ob.ok(JSONObject().put("message_id", entry.messageId).put("msg_id", msgId.toString()))
    }

    // ---------------- 撤回 / 取回 ----------------

    private fun deleteMsg(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return Ob.notReady("delete_msg")
        val msgService = KernelGate.msgService() ?: return Ob.notReady("delete_msg(msgService)")
        val messageId = optIntParam(p, "message_id")
        val entry = MsgIdStore.byMessageId(messageId)
            ?: return Ob.failed(Ob.RC_BAD_PARAM, "未知 message_id: $messageId")

        val result = Promise<OpResult>()
        try {
            msgService.recallMsg(
                Contact(entry.chatType, entry.peerUid, ""),
                arrayListOf(entry.msgId),
                IOperateCallback { code, msg ->
                    result.resolve(OpResult(code, msg ?: ""))
                }
            )
        } catch (t: Throwable) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "recallMsg 调用失败: " + t.javaClass.simpleName)
        }
        val op = result.await(Config.kernelTimeoutMs)
            ?: return Ob.failed(Ob.RC_TIMEOUT, "recallMsg 超时")
        return if (op.code == 0) Ob.ok()
        else Ob.failed(Ob.RC_KERNEL_ERROR, "撤回失败 code=" + op.code + " " + op.msg)
    }

    /**
     * 合并转发内容。标准 OneBot 用 `id`(转发 resId),但 resId 只有"见过那条消息"才拿得到;
     * 所以这里也接受 `message_id` —— 调用方手里现成就有。两条路最终都落到
     * `getMultiMsg(Contact, rootMsgId, parentMsgId, cb)`。
     */
    private fun getForwardMsg(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return Ob.notReady("get_forward_msg")
        val msgService = KernelGate.msgService() ?: return Ob.notReady("get_forward_msg(msgService)")

        val chatType: Int
        val peerUid: String
        val msgId: Long
        val resId = MsgCodec.optNonNullString(p, "id").trim()
            .ifEmpty { MsgCodec.optNonNullString(p, "res_id").trim() }
        val hasMessageId = !p.isNull("message_id") && MsgCodec.optNonNullString(p, "message_id").trim().isNotEmpty()
        if (hasMessageId) {
            val entry = MsgIdStore.byMessageId(optIntParam(p, "message_id"))
                ?: return Ob.failed(Ob.RC_BAD_PARAM, "未知 message_id")
            chatType = entry.chatType
            peerUid = entry.peerUid
            msgId = entry.msgId
        } else if (resId.isNotEmpty()) {
            val ref = ForwardStore.locate(resId)
                ?: return Ob.failed(Ob.RC_BAD_PARAM, "该 resId 未被本实例见过,请改用 message_id")
            chatType = ref.chatType
            peerUid = ref.peerUid
            msgId = ref.msgId
        } else {
            return Ob.failed(Ob.RC_BAD_PARAM, "需要 id(转发 resId)或 message_id")
        }

        val promise = Promise<MsgListResult>()
        try {
            msgService.getMultiMsg(
                Contact(chatType, peerUid, ""), msgId, msgId,
                IGetMultiMsgCallback { code, msg, records ->
                    promise.resolve(MsgListResult(code, msg ?: "", records))
                }
            )
        } catch (t: Throwable) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "getMultiMsg 调用失败: " + t.javaClass.simpleName)
        }

        val r = promise.await(Config.kernelTimeoutMs)
            ?: return Ob.failed(Ob.RC_TIMEOUT, "内核拉取合并转发超时")
        if (r.code != 0) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "内核拒绝: code=" + r.code + " " + r.msg)
        }

        val records = r.records ?: ArrayList()
        val nodes = JSONArray()
        for (rec in records) {
            // 内层记录的会话字段经常是空的 —— 直接拿它去 downloadRichMedia 就定位不到,
            // 转发里的图片于是永远只能看见文件名。补成承载它的那个会话再往下走。
            if (rec.peerUid.isNullOrEmpty()) {
                rec.peerUid = peerUid
                rec.chatType = chatType
            }
            val uin = if (rec.senderUin > 0) rec.senderUin else UinUidStore.uin(rec.senderUid)
            val nick = listOf(rec.sendNickName, rec.sendMemberName, rec.sendRemarkName)
                .firstOrNull { !it.isNullOrEmpty() } ?: NickStore.of(rec.senderUid)
            nodes.put(
                JSONObject().put("type", "node").put(
                    "data",
                    JSONObject()
                        .put("user_id", uin)
                        .put("nickname", nick)
                        .put("time", rec.msgTime)
                        .put("content", MsgCodec.formatted(MsgCodec.toSegments(rec.elements, rec)))
                )
            )
        }
        PicoLog.d(
            "get_forward_msg msgId=" + msgId + " nodes=" + nodes.length() +
                " inner=" + records.joinToString(",") { r ->
                    r.msgId.toString() + "/" + (r.elements?.size ?: 0) +
                        "/" + (r.elements?.firstOrNull()?.elementType ?: -1)
                }.take(160)
        )
        return Ob.ok(JSONObject().put("message", nodes))
    }

    private fun getMsg(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return Ob.notReady("get_msg")
        val msgService = KernelGate.msgService() ?: return Ob.notReady("get_msg(msgService)")
        val messageId = optIntParam(p, "message_id")
        val entry = MsgIdStore.byMessageId(messageId)
            ?: return Ob.failed(Ob.RC_BAD_PARAM, "未知 message_id: $messageId")

        val result = Promise<MsgListResult>()
        try {
            msgService.getMsgsByMsgId(
                Contact(entry.chatType, entry.peerUid, ""),
                arrayListOf(entry.msgId),
                IMsgOperateCallback { code, msg, records ->
                    result.resolve(MsgListResult(code, msg ?: "", records))
                }
            )
        } catch (t: Throwable) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "getMsgsByMsgId 调用失败: " + t.javaClass.simpleName)
        }
        val res = result.await(Config.kernelTimeoutMs)
            ?: return Ob.failed(Ob.RC_TIMEOUT, "getMsgsByMsgId 超时")
        if (res.code != 0) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "内核拒绝: code=" + res.code + " " + res.msg)
        }
        val rec = res.records?.firstOrNull()
            ?: return Ob.failed(Ob.RC_KERNEL_ERROR, "内核没有返回该消息")
        val body = MsgCodec.toMessageBody(rec)
            ?: return Ob.failed(Ob.RC_KERNEL_ERROR, "该消息无法解码为 OneBot 格式")
        return Ob.ok(body)
    }

    /** 主动上报指定会话已读(发送 SsoReadedReport 推进服务端 read_msg_seq)。 */
    private fun markMsgAsRead(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return Ob.notReady("mark_msg_as_read")
        val groupId = optLongParam(p, "group_id")
        val userId = optLongParam(p, "user_id")
        val messageId = optIntParam(p, "message_id")

        val chatType: Int
        val peerUid: String
        when {
            groupId > 0L -> {
                chatType = CHAT_GROUP
                peerUid = groupId.toString()
            }
            userId > 0L -> {
                val uid = UinUidStore.uid(userId)
                if (uid.isEmpty()) {
                    return Ob.failed(Ob.RC_NOT_READY, "拿不到 $userId 的 uid(需要先有一次交互或好友列表同步)")
                }
                chatType = CHAT_PRIVATE
                peerUid = uid
            }
            messageId != 0 -> {
                val entry = MsgIdStore.byMessageId(messageId)
                    ?: return Ob.failed(Ob.RC_BAD_PARAM, "未知 message_id: $messageId")
                chatType = entry.chatType
                peerUid = entry.peerUid
            }
            else -> return Ob.failed(Ob.RC_BAD_PARAM, "需要 group_id、user_id 或 message_id")
        }

        val (ok, detail) = ReadReporter.markReadNow(chatType, peerUid)
        return if (ok) {
            Ob.ok(JSONObject().put("chat_type", chatType).put("peer_uid", peerUid))
        } else {
            val rc = when {
                detail.startsWith("kernel not ready") || detail.startsWith("msgService unavailable") -> Ob.RC_NOT_READY
                detail.startsWith("timeout") || detail.contains("rate limit") -> Ob.RC_TIMEOUT
                detail.startsWith("unsupported ") || detail.startsWith("peerUid") -> Ob.RC_BAD_PARAM
                else -> Ob.RC_KERNEL_ERROR
            }
            Ob.failed(rc, "已读上报失败: $detail")
        }
    }
}

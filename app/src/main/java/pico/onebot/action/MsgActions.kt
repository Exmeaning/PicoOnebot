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

    private val limiter by lazy { RateLimiter(Config.sendRatePerSec) }

    fun register() {
        ActionRouter.register("send_msg") { p ->
            val type = p.optString("message_type", if (p.has("group_id")) "group" else "private")
            if (type == "group") sendGroup(p) else sendPrivate(p)
        }
        ActionRouter.register("send_group_msg") { p -> sendGroup(p) }
        ActionRouter.register("send_private_msg") { p -> sendPrivate(p) }
        ActionRouter.register("delete_msg") { p -> deleteMsg(p) }
        ActionRouter.register("get_msg") { p -> getMsg(p) }
        ActionRouter.register("get_forward_msg") { p -> getForwardMsg(p) }
    }

    // ---------------- 发送 ----------------

    private fun sendGroup(p: JSONObject): JSONObject {
        val groupId = p.optLong("group_id", 0)
        if (groupId <= 0) return Ob.failed(Ob.RC_BAD_PARAM, "group_id 缺失或非法")
        return send(Contact(CHAT_GROUP, groupId.toString(), ""), CHAT_GROUP, p)
    }

    private fun sendPrivate(p: JSONObject): JSONObject {
        val userId = p.optLong("user_id", 0)
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

        val raw = p.opt("message")
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
        } catch (t: MsgCodec.UnsupportedSegment) {
            return Ob.failed(Ob.RC_UNSUPPORTED, "消息段暂不支持: " + t.type)
        } catch (t: pico.onebot.media.MediaFiles.ResolveError) {
            // 取不到源文件是调用方的问题(路径/URL/base64),要说清楚是哪一步失败
            return Ob.failed(Ob.RC_BAD_PARAM, "媒体来源无法读取: " + t.message)
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
        val result = Promise<IntArray>()
        val errMsg = arrayOf("")

        try {
            msgService.sendMsg(
                msgId,
                contact,
                elements,
                HashMap<Int, MsgAttributeInfo>(),
                IOperateCallback { code, msg ->
                    errMsg[0] = msg ?: ""
                    result.resolve(intArrayOf(code))
                }
            )
        } catch (t: Throwable) {
            SendTracker.forget(msgId)
            return Ob.failed(Ob.RC_KERNEL_ERROR, "sendMsg 抛出: " + t.javaClass.simpleName + " " + t.message)
        }

        val code = result.await(Config.kernelTimeoutMs)
        if (code == null) {
            SendTracker.forget(msgId)
            return Ob.failed(Ob.RC_TIMEOUT, "sendMsg 回执超时(msgId=$msgId)")
        }
        if (code[0] != 0) {
            SendTracker.forget(msgId)
            // 刚登录/刚重启时 BDH(富媒体上传通道)的 host 列表还没下发,发图必然失败
            // (内核日志:`Bdh host list is empty No IP use`)。这是**瞬态**的,但不能自动重试 ——
            // 失败时本地其实已经落了一条记录,重发会变成两条。
            val hint = if (errMsg[0].contains("rich media")) {
                "(富媒体上传通道未就绪,常见于刚登录/刚重启后的头一两分钟,稍后重发即可)"
            } else ""
            return Ob.failed(Ob.RC_KERNEL_ERROR, "内核拒绝发送 code=" + code[0] + " " + errMsg[0] + hint)
        }

        // 落库记录用来拿 msgSeq;拿不到也不算失败(消息已被内核接受)
        val record: MsgRecord? = recordPromise.await(5000)
        SendTracker.forget(msgId)
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
        val messageId = p.optInt("message_id", 0)
        val entry = MsgIdStore.byMessageId(messageId)
            ?: return Ob.failed(Ob.RC_BAD_PARAM, "未知 message_id: $messageId")

        val result = Promise<IntArray>()
        val errMsg = arrayOf("")
        msgService.recallMsg(
            Contact(entry.chatType, entry.peerUid, ""),
            arrayListOf(entry.msgId),
            IOperateCallback { code, msg ->
                errMsg[0] = msg ?: ""
                result.resolve(intArrayOf(code))
            }
        )
        val code = result.await(Config.kernelTimeoutMs)
            ?: return Ob.failed(Ob.RC_TIMEOUT, "recallMsg 超时")
        return if (code[0] == 0) Ob.ok()
        else Ob.failed(Ob.RC_KERNEL_ERROR, "撤回失败 code=" + code[0] + " " + errMsg[0])
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
        val resId = p.optString("id", "").ifEmpty { p.optString("res_id", "") }
        if (p.has("message_id")) {
            val entry = MsgIdStore.byMessageId(p.optInt("message_id"))
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

        val promise = Promise<Array<Any?>>()
        try {
            msgService.getMultiMsg(
                Contact(chatType, peerUid, ""), msgId, msgId,
                IGetMultiMsgCallback { code, msg, records ->
                    promise.resolve(arrayOf(code, msg, records))
                }
            )
        } catch (t: Throwable) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "getMultiMsg 调用失败: " + t.javaClass.simpleName)
        }

        val r = promise.await(Config.kernelTimeoutMs)
            ?: return Ob.failed(Ob.RC_TIMEOUT, "内核拉取合并转发超时")
        val code = r[0] as? Int ?: -1
        if (code != 0) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "内核拒绝: code=" + code + " " + (r[1] ?: ""))
        }

        @Suppress("UNCHECKED_CAST")
        val records = r[2] as? ArrayList<MsgRecord> ?: ArrayList()
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
        val messageId = p.optInt("message_id", 0)
        val entry = MsgIdStore.byMessageId(messageId)
            ?: return Ob.failed(Ob.RC_BAD_PARAM, "未知 message_id: $messageId")

        val result = Promise<ArrayList<MsgRecord>>()
        msgService.getMsgsByMsgId(
            Contact(entry.chatType, entry.peerUid, ""),
            arrayListOf(entry.msgId),
            IMsgOperateCallback { _, _, records -> result.resolve(records) }
        )
        val records = result.await(Config.kernelTimeoutMs)
            ?: return Ob.failed(Ob.RC_TIMEOUT, "getMsgsByMsgId 超时")
        val rec = records.firstOrNull()
            ?: return Ob.failed(Ob.RC_KERNEL_ERROR, "内核没有返回该消息")
        return Ob.ok(MsgCodec.toMessageBody(rec))
    }
}

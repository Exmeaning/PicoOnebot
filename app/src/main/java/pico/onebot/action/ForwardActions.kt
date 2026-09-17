package pico.onebot.action

import com.tencent.qqnt.kernelpublic.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.core.Promise
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.UinUidStore
import pico.onebot.msg.MsgCodec
import pico.onebot.msg.MsgIdStore
import pico.onebot.proto.Ob

/**
 * 发送合并转发。
 *
 * **手表协议发不了真正的合并转发。** 内核侧一路都能跑通(打包节点、转发元素、
 * 压缩成 multimsg 包),但最后一步上传被服务端按产品权限拒掉:
 *
 * ```
 * multi_msg_upload_op.cc::OnUploadMsg  result=[-10122]
 *     err_msg=Product does not have permission to access cmd
 * ```
 *
 * 更坑的是它**不报错**:本地照样生成一张 multimsg ark 卡片,`get_forward_msg` 读得出来、
 * `message_sent` 也照发,只有 `meta.detail.resid` 是空的 —— 对方点开是空白。
 * 所以这里**根本不去调** `multiForwardMsg`,免得在群里留下点开即空的死卡片。
 *
 * 取而代之是三选一的处理方式([Config] 的 `forward_mode`,将来由 WebUI 开关驱动):
 *
 * | 模式 | 行为 |
 * |---|---|
 * | `merge_one`(默认) | 把所有节点**拼成一条**普通消息发出去 |
 * | `separate` | 每个节点**各发一条** |
 * | `silent` | 什么都不发,直接回成功(上游不会因为发不出去而反复重试) |
 */
object ForwardActions {

    private const val CHAT_PRIVATE = 1
    private const val CHAT_GROUP = 2

    const val MODE_MERGE = "merge_one"
    const val MODE_SEPARATE = "separate"
    const val MODE_SILENT = "silent"

    fun register() {
        ActionRouter.register("send_forward_msg") { p -> send(p) }
        ActionRouter.register("send_group_forward_msg") { p ->
            send(p.put("message_type", "group"))
        }
        ActionRouter.register("send_private_forward_msg") { p ->
            send(p.put("message_type", "private"))
        }
    }

    /** 一个节点:显示名 + 它的消息元素。 */
    private class Node(val name: String, val elements: ArrayList<MsgElement>)

    private fun send(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return Ob.notReady("send_forward_msg")

        val dst: Contact
        val dstChatType: Int
        val groupId = p.optLong("group_id", 0)
        if (groupId > 0 && p.optString("message_type", "group") != "private") {
            dstChatType = CHAT_GROUP
            dst = Contact(CHAT_GROUP, groupId.toString(), "")
        } else {
            val userId = p.optLong("user_id", 0)
            if (userId <= 0) return Ob.failed(Ob.RC_BAD_PARAM, "需要 group_id 或 user_id")
            val uid = UinUidStore.uid(userId)
            if (uid.isEmpty()) return Ob.failed(Ob.RC_NOT_READY, "拿不到 $userId 的 uid")
            dstChatType = CHAT_PRIVATE
            dst = Contact(CHAT_PRIVATE, uid, "")
        }

        // 单次调用可以用 mode 覆盖全局配置,方便上游按场景选择
        val mode = p.optString("mode", "").ifEmpty { Config.str("forward_mode", MODE_MERGE) }
        if (mode == MODE_SILENT) {
            PicoLog.i("forward: silent 模式,丢弃 " + dstChatType + "/" + dst.peerUid)
            return Ob.ok(
                JSONObject().put("message_id", 0).put("mode", MODE_SILENT)
                    .put("note", "forward_mode=silent,本条合并转发按配置丢弃")
            )
        }

        val raw = MsgCodec.normalize(p.opt("messages") ?: p.opt("message"))
        if (raw.length() == 0) return Ob.failed(Ob.RC_BAD_PARAM, "messages 为空")

        val nodes = ArrayList<Node>()
        for (i in 0 until raw.length()) {
            val seg = raw.optJSONObject(i) ?: continue
            if (seg.optString("type") != "node") {
                return Ob.failed(Ob.RC_BAD_PARAM, "第 $i 段不是 node")
            }
            val node = try {
                toNode(seg.optJSONObject("data") ?: JSONObject(), i)
            } catch (t: MsgCodec.UnsupportedSegment) {
                return Ob.failed(Ob.RC_UNSUPPORTED, "node[$i] 含不支持的段: " + t.type)
            } catch (t: pico.onebot.media.MediaFiles.ResolveError) {
                return Ob.failed(Ob.RC_BAD_PARAM, "node[$i] 媒体来源无法读取: " + t.message)
            } catch (t: NodeError) {
                return Ob.failed(Ob.RC_BAD_PARAM, t.message ?: "node 解析失败")
            } catch (t: Throwable) {
                return Ob.failed(Ob.RC_BAD_PARAM, "node[$i] 解析失败: " + t.javaClass.simpleName + " " + t.message)
            }
            if (node.elements.isNotEmpty()) nodes.add(node)
        }
        if (nodes.isEmpty()) return Ob.failed(Ob.RC_BAD_PARAM, "没有可发送的节点")

        return if (mode == MODE_SEPARATE) sendSeparate(dst, dstChatType, nodes)
        else sendMerged(dst, dstChatType, nodes)
    }

    private class NodeError(msg: String) : RuntimeException(msg)

    /** node 既可能带自定义 content,也可能只给一个已存在消息的 id。 */
    private fun toNode(data: JSONObject, index: Int): Node {
        val name = data.optString("nickname", "").ifEmpty { data.optString("name", "") }
        if (data.has("content")) {
            return Node(name, MsgCodec.toElements(MsgCodec.normalize(data.opt("content"))))
        }
        val refId = data.optString("id", "").ifEmpty { data.optString("message_id", "") }
        if (refId.isEmpty()) throw NodeError("node[$index] 既没有 content 也没有 id")
        val entry = MsgIdStore.byMessageId(refId.toIntOrNull() ?: -1)
            ?: throw NodeError("node[$index] 的 id 不是已知 message_id: $refId")
        val rec = loadRecord(entry.chatType, entry.peerUid, entry.msgId)
            ?: throw NodeError("node[$index] 引用的消息已取不到 (message_id=$refId)")
        val shown = name.ifEmpty {
            listOf(rec.sendNickName, rec.sendMemberName, rec.sendRemarkName)
                .firstOrNull { !it.isNullOrEmpty() } ?: ""
        }
        return Node(shown, ArrayList(rec.elements ?: emptyList()))
    }

    private fun loadRecord(chatType: Int, peerUid: String, msgId: Long): MsgRecord? {
        val svc = KernelGate.msgService() ?: return null
        val result = Promise<ArrayList<MsgRecord>>()
        return try {
            svc.getMsgsByMsgId(
                Contact(chatType, peerUid, ""), arrayListOf(msgId),
                IMsgOperateCallback { _, _, records -> result.resolve(records) }
            )
            result.await(Config.kernelTimeoutMs)?.firstOrNull()
        } catch (t: Throwable) {
            PicoLog.w("加载被引用消息失败 msgId=$msgId", t)
            null
        }
    }

    // ---------------- 模式 A:拼成一条 ----------------

    private fun sendMerged(dst: Contact, chatType: Int, nodes: List<Node>): JSONObject {
        val merged = ArrayList<MsgElement>()
        val header = Config.str("forward_merge_header", "〔合并转发〕")
        if (header.isNotEmpty()) merged.add(MsgCodec.text(header))
        for ((i, n) in nodes.withIndex()) {
            val prefix = StringBuilder()
            if (i > 0 || header.isNotEmpty()) prefix.append("\n")
            if (n.name.isNotEmpty()) prefix.append(n.name).append(": ")
            if (prefix.isNotEmpty()) merged.add(MsgCodec.text(prefix.toString()))
            merged.addAll(n.elements)
        }
        val resp = MsgActions.sendElements(dst, chatType, merged)
        if (resp.optInt("retcode", -1) == 0) {
            (resp.optJSONObject("data") ?: JSONObject()).put("mode", MODE_MERGE)
                .put("nodes", nodes.size)
            PicoLog.i("forward merge_one: " + nodes.size + " 个节点拼成一条")
        }
        return resp
    }

    // ---------------- 模式 B:逐条发 ----------------

    private fun sendSeparate(dst: Contact, chatType: Int, nodes: List<Node>): JSONObject {
        val ids = JSONArray()
        var lastId = 0
        var failed: JSONObject? = null
        for (n in nodes) {
            val elements = ArrayList<MsgElement>()
            if (n.name.isNotEmpty()) elements.add(MsgCodec.text(n.name + ": "))
            elements.addAll(n.elements)
            val resp = MsgActions.sendElements(dst, chatType, elements)
            if (resp.optInt("retcode", -1) != 0) {
                // 前面已经发出去的收不回来,所以如实报告"发了几条、断在哪"
                failed = resp
                break
            }
            val id = resp.optJSONObject("data")?.optInt("message_id", 0) ?: 0
            ids.put(id)
            lastId = id
        }
        if (failed != null) {
            return Ob.failed(
                failed.optInt("retcode", Ob.RC_KERNEL_ERROR),
                "逐条转发在第 " + (ids.length() + 1) + "/" + nodes.size + " 条中断: " +
                    failed.optString("message")
            )
        }
        PicoLog.i("forward separate: 发出 " + ids.length() + " 条")
        return Ob.ok(
            JSONObject()
                .put("message_id", lastId)
                .put("message_ids", ids)
                .put("mode", MODE_SEPARATE)
                .put("count", ids.length())
        )
    }
}

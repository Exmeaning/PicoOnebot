package pico.onebot.action

import com.tencent.qqnt.kernel.nativeinterface.GroupMemberListResult
import com.tencent.qqnt.kernel.nativeinterface.IGroupMemberListCallback
import com.tencent.qqnt.kernel.nativeinterface.IDetailInfoByUinCallback
import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import com.tencent.qqnt.kernel.nativeinterface.ProfileDetailInfo
import com.tencent.qqnt.kernel.nativeinterface.UserSimpleInfo
import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.core.Promise
import pico.onebot.kernel.ContactStore
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.NickStore
import pico.onebot.kernel.UinUidStore
import pico.onebot.proto.Ob

/**
 * 名册查询类动作。**这一组是上游框架的启动自检面** —— 多数插件一上来就调
 * `get_friend_list` / `get_group_list`,拿不到就直接判定协议端不可用,后面的动作再全也没用。
 *
 * 统一口径:
 *  - 内核只认 uid,OneBot 只认 QQ 号,两边的翻译一律走 [UinUidStore];
 *  - 查不到的对象回 1404 而不是空对象 —— 空对象会让上游以为"这个人昵称就是空的";
 *  - `no_cache=true` 才强制向服务器要,默认吃缓存(手表端账号被风控的余量很小)。
 */
object InfoActions {

    private const val SEX_MALE = 1
    private const val SEX_FEMALE = 2

    fun register() {
        ActionRouter.register("get_friend_list") { p -> friendList(p) }
        ActionRouter.register("get_group_list") { p -> groupList(p) }
        ActionRouter.register("get_group_info") { p -> groupInfo(p) }
        ActionRouter.register("get_group_member_list") { p -> memberList(p) }
        ActionRouter.register("get_group_member_info") { p -> memberInfo(p) }
        ActionRouter.register("get_stranger_info") { p -> strangerInfo(p) }
        // 不少框架把好友信息单独走一个 action,行为与 get_stranger_info 一致
        ActionRouter.register("get_friend_info") { p -> strangerInfo(p) }
    }

    // ---------------- 好友 ----------------

    private fun friendList(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return notReady("get_friend_list")
        val list = ContactStore.friends(p.optBoolean("no_cache", false))
        val arr = JSONArray()
        for (f in list) {
            if (f.uin <= 0) continue // uin 缺失的条目对 OneBot 侧毫无意义,不如不给
            arr.put(
                JSONObject()
                    .put("user_id", f.uin)
                    .put("nickname", f.nick)
                    .put("remark", f.remark)
                    .put("user_uid", f.uid)
            )
        }
        PicoLog.d("get_friend_list raw=" + list.size + " usable=" + arr.length())
        return Ob.ok(arr)
    }

    // ---------------- 群 ----------------

    private fun groupList(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return notReady("get_group_list")
        val list = ContactStore.groups(p.optBoolean("no_cache", false))
        val arr = JSONArray()
        for (g in list) arr.put(groupJson(g))
        return Ob.ok(arr)
    }

    private fun groupInfo(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return notReady("get_group_info")
        val code = p.optLong("group_id", 0)
        if (code <= 0) return bad("group_id 缺失或非法")
        val noCache = p.optBoolean("no_cache", false)
        val simple = ContactStore.group(code, noCache)
        val detail = ContactStore.groupDetail(code, noCache)
        if (simple == null && detail == null) {
            return Ob.failed(
                Ob.RC_BAD_PARAM, "群 $code 不在本账号的群列表里"
            )
        }
        val o = if (simple != null) groupJson(simple) else JSONObject().put("group_id", code)
        if (detail != null) {
            if (o.optString("group_name").isEmpty()) o.put("group_name", detail.groupName ?: "")
            o.put("member_count", detail.memberNum)
                .put("max_member_count", detail.maxMemberNum)
                .put("group_memo", detail.groupMemo ?: "")
                .put("group_remark", detail.remarkName ?: "")
                .put("owner_id", UinUidStore.uin(detail.ownerUid))
                .put("shut_up_all", detail.shutUpAllTimestamp > 0)
                .put("shut_up_me_timestamp", detail.shutUpMeTimestamp)
        }
        return Ob.ok(o)
    }

    private fun groupJson(g: com.tencent.qqnt.kernel.nativeinterface.GroupSimpleInfo): JSONObject =
        JSONObject()
            .put("group_id", g.groupCode)
            .put("group_name", g.groupName ?: "")
            .put("group_remark", g.remarkName ?: "")
            .put("member_count", g.memberCount)
            .put("max_member_count", g.maxMember)
            .put("group_create_time", g.createTime)
            .put("owner_id", g.groupOwnerId?.memberUin ?: 0L)

    // ---------------- 群成员 ----------------

    private fun memberList(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return notReady("get_group_member_list")
        val svc = KernelGate.groupService() ?: return notReady("get_group_member_list(groupService)")
        val code = p.optLong("group_id", 0)
        if (code <= 0) return bad("group_id 缺失或非法")

        val promise = Promise<GroupMemberListResult>()
        val err = arrayOf(0, "")
        try {
            svc.getAllMemberList(code, p.optBoolean("no_cache", false), IGroupMemberListCallback { c, m, r ->
                err[0] = c
                err[1] = m ?: ""
                promise.resolve(r)
            })
        } catch (t: Throwable) {
            return kernelError("getAllMemberList 调用失败: " + t.javaClass.simpleName)
        }
        val result = promise.await(Config.kernelTimeoutMs)
            ?: return Ob.failed(Ob.RC_TIMEOUT, "拉取群成员超时")
        if (err[0] != 0) return kernelError("内核拒绝: code=" + err[0] + " " + err[1])

        val infos = result.infos ?: HashMap()
        val arr = JSONArray()
        for (m in infos.values) arr.put(memberJson(code, m))
        // finish=false 说明内核只给了一页。手表账号的群通常不大,真遇到再接分页接口,
        // 但**必须说出来**,否则上游会拿着残缺名单当全量用。
        if (!result.finish) PicoLog.w("group $code member list not finish, got=" + arr.length())
        return Ob.ok(arr)
    }

    private fun memberInfo(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return notReady("get_group_member_info")
        val svc = KernelGate.groupService() ?: return notReady("get_group_member_info(groupService)")
        val code = p.optLong("group_id", 0)
        val uin = p.optLong("user_id", 0)
        if (code <= 0) return bad("group_id 缺失或非法")
        if (uin <= 0) return bad("user_id 缺失或非法")

        val uid = UinUidStore.uid(uin)
        if (uid.isEmpty()) return bad("拿不到 $uin 的 uid(该号可能不在本账号的可见范围内)")

        val promise = Promise<GroupMemberListResult>()
        try {
            svc.getMemberInfoForMqq(
                code, arrayListOf(uid), p.optBoolean("no_cache", false),
                IGroupMemberListCallback { _, _, r -> promise.resolve(r) }
            )
        } catch (t: Throwable) {
            return kernelError("getMemberInfoForMqq 调用失败: " + t.javaClass.simpleName)
        }
        val m = promise.await(Config.kernelTimeoutMs)?.infos?.get(uid)
            ?: return Ob.failed(
                Ob.RC_BAD_PARAM, "群 $code 里没有 $uin,或内核未返回"
            )
        return Ob.ok(memberJson(code, m))
    }

    private fun memberJson(groupCode: Long, m: MemberInfo): JSONObject {
        UinUidStore.feed(m.uid, m.uin)
        val card = m.cardName ?: ""
        val nick = m.nick ?: ""
        NickStore.feed(m.uid, if (card.isNotEmpty()) card else nick)
        return JSONObject()
            .put("group_id", groupCode)
            .put("user_id", if (m.uin > 0) m.uin else UinUidStore.uin(m.uid))
            .put("user_uid", m.uid ?: "")
            .put("nickname", nick)
            .put("card", card)
            .put("sex", "unknown")
            .put("age", 0)
            .put("area", "")
            .put("join_time", m.joinTime)
            .put("last_sent_time", m.lastSpeakTime)
            .put("level", m.memberLevel.toString())
            .put("role", role(m.role))
            .put("unfriendly", false)
            .put("title", m.memberSpecialTitle ?: "")
            .put("title_expire_time", m.specialTitleExpireTime)
            .put("card_changeable", false)
            .put("shut_up_timestamp", m.shutUpTime)
            .put("is_robot", m.isRobot)
    }

    /** MemberRole 是枚举,取值只可能是 OWNER/ADMIN/MEMBER/STRANGER/UNSPECIFIED。 */
    private fun role(r: Any?): String = when (r?.toString()?.uppercase()) {
        "OWNER" -> "owner"
        "ADMIN" -> "admin"
        else -> "member"
    }

    // ---------------- 陌生人 / 好友资料 ----------------

    private fun strangerInfo(p: JSONObject): JSONObject {
        if (!KernelGate.ready) return notReady("get_stranger_info")
        val uin = p.optLong("user_id", 0)
        if (uin <= 0) return bad("user_id 缺失或非法")
        val profile = KernelGate.profileService() ?: return notReady("get_stranger_info(profileService)")

        // 先走同步的本地缓存(好友/聊过天的人都在里面),够用就不打扰服务器
        if (!p.optBoolean("no_cache", false)) {
            val uid = UinUidStore.uid(uin)
            if (uid.isNotEmpty()) {
                val simple = try {
                    profile.getCoreAndBaseInfo("pico", arrayListOf(uid))?.get(uid)
                } catch (t: Throwable) {
                    null
                }
                if (simple != null && !simple.coreInfo?.nick.isNullOrEmpty()) {
                    return Ob.ok(simpleJson(uin, uid, simple))
                }
            }
        }

        // 缓存没有就按 uin 直接问服务器 —— 这条路不需要事先知道 uid
        val promise = Promise<ProfileDetailInfo>()
        try {
            profile.getUserDetailInfoByUin(uin, IDetailInfoByUinCallback { code, msg, info ->
                if (code != 0) PicoLog.d("getUserDetailInfoByUin code=$code $msg")
                promise.resolve(info)
            })
        } catch (t: Throwable) {
            return kernelError("getUserDetailInfoByUin 调用失败: " + t.javaClass.simpleName)
        }
        val d = promise.await(Config.kernelTimeoutMs)
            ?: return Ob.failed(Ob.RC_TIMEOUT, "拉取用户资料超时")
        if (!d.uid.isNullOrEmpty()) UinUidStore.feed(d.uid, if (d.uin > 0) d.uin else uin)
        NickStore.feed(d.uid, if (!d.remark.isNullOrEmpty()) d.remark else d.nick)
        return Ob.ok(detailJson(uin, d))
    }

    private fun simpleJson(uin: Long, uid: String, u: UserSimpleInfo): JSONObject {
        val core = u.coreInfo
        val base = u.baseInfo
        return JSONObject()
            .put("user_id", if (u.uin > 0) u.uin else uin)
            .put("user_uid", uid)
            .put("nickname", core?.nick ?: "")
            .put("remark", core?.remark ?: "")
            .put("sex", sex(base?.sex ?: 0))
            .put("age", base?.age ?: 0)
            .put("qid", base?.qid ?: "")
            .put("long_nick", base?.longNick ?: "")
            .put("level", 0)
            .put("is_friend", u.isBuddy)
    }

    private fun detailJson(uin: Long, d: ProfileDetailInfo): JSONObject {
        val q = d.qqLevel
        // QQ 等级的图标换算:皇冠 64 / 太阳 16 / 月亮 4 / 星星 1
        val level = if (q == null) 0 else q.crownNum * 64 + q.sunNum * 16 + q.moonNum * 4 + q.starNum
        return JSONObject()
            .put("user_id", if (d.uin > 0) d.uin else uin)
            .put("user_uid", d.uid ?: "")
            .put("nickname", d.nick ?: "")
            .put("remark", d.remark ?: "")
            .put("sex", sex(d.sex))
            .put("age", age(d.birthdayYear))
            .put("qid", d.qid ?: "")
            .put("long_nick", d.longNick ?: "")
            .put("level", level)
            .put("login_days", 0)
            .put("reg_time", d.regTime)
            .put("country", d.country ?: "")
            .put("province", d.province ?: "")
            .put("city", d.city ?: "")
            .put("birthday_year", d.birthdayYear)
            .put("birthday_month", d.birthdayMonth)
            .put("birthday_day", d.birthdayDay)
            .put("is_vip", d.vipFlag)
            .put("is_years_vip", d.yearVipFlag)
            .put("vip_level", d.vipLevel)
    }

    private fun sex(v: Int): String = when (v) {
        SEX_MALE -> "male"
        SEX_FEMALE -> "female"
        else -> "unknown"
    }

    /** 内核给的是出生年份而不是年龄;年份为 0 就老实回 0,别算出一个 2026 岁的人。 */
    private fun age(birthYear: Int): Int {
        if (birthYear <= 1900) return 0
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val a = now - birthYear
        return if (a in 0..150) a else 0
    }

    // ---------------- 小工具 ----------------

    private fun notReady(what: String) = Ob.notReady(what)
    private fun bad(msg: String) = Ob.failed(Ob.RC_BAD_PARAM, msg)
    private fun kernelError(msg: String) =
        Ob.failed(Ob.RC_KERNEL_ERROR, msg)
}

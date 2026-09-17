package pico.onebot.kernel

import com.tencent.qqnt.kernel.nativeinterface.BuddyCategory
import com.tencent.qqnt.kernel.nativeinterface.BuddyListCategory
import com.tencent.qqnt.kernel.nativeinterface.BuddyListReqType
import com.tencent.qqnt.kernel.nativeinterface.GroupDetailInfo
import com.tencent.qqnt.kernel.nativeinterface.GroupInfoSource
import com.tencent.qqnt.kernel.nativeinterface.GroupSimpleInfo
import com.tencent.qqnt.kernel.nativeinterface.IBuddyListCallback
import com.tencent.qqnt.kernel.nativeinterface.IKernelBuddyService
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.core.Promise
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 好友 / 群 的进程内名册。
 *
 * 之所以必须有这层缓存:内核的 `getGroupList(force, IOperateCallback)` **回调里没有数据** ——
 * 数据是异步从 `IKernelGroupListener.onGroupListUpdate` 推回来的。"请求"和"收货"天然分离,
 * 查询类 action 只能查缓存 + 必要时触发一次拉取再等。
 *
 * 好友侧相反:`getBuddyListFromCache` / `getBuddyNick` / `getBuddyRemark` / `getUinByUid`
 * 全是**同步**读本地库,可以直接在 action 线程里调。
 */
object ContactStore {

    // ---------------- 群 ----------------

    private val groups = ConcurrentHashMap<Long, GroupSimpleInfo>()
    private val groupDetails = ConcurrentHashMap<Long, GroupDetailInfo>()

    /** 等"下一次群列表回填"的人。用列表是因为可能有多个 action 同时在等。 */
    private val groupWaiters = CopyOnWriteArrayList<Promise<Boolean>>()
    private val detailWaiters = ConcurrentHashMap<Long, CopyOnWriteArrayList<Promise<Boolean>>>()

    @Volatile
    var groupListLoaded = false
        private set

    fun onGroupListUpdate(updateType: Any?, list: ArrayList<*>?) {
        if (list == null) return
        val remove = updateType?.toString().equals("REMOVE", true)
        var n = 0
        for (g in list) {
            val info = g as? GroupSimpleInfo ?: continue
            n++
            if (remove) groups.remove(info.groupCode) else groups[info.groupCode] = info
        }
        groupListLoaded = true
        PicoLog.d("group list update type=" + updateType + " n=" + n + " total=" + groups.size)
        releaseAll(groupWaiters)
    }

    fun onGroupDetailInfo(d: Any?) {
        val info = d as? GroupDetailInfo ?: return
        groupDetails[info.groupCode] = info
        releaseAll(detailWaiters.remove(info.groupCode))
    }

    /** 群成员信息变更的副产物:顺手喂 uid↔uin 和昵称,省掉后面一堆回查。 */
    fun onMemberInfoChange(map: Any?) {
        val m = map as? HashMap<*, *> ?: return
        for (v in m.values) {
            val mi = v as? MemberInfo ?: continue
            UinUidStore.feed(mi.uid, mi.uin)
            NickStore.feed(mi.uid, if (!mi.cardName.isNullOrEmpty()) mi.cardName else mi.nick)
        }
    }

    /**
     * 群列表快照。[refresh] 或从未回填过时先向内核要一次,再等监听器推回来。
     * 等不到就返回当前(可能是空)快照 —— 宁可返回空列表,也不要把调用方吊死。
     */
    fun groups(refresh: Boolean): List<GroupSimpleInfo> {
        if (refresh || !groupListLoaded) {
            val p = Promise<Boolean>()
            groupWaiters.add(p)
            if (requestGroupList(refresh)) p.await(Config.kernelTimeoutMs)
            groupWaiters.remove(p)
        }
        return groups.values.sortedBy { it.groupCode }
    }

    fun group(code: Long, refresh: Boolean = false): GroupSimpleInfo? {
        if (!refresh) groups[code]?.let { return it }
        groups(refresh = true)
        return groups[code]
    }

    /** 群详情。群公告、群主 uid、全员禁言状态只在这里有,GroupSimpleInfo 里没有。 */
    fun groupDetail(code: Long, refresh: Boolean): GroupDetailInfo? {
        if (!refresh) groupDetails[code]?.let { return it }
        val svc = KernelGate.groupService() ?: return groupDetails[code]
        val p = Promise<Boolean>()
        val bucket = detailWaiters.getOrPut(code) { CopyOnWriteArrayList() }
        bucket.add(p)
        try {
            svc.getGroupDetailInfo(code, GroupInfoSource.KDATACARD, IOperateCallback { c, m ->
                if (c != 0) PicoLog.w("getGroupDetailInfo code=$c $m")
            })
        } catch (t: Throwable) {
            PicoLog.d("getGroupDetailInfo failed: " + t.message)
            bucket.remove(p)
            return groupDetails[code]
        }
        p.await(Config.kernelTimeoutMs)
        bucket.remove(p)
        return groupDetails[code]
    }

    private fun requestGroupList(force: Boolean): Boolean {
        val svc = KernelGate.groupService() ?: return false
        return try {
            // 这个回调只说明"请求发出去了",真正的数据走 onGroupListUpdate
            svc.getGroupList(force, IOperateCallback { code, msg ->
                if (code != 0) PicoLog.w("getGroupList code=$code $msg")
            })
            true
        } catch (t: Throwable) {
            PicoLog.w("getGroupList threw: " + t.message)
            false
        }
    }

    // ---------------- 好友 ----------------

    class Friend(val uid: String, val uin: Long, val nick: String, val remark: String)

    /** 监听器推回来的完整好友信息(带 uin/昵称),比纯 uid 列表好用,作为补充来源。 */
    private val buddyRich = ConcurrentHashMap<String, Friend>()

    fun onBuddyListChange(list: ArrayList<*>?) {
        if (list == null) return
        for (c in list) {
            val cat = c as? BuddyCategory ?: continue
            for (u in cat.buddyList ?: ArrayList()) {
                val uid = u.uid ?: continue
                val core = u.coreInfo
                val nick = core?.nick ?: ""
                val remark = core?.remark ?: ""
                val uin = if (u.uin > 0) u.uin else (core?.uin ?: 0L)
                buddyRich[uid] = Friend(uid, uin, nick, remark)
                UinUidStore.feed(uid, uin)
                NickStore.feed(uid, if (remark.isNotEmpty()) remark else nick)
            }
        }
        PicoLog.d("buddy list change, rich=" + buddyRich.size)
    }

    /**
     * 好友列表。先拿 uid 清单(同步缓存 → 不行再走 V2 异步),
     * 再用同步批量接口补昵称/备注/QQ 号 —— 这三个都是读本地库,不走网络。
     */
    fun friends(refresh: Boolean): List<Friend> {
        val buddy = KernelGate.buddyService() ?: return buddyRich.values.sortedBy { it.uin }
        var uids = if (refresh) emptyList() else uidsFromCache(buddy)
        if (uids.isEmpty()) uids = uidsFromV2(buddy, refresh)
        if (uids.isEmpty()) uids = uidsFromCache(buddy)
        if (uids.isEmpty()) return buddyRich.values.sortedBy { it.uin }

        val out = ArrayList<Friend>(uids.size)
        // 分片:uid 清单可能上千,单次 JNI 调用塞太多没有好处
        for (chunk in uids.chunked(200)) {
            val list = ArrayList(chunk)
            val nicks = safeMap { buddy.getBuddyNick(list) }
            val remarks = safeMap { buddy.getBuddyRemark(list) }
            val uins = try {
                KernelGate.profileService()?.getUinByUid("pico", list) ?: HashMap()
            } catch (t: Throwable) {
                HashMap<String, Long>()
            }
            for (uid in chunk) {
                val rich = buddyRich[uid]
                val nick = nicks[uid]?.takeIf { it.isNotEmpty() } ?: rich?.nick ?: ""
                val remark = remarks[uid]?.takeIf { it.isNotEmpty() } ?: rich?.remark ?: ""
                val uin = (uins[uid] ?: 0L).takeIf { it > 0 } ?: rich?.uin ?: UinUidStore.uin(uid)
                if (uin > 0) UinUidStore.feed(uid, uin)
                if (nick.isNotEmpty() || remark.isNotEmpty()) {
                    NickStore.feed(uid, if (remark.isNotEmpty()) remark else nick)
                }
                out.add(Friend(uid, uin, nick, remark))
            }
        }
        return out.sortedBy { it.uin }
    }

    /**
     * 9.0.7(2563)起 `getBuddyListFromCache/getBuddyListV2` 多了一个前导 String(内核侧的 callFrom/trace 标记,
     * 宿主 ContactRepo 自己传的就是空串 "")。9.0.1/9.0.3 没有这个参数。
     */
    private const val BUDDY_CALL_FROM = ""

    private fun uidsFromCache(buddy: IKernelBuddyService): List<String> = try {
        val cats: ArrayList<BuddyListCategory>? = buddy.getBuddyListFromCache(BUDDY_CALL_FROM, BuddyListReqType.KNOMAL)
        cats?.flatMap { it.buddyUids ?: ArrayList() } ?: emptyList()
    } catch (t: Throwable) {
        PicoLog.d("getBuddyListFromCache failed: " + t.message)
        emptyList()
    }

    private fun uidsFromV2(buddy: IKernelBuddyService, force: Boolean): List<String> {
        val p = Promise<ArrayList<BuddyListCategory>>()
        return try {
            buddy.getBuddyListV2(BUDDY_CALL_FROM, force, BuddyListReqType.KNOMAL, IBuddyListCallback { code, msg, cats ->
                if (code != 0) PicoLog.w("getBuddyListV2 code=$code $msg")
                p.resolve(cats)
            })
            p.await(Config.kernelTimeoutMs)?.flatMap { it.buddyUids ?: ArrayList() } ?: emptyList()
        } catch (t: Throwable) {
            PicoLog.d("getBuddyListV2 failed: " + t.message)
            emptyList()
        }
    }

    private fun safeMap(block: () -> HashMap<String, String>?): Map<String, String> = try {
        block() ?: emptyMap()
    } catch (t: Throwable) {
        emptyMap()
    }

    // ---------------- 杂项 ----------------

    private fun releaseAll(waiters: MutableCollection<Promise<Boolean>>?) {
        if (waiters == null) return
        for (w in waiters) w.resolve(true)
        waiters.clear()
    }

    fun stats(): JSONObject = JSONObject()
        .put("groups", groups.size)
        .put("group_details", groupDetails.size)
        .put("group_list_loaded", groupListLoaded)
        .put("buddy_rich", buddyRich.size)

    /** 面板用的轻量计数,读缓存 size,不触发内核拉取。 */
    fun groupCount(): Int = groups.size
    fun friendCount(): Int = buddyRich.size
}

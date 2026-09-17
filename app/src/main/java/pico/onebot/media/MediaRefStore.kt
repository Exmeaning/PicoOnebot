package pico.onebot.media

/**
 * 收到的媒体元素 → 定位它所需的四元组。
 *
 * OneBot 的 `get_image` / `get_record` 只给一个 `file` 字符串,而内核要
 * (chatType, peerUid, msgId, elementId) 才下得下来。这层映射不记,收到的图就只能看
 * 不能取 —— 而且 rkey 直链是会过期的,放着不落地等于没有。
 *
 * 键同时登记 fileName 和 md5,因为上游回传的 `file` 两种都见过。
 */
object MediaRefStore {

    private const val CAP = 4096

    class Ref(
        val chatType: Int,
        val peerUid: String,
        val msgId: Long,
        val elementId: Long,
        val elementType: Int,
        val localPath: String,
        val fileName: String,
        val fileSize: Long,
        /**
         * 直链(图片才有)。**合并转发内部的媒体,内核下载是走不通的** ——
         * 内层记录的 msgId 不是该会话里的真实消息,`downloadRichMedia` 定位不到、
         * 永不回调。实测那条 rkey 直链能下到一模一样的原文件,所以留着它当回退路径。
         */
        val url: String = ""
    )

    private val map = LinkedHashMap<String, Ref>(256, 0.75f, false)

    @Synchronized
    fun put(ref: Ref, vararg keys: String?) {
        for (k in keys) {
            if (k.isNullOrEmpty()) continue
            map[k] = ref
        }
        while (map.size > CAP) {
            val it = map.entries.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    @Synchronized
    fun get(key: String?): Ref? {
        if (key.isNullOrEmpty()) return null
        map[key]?.let { return it }
        // 上游有时把扩展名去掉或加上,再试一次
        val bare = key.substringBeforeLast('.')
        if (bare != key) map[bare]?.let { return it }
        return map.entries.firstOrNull { it.key.startsWith(bare) }?.value
    }

    @Synchronized
    fun size(): Int = map.size
}

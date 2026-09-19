package pico.onebot.net

import pico.onebot.core.PicoLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 所有活着的 OneBot 连接(正向 WS 接进来的 + 反向 WS 连出去的)。
 * 事件"序列化一次,多路广播"就发生在这里。
 */
object Transport {

    private val conns = CopyOnWriteArrayList<WsConn>()

    fun add(c: WsConn) {
        conns.add(c)
        PicoLog.i("ws connected: " + c.tag + " role=" + c.role + " total=" + conns.size)
    }

    fun remove(c: WsConn) {
        if (conns.remove(c)) {
            PicoLog.i("ws closed: " + c.tag + " total=" + conns.size)
        }
    }

    fun broadcast(text: String) {
        for (c in conns) {
            if (c.closed) {
                remove(c)
                continue
            }
            if (c.wantsEvents) c.sendText(text)
        }
    }

    fun count(): Int = conns.size

    /** 某个网络配置项当前真正挂着的对端数量(WebUI 的在线灯只认这个,不认 enabled)。 */
    fun peers(ownerId: String): Int =
        if (ownerId.isEmpty()) 0 else conns.count { !it.closed && it.ownerId == ownerId }

    fun describe(): List<String> = conns.map { it.tag + "(" + it.role + ")" }

    fun closeAll() {
        for (c in conns) c.close()
        conns.clear()
    }
}

package pico.onebot.core

import java.net.Inet4Address
import java.net.NetworkInterface

/** 局域网地址推断:给引导面板 / 日志 / WebUI 状态用,监听 0.0.0.0 时人要知道该敲哪个 IP。 */
object NetUtil {

    /** 第一个非回环 IPv4;优先 wlan/eth 网卡(Waydroid 容器是 eth0,真机是 wlan0)。 */
    fun lanIp(): String? {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    val name = ni.name ?: ""
                    if (name.startsWith("wlan") || name.startsWith("eth")) return ip
                    if (fallback == null) fallback = ip
                }
            }
            return fallback
        } catch (t: Throwable) {
            return null
        }
    }

    /** WebUI 的可访问地址:配置里绑的是通配地址就换成局域网 IP。 */
    fun webuiUrl(): String {
        val host = Config.webuiHost
        val shown = if (host == "0.0.0.0" || host == "::" || host.isEmpty()) (lanIp() ?: "127.0.0.1") else host
        return "http://" + shown + ":" + Config.webuiPort + "/"
    }
}

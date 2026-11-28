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

    /** WebUI 的可访问地址:配置里绑的是通配地址就换成局域网 IP,并对 IPv6 主机加方括号。 */
    fun webuiUrl(): String {
        val raw = Config.webuiHost.trim().removePrefix("[").removeSuffix("]")
        val isWildcard = raw.isEmpty() ||
            raw == "0.0.0.0" ||
            raw == "::" ||
            raw == "::0" ||
            raw == "0:0:0:0:0:0:0:0" ||
            raw == "0000:0000:0000:0000:0000:0000:0000:0000"
        val shown = if (isWildcard) (lanIp() ?: "127.0.0.1") else raw
        val formatted = if (shown.contains(':') && !shown.startsWith("[")) "[$shown]" else shown
        return "http://$formatted:${Config.webuiPort}/"
    }
}

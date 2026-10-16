package dev.nekohash.tornet

import java.net.Inet4Address
import java.net.NetworkInterface

object Network {
    /**
     * Адрес телефона в локальной сети: Wi-Fi или собственная точка доступа.
     * Мобильный интернет (rmnet), VPN (tun) и loopback пропускаем.
     */
    fun lanIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { it.name.startsWith("tun") || it.name.startsWith("rmnet") || it.name.startsWith("ccmni") }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

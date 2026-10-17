package dev.nekohash.tornet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object Network {
    /**
     * Тип текущей сети: "wifi", "cellular" или "other". Блокировки на домашнем Wi-Fi и у мобильного
     * оператора часто разные, поэтому удачный способ подключения запоминается для каждого типа.
     * TorNet исключён из своего VPN, так что здесь видна настоящая сеть, а не туннель.
     */
    fun type(context: Context): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "other"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }.getOrDefault("other")

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

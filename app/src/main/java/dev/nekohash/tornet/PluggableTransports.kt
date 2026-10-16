package dev.nekohash.tornet

import IPtProxy.Controller
import IPtProxy.IPtProxy
import IPtProxy.OnTransportEvents
import android.content.Context
import java.io.File

/**
 * Pluggable transports (obfs4, snowflake, meek, webtunnel, dnstt) из IPtProxy —
 * той же библиотеки, что использует Orbot. Каждый транспорт поднимает локальный
 * SOCKS5-порт, а Tor подключается к мостам через него (`ClientTransportPlugin … socks5`).
 */
object PluggableTransports {

    private var controller: Controller? = null
    private val running = mutableSetOf<String>()

    private val events = object : OnTransportEvents {
        override fun connected(name: String?) = Unit

        override fun error(name: String?, error: Exception?) {
            TorNetStore.log(L10n.s(R.string.log_transport_error, name, error?.message))
        }

        override fun stopped(name: String?, error: Exception?) {
            if (error != null) TorNetStore.log(L10n.s(R.string.log_transport_stopped, name, error.message))
        }
    }

    @Synchronized
    private fun controller(context: Context): Controller = controller ?: run {
        val dir = File(context.filesDir, "pt_state").apply { mkdirs() }
        IPtProxy.newController(dir.absolutePath, false, false, "WARN", events).also { controller = it }
    }

    /** Имя транспорта IPtProxy для строки моста. */
    private fun ptName(transport: String) = when (transport) {
        "meek", "meek_lite" -> IPtProxy.MeekLite
        else -> transport
    }

    /**
     * Запускает нужные для этих мостов транспорты и возвращает строки torrc.
     * Для прямого подключения (пустой список) — только `UseBridges 0`.
     */
    @Synchronized
    fun torConfig(context: Context, bridges: List<BridgeLine>): List<String> {
        if (bridges.isEmpty()) return listOf("UseBridges 0")

        val c = controller(context)
        val lines = mutableListOf("UseBridges 1")
        val transports = bridges.map { it.transport }.filter { it.isNotEmpty() }.toSet()

        bridges.firstOrNull { it.transport == "snowflake" }?.let { sf ->
            // Параметры брокера берём из строки моста, как Orbot.
            c.snowflakeBrokerUrl = sf.arg("url").orEmpty()
            c.snowflakeFrontDomains = (sf.arg("fronts") ?: sf.arg("front")).orEmpty()
            c.snowflakeIceServers = sf.arg("ice").orEmpty()
            c.snowflakeAmpCacheUrl = sf.arg("ampcache").orEmpty()
        }

        for (transport in transports) {
            val name = ptName(transport)
            if (name !in running) {
                c.start(name, null)
                running += name
            }
            val port = c.port(name)
            if (port in 1..65535) lines += "ClientTransportPlugin $transport socks5 127.0.0.1:$port"
        }
        bridges.forEach { lines += "Bridge ${it.raw}" }
        return lines
    }

    /** Порт отдельного транспорта (для Moat-туннеля), запускает его при необходимости. */
    @Synchronized
    fun startSingle(context: Context, name: String): Int {
        val c = controller(context)
        if (name !in running) {
            c.start(name, null)
            running += name
        }
        return c.port(name).toInt()
    }

    @Synchronized
    fun stopAll() {
        val c = controller ?: return
        running.forEach { runCatching { c.stop(it) } }
        running.clear()
    }
}

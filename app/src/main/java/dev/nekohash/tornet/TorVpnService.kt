package dev.nekohash.tornet

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Режим VPN: весь трафик телефона заворачивается в tun-интерфейс, а оттуда
 * hev-socks5-tunnel отправляет его в SOCKS5-порт Tor.
 *
 * DNS-запросы обрабатывает mapdns внутри hev-socks5-tunnel: он отвечает приложениям
 * фиктивными адресами из 100.64.0.0/10, а при подключении передаёт в Tor исходное
 * имя хоста. Поэтому имена резолвятся на выходном узле Tor и не утекают провайдеру.
 * UDP (кроме DNS) Tor не поддерживает — такие пакеты просто отбрасываются.
 */
class TorVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var appliedSignature: String? = null
    private var socksPort = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn(intent.getIntExtra(EXTRA_SOCKS_PORT, 0))
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
            else -> {
                // Запуск системой (постоянный VPN): включаем режим VPN и Tor,
                // TorNetService сам вызовет нас с портом, когда Tor подключится.
                RotationSettings.save(this, RotationSettings.load(this).copy(vpn = true))
                TorNetService.start(this)
            }
        }
        // Если система перезапустит сервис, TorNetService заново поднимет VPN, когда Tor подключится.
        return START_NOT_STICKY
    }

    /**
     * Поднимает VPN или, если он уже работает, пересоздаёт интерфейс под новые правила
     * приложений. Новый интерфейс устанавливается до закрытия старого, так что трафик
     * в обход туннеля в этот момент не уходит.
     */
    private fun startVpn(port: Int) {
        if (port <= 0) {
            TorNetStore.log(L10n.s(R.string.vpn_log_no_port))
            return
        }
        val rules = AppRules.load(this)
        if (tun != null && rules.signature == appliedSignature && port == socksPort) return
        if (prepare(this) != null) {
            TorNetStore.log(L10n.s(R.string.vpn_log_no_permission))
            return
        }

        val fd = buildInterface(rules) ?: return
        val old = tun
        if (old != null) runCatching { TProxyService.TProxyStopService() }

        val config = File(filesDir, "tunnel.yml").apply { writeText(tunnelConfig(port)) }
        val started = runCatching { TProxyService.TProxyStartService(config.absolutePath, fd.fd) }
            .onFailure { TorNetStore.log(L10n.s(R.string.vpn_log_tun2socks_failed, it.message)) }
            .getOrDefault(false)
        if (!started) {
            fd.close()
            if (old != null) {
                runCatching { old.close() }
                tun = null
                appliedSignature = null
                TorNetStore.update { it.copy(vpnActive = false) }
            }
            return
        }
        runCatching { old?.close() }
        tun = fd
        appliedSignature = rules.signature
        socksPort = port
        TorNetStore.update { it.copy(vpnActive = true) }
        TorNetStore.log(
            if (old == null) L10n.s(R.string.vpn_log_on, AppsActivity.summary(rules))
            else L10n.s(R.string.vpn_log_rules_updated, AppsActivity.summary(rules))
        )
    }

    private fun buildInterface(rules: AppRules): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(TUN_IPV4, 32)
            .addRoute("0.0.0.0", 0)
            .addAddress(TUN_IPV6, 128)
            .addRoute("::", 0)
            .addDnsServer(DNS_IPV4)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        applyRules(builder, rules)
        return try {
            builder.establish()
        } catch (e: Exception) {
            TorNetStore.log(L10n.s(R.string.vpn_log_iface_failed, e.message))
            null
        }
    }

    /**
     * Android не разрешает смешивать списки «разрешённых» и «исключённых» приложений.
     * Сам TorNet (в нём работает Tor) всегда должен ходить в сеть мимо туннеля, иначе
     * трафик Tor зациклится: в режимах «все» и «кроме» он исключается явно, а в режиме
     * «только выбранные» его просто нет в списке.
     */
    private fun applyRules(builder: Builder, rules: AppRules) {
        fun safely(block: () -> Unit) = try {
            block(); true
        } catch (_: PackageManager.NameNotFoundException) {
            false // приложение удалено — пропускаем
        }

        when (rules.effectiveMode) {
            AppsMode.ALL -> safely { builder.addDisallowedApplication(packageName) }
            AppsMode.EXCEPT -> {
                safely { builder.addDisallowedApplication(packageName) }
                rules.packages.filter { it != packageName }.forEach { safely { builder.addDisallowedApplication(it) } }
            }
            AppsMode.ONLY -> {
                val added = rules.packages.filter { it != packageName }
                    .count { safely { builder.addAllowedApplication(it) } }
                // Все выбранные приложения удалены — безопаснее пустить через Tor всё.
                if (added == 0) safely { builder.addDisallowedApplication(packageName) }
            }
        }
    }

    private fun stopVpn() {
        val fd = tun ?: return
        runCatching { TProxyService.TProxyStopService() }
        runCatching { fd.close() }
        tun = null
        appliedSignature = null
        TorNetStore.update { it.copy(vpnActive = false) }
        TorNetStore.log(L10n.s(R.string.vpn_log_off))
    }

    override fun onRevoke() {
        // Пользователь выключил VPN в настройках или его занял другой VPN.
        TorNetStore.log(L10n.s(R.string.vpn_log_revoked))
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun tunnelConfig(socksPort: Int) = """
        tunnel:
          mtu: $MTU
          ipv4: $TUN_IPV4
          ipv6: '$TUN_IPV6'

        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: 'udp'

        mapdns:
          address: $DNS_IPV4
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000

        misc:
          task-stack-size: 81920
          connect-timeout: 30000
          log-level: warn
    """.trimIndent() + "\n"

    companion object {
        private const val ACTION_START = "dev.nekohash.tornet.VPN_START"
        private const val ACTION_STOP = "dev.nekohash.tornet.VPN_STOP"
        private const val EXTRA_SOCKS_PORT = "socks_port"

        private const val MTU = 8500
        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6 = "fc00::1"
        private const val DNS_IPV4 = "198.18.0.2"

        fun start(context: Context, socksPort: Int) {
            context.startService(
                Intent(context, TorVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SOCKS_PORT, socksPort),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TorVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

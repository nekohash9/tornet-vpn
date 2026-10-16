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
        return START_NOT_STICKY
    }

    private fun startVpn(socksPort: Int) {
        if (tun != null) return
        if (socksPort <= 0) {
            TorNetStore.log("VPN: неизвестен SOCKS-порт Tor")
            return
        }
        if (prepare(this) != null) {
            TorNetStore.log("VPN: нет разрешения, включите режим VPN из приложения")
            return
        }

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
        try {
            // Сам Tor работает в нашем процессе и должен ходить в сеть напрямую,
            // иначе его трафик зациклится в собственный туннель.
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            TorNetStore.log("VPN: не удалось создать интерфейс: ${e.message}")
            null
        } ?: return

        val config = File(filesDir, "tunnel.yml").apply { writeText(tunnelConfig(socksPort)) }
        val started = runCatching { TProxyService.TProxyStartService(config.absolutePath, fd.fd) }
            .onFailure { TorNetStore.log("VPN: tun2socks не запустился: ${it.message}") }
            .getOrDefault(false)
        if (!started) {
            fd.close()
            return
        }
        tun = fd
        TorNetStore.update { it.copy(vpnActive = true) }
        TorNetStore.log("VPN включён: весь трафик телефона идёт через Tor")
    }

    private fun stopVpn() {
        val fd = tun ?: return
        runCatching { TProxyService.TProxyStopService() }
        runCatching { fd.close() }
        tun = null
        TorNetStore.update { it.copy(vpnActive = false) }
        TorNetStore.log("VPN выключен")
    }

    override fun onRevoke() {
        // Пользователь выключил VPN в настройках или его занял другой VPN.
        TorNetStore.log("VPN отключён системой")
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

package dev.nekohash.tornet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.torproject.jni.TorService
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.random.Random

/**
 * Держит встроенный Tor запущенным и периодически просит его сменить
 * цепочку (сигнал NEWNYM) — так же, как это делает tornet на компьютере.
 */
class TorNetService : LifecycleService() {

    private var torService: TorService? = null
    private var bound = false
    private var receiverRegistered = false
    private var settings = RotationSettings()

    private var bootstrapJob: Job? = null
    private var rotationJob: Job? = null
    private var notificationJob: Job? = null
    private val changeLock = Mutex()
    private var lastChangeAt = 0L

    // Перебор способов подключения (режим «Авто»).
    private val attempts = ArrayDeque<BridgeMode>()
    private var currentMode = BridgeMode.DIRECT
    private var stallBaseline = 0
    private var stallSince = 0L
    private var stallHinted = false
    private var switching = false
    private var appliedBridges: Pair<BridgeMode, String>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            torService = (binder as? TorService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torService = null
        }
    }

    private val torReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TorService.ACTION_STATUS ->
                    if (intent.getStringExtra(TorService.EXTRA_STATUS) == TorService.STATUS_ON) onTorReady()

                TorService.ACTION_ERROR ->
                    TorNetStore.log(L10n.s(R.string.log_tor_error, intent.getStringExtra(Intent.EXTRA_TEXT)))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_NEW_IP -> lifecycleScope.launch { changeIp(manual = true) }
            ACTION_SYNC_LAN -> {
                settings = RotationSettings.load(this).normalized()
                lifecycleScope.launch { syncLan() }
            }
            ACTION_STOP -> stop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        settings = RotationSettings.load(this).normalized()
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(TorNetStore.state.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        watchNotification()

        val status = TorNetStore.state.value.status
        if (bound && settings.bridgeMode to settings.customBridges != appliedBridges) {
            // Поменяли способ подключения на ходу — переподключаемся без перезапуска Tor.
            TorNetStore.log(L10n.s(R.string.log_new_method, settings.bridgeMode.title))
            rotationJob?.cancel()
            TorNetStore.update {
                it.copy(status = TorStatus.STARTING, changes = 0, maxChanges = settings.count, nextChangeAt = 0)
            }
            bootstrapJob?.cancel()
            bootstrapJob = lifecycleScope.launch {
                resetAttempts()
                nextAttempt(firstStart = false)
                watchBootstrap()
            }
            return
        }
        if (status == TorStatus.ON) {
            // Уже работаем — просто применяем новые настройки.
            TorNetStore.update { it.copy(changes = 0, maxChanges = settings.count) }
            TorNetStore.log(L10n.s(R.string.log_new_settings, describe(settings)))
            syncVpn()
            lifecycleScope.launch { syncLan() }
            startRotation()
            return
        }
        if (bound) return

        TorNetStore.reset()
        TorNetStore.update { it.copy(status = TorStatus.STARTING, maxChanges = settings.count) }
        TorNetStore.log(L10n.s(R.string.log_starting, describe(settings)))

        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(torReceiver, filter)
        receiverRegistered = true

        lifecycleScope.launch {
            // Конфиг первого способа подключения должен лежать в torrc до старта Tor.
            resetAttempts()
            if (!nextAttempt(firstStart = true)) return@launch
            bound = bindService(Intent(this@TorNetService, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                TorNetStore.log(L10n.s(R.string.log_torservice_failed))
                stop()
                return@launch
            }
            bootstrapJob = lifecycleScope.launch { watchBootstrap() }
        }
    }

    // --- Мосты ---

    private fun resetAttempts() {
        attempts.clear()
        val s = settings
        if (s.bridgeMode != BridgeMode.AUTO) {
            attempts += s.bridgeMode
        } else {
            // Сначала то, что сработало в прошлый раз, потом от быстрого к самому живучему.
            val order = mutableListOf<BridgeMode>()
            if (BridgeLine.parse(s.customBridges).isNotEmpty()) order += BridgeMode.CUSTOM
            RotationSettings.lastWorking(this)?.let { order += it }
            order += listOf(
                BridgeMode.DIRECT, BridgeMode.MOAT, BridgeMode.OBFS4, BridgeMode.SNOWFLAKE,
                BridgeMode.DNSTT, BridgeMode.MEEK,
            )
            attempts += order.distinct()
        }
        appliedBridges = s.bridgeMode to s.customBridges
    }

    /** Мосты для способа подключения; null — способ сейчас неприменим. */
    private suspend fun bridgesFor(mode: BridgeMode): List<BridgeLine>? = when (mode) {
        BridgeMode.AUTO -> null
        BridgeMode.DIRECT -> emptyList()
        BridgeMode.CUSTOM -> BridgeLine.parse(settings.customBridges).ifEmpty {
            TorNetStore.log(L10n.s(R.string.log_no_custom))
            null
        }
        BridgeMode.MOAT -> {
            TorNetStore.log(L10n.s(R.string.log_moat_request))
            val fresh = withContext(Dispatchers.IO) { runCatching { Moat.fetchBridges(this@TorNetService) } }
            fresh.onSuccess {
                if (it.isNotEmpty()) RotationSettings.setMoatBridges(this, it)
                TorNetStore.log(L10n.s(R.string.log_moat_got, it.size))
            }.onFailure { TorNetStore.log(L10n.s(R.string.log_moat_failed, it.message)) }
            (fresh.getOrNull()?.takeIf { it.isNotEmpty() } ?: RotationSettings.moatBridges(this)).ifEmpty { null }
        }
        else -> BuiltInBridges.get(this, mode).ifEmpty { null }
    }

    /**
     * Берёт следующий способ из очереди и применяет его: до старта Tor — через torrc,
     * на работающем Tor — через SETCONF. В режиме «Авто» очередь крутится по кругу.
     */
    private suspend fun nextAttempt(firstStart: Boolean): Boolean {
        repeat(32) {
            if (attempts.isEmpty()) {
                if (settings.bridgeMode != BridgeMode.AUTO) return false
                TorNetStore.log(L10n.s(R.string.log_next_round))
                resetAttempts()
            }
            val mode = attempts.removeFirst()
            val bridges = bridgesFor(mode) ?: return@repeat
            val config = withContext(Dispatchers.IO) {
                runCatching { PluggableTransports.torConfig(this@TorNetService, bridges) }
            }.getOrElse {
                TorNetStore.log(L10n.s(R.string.log_transport_start_failed, mode.title, it.message))
                return@repeat
            }
            val applied = if (firstStart) {
                withContext(Dispatchers.IO) {
                    runCatching { TorService.getTorrc(this@TorNetService).writeText(config.joinToString("\n") + "\n") }
                }.isSuccess
            } else {
                applyToRunningTor(config)
            }
            if (!applied) return@repeat

            currentMode = mode
            stallBaseline = TorNetStore.state.value.bootstrap
            stallSince = System.currentTimeMillis()
            stallHinted = false
            TorNetStore.update { it.copy(transport = mode.title) }
            TorNetStore.log(
                if (bridges.isEmpty()) L10n.s(R.string.log_connecting, mode.title)
                else L10n.s(R.string.log_connecting_bridges, mode.title, bridges.size)
            )
            return true
        }
        return false
    }

    private suspend fun applyToRunningTor(config: List<String>): Boolean = withContext(Dispatchers.IO) {
        val control = torService?.torControlConnection ?: return@withContext false
        runCatching {
            // Выключаем сеть, меняем мосты и включаем обратно — Tor начинает подключение заново.
            control.setConf("DisableNetwork", "1")
            control.resetConf(listOf("UseBridges", "Bridge", "ClientTransportPlugin"))
            control.setConf(config)
            control.setConf("DisableNetwork", "0")
        }.onFailure { TorNetStore.log(L10n.s(R.string.log_bridges_apply_failed, it.message)) }.isSuccess
    }

    /** Нет прогресса дольше таймаута — в «Авто» переходим к следующему способу. */
    private suspend fun checkStall(progress: Int) {
        if (progress >= 100 || switching) return
        val now = System.currentTimeMillis()
        if (progress > stallBaseline) {
            stallBaseline = progress
            stallSince = now
            return
        }
        if (now - stallSince < currentMode.stallSeconds * 1000L) return

        if (settings.bridgeMode == BridgeMode.AUTO) {
            TorNetStore.log(L10n.s(R.string.log_stall_next, currentMode.title, currentMode.stallSeconds))
            switching = true
            try {
                nextAttempt(firstStart = false)
            } finally {
                switching = false
            }
        } else if (!stallHinted) {
            stallHinted = true
            TorNetStore.log(L10n.s(R.string.log_stall_hint, currentMode.title))
        }
    }

    /** Опрашивает Tor о ходе подключения, пока он не будет готов. */
    private suspend fun watchBootstrap() {
        var lastLogged = -1
        while (currentCoroutineContext().isActive && TorNetStore.state.value.status == TorStatus.STARTING) {
            val phase = withContext(Dispatchers.IO) {
                runCatching { torService?.getInfo("status/bootstrap-phase") }.getOrNull()
            }
            if (phase != null) {
                val progress = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val summary = Regex("SUMMARY=\"([^\"]*)\"").find(phase)?.groupValues?.get(1).orEmpty()
                TorNetStore.update { it.copy(bootstrap = progress, bootstrapSummary = summary) }
                if (progress / 25 != lastLogged / 25) {
                    TorNetStore.log(L10n.s(R.string.log_bootstrap, progress, summary))
                    lastLogged = progress
                }
                if (progress >= 100) onTorReady() else checkStall(progress)
            }
            delay(1000)
        }
    }

    private fun onTorReady() {
        val tor = torService ?: return
        if (TorNetStore.state.value.status != TorStatus.STARTING) return

        val socks = tor.getSocksPort().takeIf { it > 0 } ?: tor.listenerPort("net/listeners/socks")
        val http = tor.getHttpTunnelPort().takeIf { it > 0 } ?: tor.listenerPort("net/listeners/httptunnel")
        TorNetStore.update {
            it.copy(status = TorStatus.ON, bootstrap = 100, socksPort = socks, httpPort = http)
        }
        TorNetStore.log(L10n.s(R.string.log_connected, currentMode.title, socks, http))
        RotationSettings.setLastWorking(this, currentMode)
        lastChangeAt = System.currentTimeMillis()

        syncVpn()
        lifecycleScope.launch {
            syncLan()
            refreshIp()
            startRotation()
        }
    }

    private fun startRotation() {
        rotationJob?.cancel()
        rotationJob = lifecycleScope.launch {
            while (isActive) {
                val s = settings
                if (s.count > 0 && TorNetStore.state.value.changes >= s.count) {
                    TorNetStore.update { it.copy(nextChangeAt = 0) }
                    TorNetStore.log(L10n.s(R.string.log_done, s.count))
                    break
                }
                val seconds = Random.nextInt(s.minInterval, s.maxInterval + 1)
                TorNetStore.update { it.copy(nextChangeAt = System.currentTimeMillis() + seconds * 1000L) }
                delay(seconds * 1000L)
                if (changeIp(manual = false)) {
                    TorNetStore.update { it.copy(changes = it.changes + 1) }
                }
            }
        }
    }

    /** Отправляет NEWNYM и узнаёт новый внешний IP. */
    private suspend fun changeIp(manual: Boolean): Boolean = changeLock.withLock {
        val control = torService?.torControlConnection
        if (TorNetStore.state.value.status != TorStatus.ON || control == null) {
            if (manual) TorNetStore.log(L10n.s(R.string.log_not_connected))
            return false
        }
        val wait = RotationSettings.MIN_INTERVAL * 1000L - (System.currentTimeMillis() - lastChangeAt)
        if (manual && wait > 0) {
            TorNetStore.log(L10n.s(R.string.log_rate_limit, (wait + 999) / 1000))
            return false
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching { control.signal("NEWNYM") }
                .onFailure { TorNetStore.log(L10n.s(R.string.log_newnym_failed, it.message)) }
                .isSuccess
        }
        if (!ok) return false
        lastChangeAt = System.currentTimeMillis()
        TorNetStore.log(L10n.s(if (manual) R.string.log_change_manual else R.string.log_change_timer))
        // Даём Tor собрать новую цепочку.
        delay(1500)
        refreshIp()
        true
    }

    private suspend fun refreshIp() {
        val port = TorNetStore.state.value.httpPort
        if (port <= 0) return
        repeat(3) { attempt ->
            val ip = withContext(Dispatchers.IO) { runCatching { fetchExitIp(port) } }
            ip.onSuccess { address ->
                val old = TorNetStore.state.value.currentIp
                TorNetStore.update { it.copy(currentIp = address) }
                TorNetStore.log(
                    if (old == null || old != address) L10n.s(R.string.log_new_ip, address)
                    else L10n.s(R.string.log_same_ip, address)
                )
                return
            }
            if (attempt == 2) TorNetStore.log(L10n.s(R.string.log_ip_failed, ip.exceptionOrNull()?.message))
            delay(2000)
        }
    }

    /** Поднимает или опускает VPN в соответствии с настройкой. */
    private fun syncVpn() {
        val state = TorNetStore.state.value
        if (settings.vpn && state.status == TorStatus.ON) {
            TorVpnService.start(this, state.socksPort)
        } else if (!settings.vpn && state.vpnActive) {
            TorVpnService.stop(this)
        }
    }

    /**
     * Раздача прокси в локальной сети: добавляем Tor второй SOCKS-порт на 0.0.0.0.
     * Основной порт остаётся только на 127.0.0.1.
     */
    private suspend fun syncLan() {
        val state = TorNetStore.state.value
        if (state.status != TorStatus.ON) return
        val want = settings.shareLan
        if (want == (state.lanPort > 0)) return
        val control = torService?.torControlConnection ?: return
        val ports = mutableListOf("SocksPort 127.0.0.1:${state.socksPort}")
        if (want) ports += "SocksPort 0.0.0.0:$LAN_SOCKS_PORT"
        val ok = withContext(Dispatchers.IO) {
            runCatching { control.setConf(ports) }
                .onFailure { TorNetStore.log(L10n.s(R.string.log_lan_failed, it.message)) }
                .isSuccess
        }
        if (!ok) return
        TorNetStore.update { it.copy(lanPort = if (want) LAN_SOCKS_PORT else 0) }
        TorNetStore.log(
            if (want) L10n.s(R.string.log_lan_on, LAN_SOCKS_PORT)
            else L10n.s(R.string.log_lan_off)
        )
    }

    private fun stop() {
        if (TorNetStore.state.value.vpnActive) TorVpnService.stop(this)
        bootstrapJob?.cancel()
        rotationJob?.cancel()
        if (TorNetStore.state.value.status != TorStatus.OFF) {
            TorNetStore.update { it.copy(status = TorStatus.STOPPING, nextChangeAt = 0) }
            TorNetStore.log(L10n.s(R.string.log_stopping))
        }
        releaseTor()
        TorNetStore.reset()
        notificationJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseTor() {
        PluggableTransports.stopAll()
        appliedBridges = null
        if (receiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(torReceiver)
            receiverRegistered = false
        }
        if (bound) {
            // После отвязки TorService сам останавливает tor в onDestroy().
            unbindService(connection)
            bound = false
        }
        torService = null
    }

    override fun onDestroy() {
        releaseTor()
        if (TorNetStore.state.value.status != TorStatus.OFF) TorNetStore.reset()
        super.onDestroy()
    }

    // --- Уведомление ---

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID, L10n.s(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun watchNotification() {
        if (notificationJob?.isActive == true) return
        notificationJob = lifecycleScope.launch {
            TorNetStore.state
                .map { Triple(it.status, it.currentIp, it.changes) to (it.bootstrap to it.vpnActive) }
                .distinctUntilChanged()
                .collect {
                    val state = TorNetStore.state.value
                    if (state.status == TorStatus.OFF) return@collect
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(state))
                }
        }
    }

    private fun buildNotification(state: TorNetState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (state.status) {
            TorStatus.ON -> L10n.s(R.string.notif_ip, state.currentIp ?: "…")
            TorStatus.STARTING -> L10n.s(R.string.notif_connecting, state.bootstrap)
            else -> getString(R.string.app_name)
        }
        val text = when (state.status) {
            TorStatus.ON -> {
                val mode = if (state.vpnActive) L10n.s(R.string.notif_vpn)
                else "SOCKS5 :${state.socksPort} · HTTP :${state.httpPort}"
                if (state.maxChanges > 0) L10n.s(R.string.notif_changes_of, state.changes, state.maxChanges, mode)
                else L10n.s(R.string.notif_changes, state.changes, mode)
            }
            else -> state.bootstrapSummary.ifEmpty { L10n.s(R.string.notif_starting) }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state.status == TorStatus.STARTING) builder.setProgress(100, state.bootstrap, false)
        if (state.status == TorStatus.ON) {
            builder.addAction(0, L10n.s(R.string.new_ip), servicePendingIntent(ACTION_NEW_IP, 1))
        }
        builder.addAction(0, L10n.s(R.string.stop), servicePendingIntent(ACTION_STOP, 2))
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, TorNetService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ACTION_START = "dev.nekohash.tornet.START"
        const val ACTION_STOP = "dev.nekohash.tornet.STOP"
        const val ACTION_NEW_IP = "dev.nekohash.tornet.NEW_IP"
        const val ACTION_SYNC_LAN = "dev.nekohash.tornet.SYNC_LAN"

        /** Порт, как у Tor Browser, — его знают многие клиенты. */
        const val LAN_SOCKS_PORT = 9150

        private const val CHANNEL_ID = "tornet"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, TorNetService::class.java).setAction(ACTION_START),
        )

        fun send(context: Context, action: String) {
            context.startService(Intent(context, TorNetService::class.java).setAction(action))
        }

        fun describe(s: RotationSettings): String {
            val interval = if (s.minInterval == s.maxInterval) L10n.s(R.string.describe_every, s.minInterval)
            else L10n.s(R.string.describe_every_range, s.minInterval, s.maxInterval)
            val count = if (s.count == 0) L10n.s(R.string.describe_unlimited) else L10n.s(R.string.describe_times, s.count)
            val vpn = if (s.vpn) ", VPN" else ""
            return "$interval, $count$vpn"
        }

        /** Запрос IP через HTTP-туннель Tor, так что DNS тоже идёт через Tor. */
        fun fetchExitIp(httpPort: Int): String {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
            var error: Exception = IllegalStateException(L10n.s(R.string.err_no_response))
            for (url in IP_SERVICES) {
                val conn = URL(url).openConnection(proxy) as HttpURLConnection
                try {
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 30_000
                    conn.useCaches = false
                    // Новое соединение = новая цепочка после NEWNYM.
                    conn.setRequestProperty("Connection", "close")
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val ip = json.optString("IP").ifEmpty { json.optString("ip") }
                    if (ip.isNotEmpty()) return ip
                } catch (e: Exception) {
                    error = e
                } finally {
                    conn.disconnect()
                }
            }
            throw error
        }

        private val IP_SERVICES = listOf(
            "https://check.torproject.org/api/ip",
            "https://api.ipify.org?format=json",
        )
    }
}

/** Разбирает ответ вида `"127.0.0.1:9050"` из GETINFO net/listeners/…. */
private fun TorService.listenerPort(key: String): Int =
    runCatching { getInfo(key) }.getOrNull()
        ?.substringAfterLast(':')?.trim('"', ' ')?.toIntOrNull() ?: 0

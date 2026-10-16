package dev.nekohash.tornet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TorStatus { OFF, STARTING, ON, STOPPING }

data class TorNetState(
    val status: TorStatus = TorStatus.OFF,
    val bootstrap: Int = 0,
    val bootstrapSummary: String = "",
    val socksPort: Int = 0,
    val httpPort: Int = 0,
    val currentIp: String? = null,
    /** Страна текущего выхода по базе GeoIP Tor (код вроде "de"). */
    val exitCountry: String? = null,
    val changes: Int = 0,
    /** 0 — менять бесконечно, как `--count 0` в tornet. */
    val maxChanges: Int = 0,
    /** Время следующей автоматической смены IP (System.currentTimeMillis), 0 — не запланирована. */
    val nextChangeAt: Long = 0,
    /** Поднят ли VPN-туннель, через который идёт весь трафик. */
    val vpnActive: Boolean = false,
    /** Чем сейчас подключаемся: «без мостов», «Snowflake», … */
    val transport: String = "",
    /** Порт SOCKS5 для других устройств в локальной сети, 0 — раздача выключена. */
    val lanPort: Int = 0,
    val log: List<String> = emptyList(),
)

/** Общее состояние сервиса, на которое подписывается экран. */
object TorNetStore {
    private const val LOG_LIMIT = 100
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _state = MutableStateFlow(TorNetState())
    val state: StateFlow<TorNetState> = _state.asStateFlow()

    fun update(block: (TorNetState) -> TorNetState) = _state.update(block)

    fun log(message: String) = _state.update {
        val line = "${timeFormat.format(Date())}  $message"
        it.copy(log = (it.log + line).takeLast(LOG_LIMIT))
    }

    fun reset() = _state.update { TorNetState(log = it.log) }
}

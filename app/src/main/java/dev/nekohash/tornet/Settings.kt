package dev.nekohash.tornet

import android.content.Context

/** Параметры смены IP — аналог `--interval` и `--count` из tornet. */
data class RotationSettings(
    val minInterval: Int = 60,
    val maxInterval: Int = 60,
    val count: Int = 0,
    /** Пускать весь трафик телефона через Tor (VpnService + tun2socks). */
    val vpn: Boolean = false,
    /** Как подключаться к Tor: напрямую, через мосты или перебором (AUTO). */
    val bridgeMode: BridgeMode = BridgeMode.AUTO,
    /** Свои мосты, по строке на мост. */
    val customBridges: String = "",
    /** Открыть SOCKS5-прокси для других устройств в Wi-Fi / точке доступа. */
    val shareLan: Boolean = false,
) {
    fun normalized(): RotationSettings {
        // Tor пропускает NEWNYM чаще, чем раз в 10 секунд, так что меньше нет смысла.
        val min = minInterval.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        val max = maxInterval.coerceIn(min, MAX_INTERVAL)
        return copy(minInterval = min, maxInterval = max, count = count.coerceAtLeast(0), customBridges = customBridges.trim())
    }

    companion object {
        const val MIN_INTERVAL = 10
        const val MAX_INTERVAL = 24 * 60 * 60

        private const val PREFS = "tornet"

        fun load(context: Context): RotationSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return RotationSettings(
                p.getInt("min_interval", 60),
                p.getInt("max_interval", 60),
                p.getInt("count", 0),
                p.getBoolean("vpn", false),
                BridgeMode.fromId(p.getString("bridge_mode", null)),
                p.getString("custom_bridges", "").orEmpty(),
                p.getBoolean("share_lan", false),
            )
        }

        fun save(context: Context, s: RotationSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("min_interval", s.minInterval)
                .putInt("max_interval", s.maxInterval)
                .putInt("count", s.count)
                .putBoolean("vpn", s.vpn)
                .putString("bridge_mode", s.bridgeMode.id)
                .putString("custom_bridges", s.customBridges)
                .putBoolean("share_lan", s.shareLan)
                .apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Способ, которым удалось подключиться в прошлый раз, — в режиме «Авто» пробуем его первым. */
        fun lastWorking(context: Context) =
            prefs(context).getString("last_working", null)?.let { BridgeMode.fromId(it) }

        fun setLastWorking(context: Context, mode: BridgeMode) =
            prefs(context).edit().putString("last_working", mode.id).apply()

        /** Мосты, выданные Moat в прошлый раз. */
        fun moatBridges(context: Context) =
            BridgeLine.parse(prefs(context).getString("moat_bridges", "").orEmpty())

        fun setMoatBridges(context: Context, bridges: List<BridgeLine>) =
            prefs(context).edit().putString("moat_bridges", bridges.joinToString("\n") { it.raw }).apply()
    }
}

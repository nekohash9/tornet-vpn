package dev.nekohash.tornet

import android.content.Context

/** Параметры смены IP — аналог `--interval` и `--count` из tornet. */
data class RotationSettings(
    /** Автоматическая смена IP. По умолчанию выключена: частая смена рвёт сессии и чаще вызывает капчи. */
    val rotate: Boolean = false,
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
    /** Приоритетный регион: коды стран выхода через запятую, пусто — любые. */
    val exitCountries: String = "",
) {
    fun normalized(): RotationSettings {
        // Tor пропускает NEWNYM чаще, чем раз в 10 секунд, так что меньше нет смысла.
        val min = minInterval.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        val max = maxInterval.coerceIn(min, MAX_INTERVAL)
        return copy(minInterval = min, maxInterval = max, count = count.coerceAtLeast(0), customBridges = customBridges.trim(),
            exitCountries = ExitCountries.parse(exitCountries).joinToString(","))
    }

    companion object {
        const val MIN_INTERVAL = 10
        const val MAX_INTERVAL = 24 * 60 * 60

        private const val PREFS = "tornet"

        fun load(context: Context): RotationSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return RotationSettings(
                p.getBoolean("rotate", false),
                p.getInt("min_interval", 60),
                p.getInt("max_interval", 60),
                p.getInt("count", 0),
                p.getBoolean("vpn", false),
                BridgeMode.fromId(p.getString("bridge_mode", null)),
                p.getString("custom_bridges", "").orEmpty(),
                p.getBoolean("share_lan", false),
                p.getString("exit_countries", "").orEmpty(),
            )
        }

        fun save(context: Context, s: RotationSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("rotate", s.rotate)
                .putInt("min_interval", s.minInterval)
                .putInt("max_interval", s.maxInterval)
                .putInt("count", s.count)
                .putBoolean("vpn", s.vpn)
                .putString("bridge_mode", s.bridgeMode.id)
                .putString("custom_bridges", s.customBridges)
                .putBoolean("share_lan", s.shareLan)
                .putString("exit_countries", s.exitCountries)
                .apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Способ, которым удалось подключиться в прошлый раз, — в режиме «Авто» пробуем его первым. */
        fun lastWorking(context: Context, network: String) =
            (prefs(context).getString("last_working_$network", null) ?: prefs(context).getString("last_working", null))
                ?.let { BridgeMode.fromId(it) }

        fun setLastWorking(context: Context, network: String, mode: BridgeMode) =
            prefs(context).edit().putString("last_working_$network", mode.id).putString("last_working", mode.id).apply()

        /** Когда Moat в последний раз не ответил — чтобы «Авто» не тратил на него время каждый раз. */
        fun moatFailedAt(context: Context) = prefs(context).getLong("moat_failed_at", 0L)

        fun setMoatFailedAt(context: Context, time: Long) =
            prefs(context).edit().putLong("moat_failed_at", time).apply()

        /** Мосты, выданные Moat в прошлый раз. */
        fun moatBridges(context: Context) =
            BridgeLine.parse(prefs(context).getString("moat_bridges", "").orEmpty())

        fun setMoatBridges(context: Context, bridges: List<BridgeLine>) =
            prefs(context).edit().putString("moat_bridges", bridges.joinToString("\n") { it.raw }).apply()
    }
}

/** Какие приложения идут через VPN. */
enum class AppsMode(val id: String) {
    /** Все, кроме самого TorNet. */
    ALL("all"),
    ONLY("only"),
    EXCEPT("except");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/** Раздельное туннелирование: режим и выбранные пакеты. */
data class AppRules(val mode: AppsMode = AppsMode.ALL, val packages: Set<String> = emptySet()) {
    /** «Только выбранные» без выбранных приложений равносилен «Всем» — так безопаснее, чем пустой VPN. */
    val effectiveMode: AppsMode get() = if (mode == AppsMode.ONLY && packages.isEmpty()) AppsMode.ALL else mode

    /** Меняется при любом изменении правил — по нему VPN понимает, что интерфейс надо пересоздать. */
    val signature: String get() = effectiveMode.id + ":" + packages.sorted().joinToString(",")

    companion object {
        private const val PREFS = "tornet"

        fun load(context: Context): AppRules {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppRules(
                AppsMode.fromId(p.getString("apps_mode", null)),
                p.getStringSet("apps", emptySet()).orEmpty().toSet(),
            )
        }

        fun save(context: Context, rules: AppRules) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("apps_mode", rules.mode.id)
                .putStringSet("apps", rules.packages)
                .apply()
        }
    }
}

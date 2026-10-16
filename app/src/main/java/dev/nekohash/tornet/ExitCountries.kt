package dev.nekohash.tornet

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Приоритетный регион: Tor строит цепочки только через выходные узлы из выбранных стран
 * (`ExitNodes {de},{nl}`). Для этого Tor нужна база GeoIP — она кладётся в assets при сборке.
 */
object ExitCountries {
    private const val ASSET = "geoip"

    /** Страны, где обычно много выходных узлов Tor. */
    val COMMON = listOf(
        "de", "nl", "fr", "ch", "se", "fi", "no", "at", "is", "lu", "gb", "ie", "pl", "cz",
        "ro", "bg", "md", "ee", "lv", "lt", "es", "it", "dk", "be", "us", "ca", "jp", "sg",
        "hk", "au",
    )

    fun parse(value: String): List<String> =
        value.split(',', ' ').map { it.trim().lowercase() }.filter { it.length == 2 }.distinct()

    /** Копирует базу из APK в файлы приложения (Tor читает только обычный файл). */
    fun geoipFile(context: Context): File? {
        val target = File(context.filesDir, ASSET)
        return runCatching {
            // Обновляем копию после каждого обновления приложения — в нём может быть свежая база.
            val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            if (!target.exists() || target.length() == 0L || target.lastModified() < updated) copy(context, target)
            target
        }.getOrNull()
    }

    private fun copy(context: Context, target: File) {
        val tmp = File(target.path + ".tmp")
        context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(target)
    }

    /** Строки torrc: база GeoIP и, если выбраны страны, ограничение выходов. */
    fun torConfig(context: Context, countries: List<String>): List<String> {
        val geoip = geoipFile(context) ?: return emptyList()
        val lines = mutableListOf("GeoIPFile ${geoip.absolutePath}")
        if (countries.isNotEmpty()) lines += exitNodes(countries)
        return lines
    }

    fun exitNodes(countries: List<String>) = "ExitNodes " + countries.joinToString(",") { "{$it}" }

    fun flag(cc: String): String {
        if (cc.length != 2 || !cc.all { it.isLetter() }) return ""
        return cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** «🇩🇪 Германия» на языке приложения. */
    fun label(cc: String): String {
        val language = L10n.current().ifEmpty { Locale.getDefault().language }
        val name = Locale("", cc.uppercase()).getDisplayCountry(Locale(language))
        return "${flag(cc)} ${name.ifEmpty { cc.uppercase() }}"
    }
}

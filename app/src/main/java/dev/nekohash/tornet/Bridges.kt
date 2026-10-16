package dev.nekohash.tornet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Способ подключения к сети Tor. */
enum class BridgeMode(val id: String, val title: String, val stallSeconds: Int) {
    AUTO("auto", "Авто", 0),
    DIRECT("direct", "без мостов", 40),
    SNOWFLAKE("snowflake", "Snowflake", 120),
    OBFS4("obfs4", "obfs4", 75),
    MEEK("meek", "meek", 90),
    DNSTT("dnstt", "dnstt (через DNS)", 120),

    /** Мосты, полученные у Tor Project через Moat под нашу страну. */
    MOAT("moat", "мосты от Tor Project", 90),
    CUSTOM("custom", "свои мосты", 90);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Одна строка моста в формате torrc: `obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0`. */
data class BridgeLine(val raw: String) {
    /** Имя транспорта, пусто для обычного (vanilla) моста. */
    val transport: String
        get() {
            val first = raw.substringBefore(' ')
            return if (first.firstOrNull()?.isLetter() == true) first else ""
        }

    /** Параметры вида key=value из строки моста. */
    fun arg(key: String): String? =
        raw.split(' ').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    companion object {
        /** Разбирает вставленный текст: по строке на мост, префикс «Bridge » и пустые строки игнорируются. */
        fun parse(text: String): List<BridgeLine> = text.lines()
            .map { it.trim().removePrefix("Bridge ").removePrefix("bridge ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { BridgeLine(it) }
    }
}

/**
 * Встроенные мосты Tor Browser / Orbot. Лежат в assets, а свежие версии,
 * полученные через Moat, сохраняются в файл и имеют приоритет.
 */
object BuiltInBridges {
    private const val ASSET = "builtin-bridges.json"
    private const val UPDATED = "builtin-bridges-updated.json"

    // Из Orbot: dnstt ходит через DNS-over-HTTPS Google, его почти невозможно заблокировать точечно.
    private val DNSTT = listOf(
        "dnstt 192.0.2.4:1 A998F319ADB60EE344540EC4B21524CC484F96BE doh=https://dns.google/dns-query pubkey=241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f domain=t.ruhnama.net",
        "dnstt 192.0.2.4:2 80EEFA4F4875ED2B7B5A86DF2D7588AD32E29F15 doh=https://dns.google/dns-query pubkey=a2fb71077eeaa54a02cda7a90be306af5d299ab21822a8b277d4eacbc9168631 domain=t2.bypasscensorship.org",
    ).map { BridgeLine(it) }

    @Volatile
    private var cache: Map<String, List<BridgeLine>>? = null

    fun get(context: Context, mode: BridgeMode): List<BridgeLine> {
        val all = load(context)
        return when (mode) {
            BridgeMode.SNOWFLAKE -> all["snowflake"].orEmpty()
            BridgeMode.OBFS4 -> all["obfs4"].orEmpty()
            BridgeMode.MEEK -> all["meek"] ?: all["meek-azure"].orEmpty()
            BridgeMode.DNSTT -> all["dnstt"]?.takeIf { it.isNotEmpty() } ?: DNSTT
            else -> emptyList()
        }
    }

    /** Сохраняет обновлённый список (ответ Moat `builtin`). */
    fun update(context: Context, json: JSONObject) {
        val parsed = parse(json)
        if (parsed.values.all { it.isEmpty() }) return
        File(context.filesDir, UPDATED).writeText(json.toString())
        cache = null
    }

    private fun load(context: Context): Map<String, List<BridgeLine>> {
        cache?.let { return it }
        val updated = File(context.filesDir, UPDATED)
        val text = runCatching { updated.readText() }.getOrNull()
            ?: context.assets.open(ASSET).bufferedReader().use { it.readText() }
        return parse(JSONObject(text)).also { cache = it }
    }

    private fun parse(json: JSONObject): Map<String, List<BridgeLine>> =
        json.keys().asSequence().associateWith { key ->
            val arr = json.optJSONArray(key) ?: JSONArray()
            (0 until arr.length()).map { BridgeLine(arr.getString(it)) }
        }
}

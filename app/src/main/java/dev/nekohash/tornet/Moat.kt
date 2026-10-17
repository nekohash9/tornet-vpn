package dev.nekohash.tornet

import IPtProxy.IPtProxy
import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Moat — API Tor Project, которое выдаёт мосты, работающие в конкретной стране.
 * Сам сайт bridges.torproject.org заблокирован, поэтому, как в Tor Browser и Orbot,
 * ходим к нему через туннель: meek (через CDN) или dnstt (через DNS-over-HTTPS Google).
 */
object Moat {

    private enum class Tunnel(val transport: String, val baseUrl: String, val args: String) {
        TOR_PROJECT(
            IPtProxy.MeekLite,
            "https://bridges.torproject.org/moat/circumvention/",
            "url=https://1723079976.rsc.cdn77.org;front=www.phpmyadmin.net",
        ),
        GUARDIAN_PROJECT(
            IPtProxy.Dnstt,
            "https://tns1.bypasscensorship.org/moat/circumvention/",
            "doh=https://dns.google/dns-query;pubkey=c07ae9dd7b86ded6121c3db173de048bfd4f41de38dd430e3dfaf83ec8f36a06;domain=t1.bypasscensorship.org",
        ),
    }

    private val TRANSPORTS = listOf("obfs4", "webtunnel", "snowflake", "dnstt")

    private const val DIRECT_BASE = "https://bridges.torproject.org/moat/circumvention/"

    /**
     * Запрашивает мосты для страны пользователя. Заодно обновляет встроенные мосты.
     * Порядок: через уже подключённый Tor (если [torHttpPort] > 0) — это работает всегда;
     * затем meek-туннель через CDN; затем dnstt через DNS Google.
     * Возвращает строки мостов (может быть пустым) или кидает исключение, если Moat недоступен.
     * Блокирующий вызов — только не на главном потоке.
     */
    fun fetchBridges(context: Context, torHttpPort: Int = 0): List<BridgeLine> {
        val country = country(context)
        val body = JSONObject().put("transports", JSONArray(TRANSPORTS))
        if (country != null) body.put("country", country)
        var lastError: Exception? = null
        var builtinUpdated = false

        fun attempt(baseUrl: String, open: (String, JSONObject?) -> JSONObject): List<BridgeLine>? = try {
            if (!builtinUpdated) {
                runCatching { open(baseUrl + "builtin", null) }
                    .onSuccess { BuiltInBridges.update(context, it); builtinUpdated = true }
            }
            extract(open(baseUrl + "settings", body)).ifEmpty { null }
        } catch (e: Exception) {
            lastError = e
            null
        }

        if (torHttpPort > 0) {
            val viaTor = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", torHttpPort))
            attempt(DIRECT_BASE) { url, b -> http(context, url, b, viaTor) }?.let { return it }
        }
        for (tunnel in Tunnel.entries) {
            // Moat от Guardian Project за dnstt не видит наш IP, ему нужна страна явно.
            if (tunnel == Tunnel.GUARDIAN_PROJECT && country == null) continue
            attempt(tunnel.baseUrl) { url, b -> viaTunnel(context, tunnel, url, b) }?.let { return it }
        }
        lastError?.let { throw it }
        return emptyList()
    }

    private fun extract(response: JSONObject): List<BridgeLine> {
        val result = mutableListOf<BridgeLine>()
        val settings = response.optJSONArray("settings") ?: return result
        for (i in 0 until settings.length()) {
            val bridges = settings.getJSONObject(i).optJSONObject("bridges") ?: continue
            val strings = bridges.optJSONArray("bridge_strings") ?: continue
            for (j in 0 until strings.length()) result += BridgeLine(strings.getString(j))
        }
        return result
    }

    private fun viaTunnel(context: Context, tunnel: Tunnel, url: String, body: JSONObject?): JSONObject {
        val port = PluggableTransports.startSingle(context, tunnel.transport)
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        // Параметры транспорта передаются через логин SOCKS5, пароль — нулевой байт.
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(tunnel.args, charArrayOf('\u0000'))
        })
        try {
            return http(context, url, body, proxy)
        } finally {
            Authenticator.setDefault(null)
        }
    }

    private fun http(context: Context, url: String, body: JSONObject?, proxy: Proxy): JSONObject {
        val conn = URL(url).openConnection(proxy) as HttpsURLConnection
        sslFactory(context)?.let { conn.sslSocketFactory = it }
        // Недолго: если путь заблокирован, лучше быстро перейти к следующему.
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/vnd.api+json")
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException(L10n.s(R.string.log_moat_http, conn.responseCode))
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Код страны: по сотовой сети, иначе по SIM, иначе по языку системы. */
    private fun country(context: Context): String? {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = listOf(
            runCatching { tm?.networkCountryIso }.getOrNull(),
            runCatching { tm?.simCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { !it.isNullOrBlank() && it.length == 2 }
        return iso?.lowercase()
    }

    /**
     * На Android 7.0 нет корневого сертификата Let's Encrypt (ISRG Root X1),
     * которым подписан bridges.torproject.org, поэтому доверяем ему явно.
     */
    private fun sslFactory(context: Context): SSLSocketFactory? = runCatching {
        val cert = context.assets.open("isrg-root-x1.cer").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("isrg", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }.getOrNull()
}

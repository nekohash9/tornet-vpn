package dev.nekohash.tornet

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Язык приложения: системный, английский или русский.
 *
 * Экраны переключает AppCompat, а сервисы (журнал, уведомление) берут строки
 * через [s], которая учитывает выбранный язык и на старых Android.
 */
object L10n {
    const val SYSTEM = ""
    val LANGUAGES = listOf(SYSTEM, "en", "ru")

    private const val PREFS = "tornet"
    private const val KEY = "language"
    private lateinit var app: Context

    fun init(application: Application) {
        app = application
        // До Android 13 язык приложения не хранится системой — восстанавливаем сами.
        if (Build.VERSION.SDK_INT < 33) apply(saved())
    }

    /** Текущий язык: "" (как в системе), "en" или "ru". */
    fun current(): String {
        if (Build.VERSION.SDK_INT >= 33) {
            // На Android 13+ язык можно сменить и в системных настройках приложения.
            val locales = app.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) SYSTEM else locales[0].language
        }
        return saved()
    }

    fun set(language: String) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
        apply(language)
    }

    private fun saved() =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM).orEmpty()

    private fun apply(language: String) {
        AppCompatDelegate.setApplicationLocales(
            if (language.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(language),
        )
    }

    private fun context(): Context {
        val language = current()
        if (language.isEmpty()) return app
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(language))
        return app.createConfigurationContext(config)
    }

    /** Строка на выбранном языке — для кода вне Activity. */
    fun s(@StringRes id: Int, vararg args: Any?): String = context().getString(id, *args)
}

class TorNetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        L10n.init(this)
    }
}

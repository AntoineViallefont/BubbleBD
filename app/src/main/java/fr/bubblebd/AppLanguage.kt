package fr.bubblebd

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Android 13 owns the preference; older Android versions keep it locally. */
object AppLanguage {
    private const val STORE = "bubblebd-language"
    private const val KEY = "language"
    val choices = listOf("", "fr", "en")

    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',').substringBefore('-')
    } else context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun select(context: Context, language: String) {
        require(language in choices)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
        } else {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, language).commit()
        }
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val requested = selected(context)
        val system = android.content.res.Resources.getSystem().configuration.locales[0]
        val locale = Locale(if ((requested.ifEmpty { system.language }) == "fr") "fr" else "en")
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration)
    }
}

class BubbleApplication : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))
}

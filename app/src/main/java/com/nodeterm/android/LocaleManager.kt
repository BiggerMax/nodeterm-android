package com.nodeterm.android

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import com.nodeterm.android.data.UiPrefsStore
import java.util.Locale

object LocaleManager {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val CHINESE = "zh"

    fun currentLanguage(context: Context): String = UiPrefsStore(context).language

    fun wrap(context: Context): ContextWrapper = LocaleContextWrapper(context, localeFor(currentLanguage(context)))

    fun setLocale(context: Context, language: String) {
        UiPrefsStore(context).language = language
        localeFor(language)?.let { Locale.setDefault(it) }
    }

    fun applyDefault(language: String) {
        localeFor(language)?.let { Locale.setDefault(it) }
    }

    private fun localeFor(language: String): Locale? = when (language) {
        ENGLISH -> Locale.ENGLISH
        CHINESE -> Locale.SIMPLIFIED_CHINESE
        else -> null
    }

    private class LocaleContextWrapper(base: Context, locale: Locale?) : ContextWrapper(base) {
        private val localizedResources: Resources? = locale?.let {
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocale(it)
            base.createConfigurationContext(configuration).resources
        }

        override fun getResources(): Resources = localizedResources ?: super.getResources()
        override fun getApplicationContext(): Context = this
    }
}

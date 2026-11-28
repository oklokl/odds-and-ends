package com.krdonon.timer

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

enum class AppLanguage(val code: String, val locale: Locale) {
    KOREAN("ko", Locale.KOREAN),
    ENGLISH("en", Locale.ENGLISH);

    companion object {
        fun fromCode(code: String?): AppLanguage? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

object AppLanguagePrefs {
    private const val PREFS_NAME = "app_language_prefs"
    private const val KEY_LANGUAGE_CODE = "selected_language_code"

    fun getSelectedLanguage(context: Context): AppLanguage {
        val safeCtx = context.applicationContext ?: context
        val prefs = safeCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE_CODE, null)
        AppLanguage.fromCode(saved)?.let { return it }

        val systemLang = Resources.getSystem().configuration.locales.let { list ->
            if (!list.isEmpty) list[0].language else Locale.getDefault().language
        }
        return if (systemLang.equals("en", ignoreCase = true)) {
            AppLanguage.ENGLISH
        } else {
            AppLanguage.KOREAN
        }
    }

    fun setSelectedLanguage(context: Context, language: AppLanguage) {
        val safeCtx = context.applicationContext ?: context
        safeCtx
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putString(KEY_LANGUAGE_CODE, language.code)
            }
        Locale.setDefault(language.locale)
    }

    fun getEffectiveLocale(context: Context): Locale =
        getSelectedLanguage(context).locale

    fun syncDefaultLocale(context: Context) {
        Locale.setDefault(getEffectiveLocale(context))
    }

    fun wrapContext(base: Context, forceFontScaleOne: Boolean = false): Context {
        val targetLocale = getEffectiveLocale(base)
        Locale.setDefault(targetLocale)

        val currentConfig = base.resources.configuration
        val newConfig = Configuration(currentConfig).apply {
            setLocale(targetLocale)
            setLocales(LocaleList(targetLocale))
            if (forceFontScaleOne && fontScale != 1.0f) {
                fontScale = 1.0f
            }
        }
        return base.createConfigurationContext(newConfig)
    }
}

package com.mediminder.app.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * The locale the app is currently running in.
 *
 * With per-app languages (chosen in Settings) this can differ from the phone's
 * system locale — and on Android 12 and older `Locale.getDefault()` still
 * returns the *system* locale. Every user-visible date/time formatter must
 * therefore go through here instead of using the default locale directly.
 */
fun appLocale(): Locale {
    val appLocales = AppCompatDelegate.getApplicationLocales()
    if (!appLocales.isEmpty) {
        return appLocales[0] ?: Locale.getDefault()
    }
    return Locale.getDefault()
}

/**
 * A context whose strings resolve in the app language.
 *
 * On Android 12 and older, per-app locales only apply to Activity contexts —
 * the application context (used by notification receivers, workers and the
 * PDF report) still resolves strings in the system language. This wrapper
 * re-applies the chosen language (stored in settings) to such contexts.
 * On Android 13+ the framework already localizes everything, so the
 * receiver/handler contexts are returned unchanged.
 */
fun Context.withAppLanguage(languageTag: String): Context {
    if (languageTag.isBlank() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return this
    }
    return runCatching {
        val config = Configuration(resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(languageTag))
        createConfigurationContext(config)
    }.getOrDefault(this)
}

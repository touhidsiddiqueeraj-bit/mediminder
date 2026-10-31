package com.mediminder.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The languages the app ships translations for. [tag] is the Android language
 * tag of the bundled `values-<tag>` resources; `null` means "follow the phone
 * setting" (system default).
 *
 * The choice is applied through AndroidX per-app locales: on Android 13+ it
 * hands over to the framework (and the system settings list the languages
 * too), on Android 8–12 AppCompat recreates the activities itself. It is also
 * persisted in the app settings so notifications and reports — which run on
 * the application context and therefore miss the activity-level locale — can
 * resolve strings in the chosen language.
 */
enum class AppLanguage(val tag: String?, val nativeName: String) {
    SYSTEM(null, ""),
    ENGLISH("en", "English"),
    BANGLA("bn", "বাংলা"),
    HINDI("hi", "हिन्दी"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ARABIC("ar", "العربية"),
    PORTUGUESE("pt", "Português");

    /** Switches the whole app over immediately; activities recreate themselves. */
    fun select() {
        val locales = if (tag == null) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        /** The language the app is currently running in. */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return SYSTEM
            val tag = locales[0]?.language
            return entries.firstOrNull { it.tag == tag } ?: SYSTEM
        }
    }
}

package com.mediminder.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.mediminder.app.util.appLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** What the speaker can currently do. */
enum class SpeakerState {
    /** No engine has finished initialising yet. */
    WARMING_UP,
    /** Engine ready and a voice for the app language is installed. */
    READY,
    /** Engine works, but no voice data is installed for the app language. */
    LANGUAGE_MISSING,
    /** The phone has no working text-to-speech engine at all. */
    FAILED,
}

/**
 * Optional voice readout of reminders (Text-to-Speech). Scoped to a screen or
 * activity: call [init] early (when the screen appears, not on the first tap),
 * always call [shutdown] on dispose/destroy.
 *
 * Reliability rules, learned from "the read-aloud button does nothing" reports:
 *  - Engines take a moment to bind. Speaking before init completes silently
 *    does nothing, so [init] is warmed up when the screen opens and any
 *    utterance requested too early is held and spoken once the engine is live.
 *  - The language must be set explicitly. Without it the engine picks a voice
 *    that may be unable to speak the app language, which also fails silently.
 *  - Failures are surfaced through [state] so the UI can tell the user what to
 *    do (e.g. install a voice) instead of leaving a button that does nothing.
 */
class ReminderSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: TextToSpeech? = null
    private var ready = false
    private var targetLanguage: Locale? = null
    private var pendingUtterance: String? = null
    private var retryPending = false

    private val _state = MutableStateFlow(SpeakerState.WARMING_UP)
    val state: StateFlow<SpeakerState> = _state.asStateFlow()

    /** Creates and warms up the engine. Safe to call repeatedly. */
    fun init() {
        if (engine != null) return
        engine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                applyLanguage()
                val utterance = pendingUtterance
                pendingUtterance = null
                if (utterance != null) speak(utterance)
            } else {
                ready = false
                _state.value = SpeakerState.FAILED
            }
        }
    }

    /**
     * The language the next utterance is spoken in. Call this whenever the
     * app language is (or may be) known — it defaults to the app locale.
     */
    fun language(locale: Locale) {
        if (targetLanguage == locale) return
        targetLanguage = locale
        if (engine != null && ready) applyLanguage()
    }

    /**
     * Speaks [text], replacing anything already playing. If the engine is not
     * ready yet the text is held and spoken the moment it becomes ready.
     */
    fun speak(text: String) {
        val utterance = text.trim()
        if (utterance.isEmpty()) return
        val current = engine
        if (current == null || !ready) {
            pendingUtterance = utterance
            init()
            return
        }
        applyLanguage()
        val accepted = current.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (accepted == TextToSpeech.ERROR) {
            // Some engines report an error for the very first utterance right
            // after init; one delayed retry catches that without stacking.
            retrySoon(utterance)
        }
    }

    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        engine?.let { runCatching { it.shutdown() } }
        engine = null
        ready = false
        pendingUtterance = null
        retryPending = false
        _state.value = SpeakerState.WARMING_UP
    }

    private fun applyLanguage() {
        val current = engine ?: return
        val locale = targetLanguage ?: appLocale()
        val result = current.setLanguage(locale)
        _state.value = if (
            result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            SpeakerState.LANGUAGE_MISSING
        } else {
            SpeakerState.READY
        }
    }

    private fun retrySoon(utterance: String) {
        if (retryPending) return
        retryPending = true
        mainHandler.postDelayed(
            {
                retryPending = false
                val current = engine ?: return@postDelayed
                if (!ready) return@postDelayed
                applyLanguage()
                current.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            },
            RETRY_DELAY_MS,
        )
    }

    private companion object {
        const val UTTERANCE_ID = "mediminder-utterance"
        const val RETRY_DELAY_MS = 400L
    }
}

package com.mediminder.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech

/**
 * Optional voice readout of reminders (Text-to-Speech). Scoped to an Activity:
 * create on demand, always call [shutdown] from onDestroy.
 */
class ReminderSpeaker(private val context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pendingUtterance: String? = null

    fun init() {
        if (engine != null) return
        engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                pendingUtterance?.let { speak(it) }
            }
            pendingUtterance = null
        }
    }

    fun speak(text: String) {
        val current = engine
        if (current != null && ready) {
            current.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mediminder-utterance")
        } else {
            // Engine still warming up — say it as soon as it is ready.
            pendingUtterance = text
        }
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
        pendingUtterance = null
    }
}

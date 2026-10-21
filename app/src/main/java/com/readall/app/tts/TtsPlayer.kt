package com.readall.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class TtsPlayer(context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private var utteranceCallback: ((Boolean) -> Unit)? = null
    private var currentUtteranceId: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val korean = engine.setLanguage(Locale.KOREAN)
                if (korean == TextToSpeech.LANG_MISSING_DATA || korean == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.getDefault()
                }
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        notifyIfCurrent(utteranceId, success = true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        notifyIfCurrent(utteranceId, success = false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        notifyIfCurrent(utteranceId, success = false)
                    }
                })
                ready.set(true)
            }
        }
    }

    val isReady: Boolean get() = ready.get()

    fun setRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.5f))
    }

    fun speak(text: String, rate: Float, onFinished: (Boolean) -> Unit) {
        val engine = tts
        if (engine == null || !ready.get() || text.isBlank()) {
            onFinished(false)
            return
        }
        val utteranceId = "readall-${System.nanoTime()}"
        currentUtteranceId = utteranceId
        utteranceCallback = onFinished
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.5f))
        val params = Bundle()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        utteranceCallback = null
        currentUtteranceId = null
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }

    private fun notifyIfCurrent(utteranceId: String?, success: Boolean) {
        if (utteranceId != currentUtteranceId) return
        mainHandler.post {
            if (utteranceId == currentUtteranceId) {
                utteranceCallback?.invoke(success)
            }
        }
    }
}

package com.readall.app.tts

sealed class SpeechUnit {
    data class Speak(val text: String) : SpeechUnit()
    data class Pause(val millis: Long) : SpeechUnit()
}

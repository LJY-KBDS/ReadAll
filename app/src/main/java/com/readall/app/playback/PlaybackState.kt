package com.readall.app.playback

import com.readall.app.tts.SpeechUnit

data class PlaybackState(
    val documentId: String? = null,
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val units: List<SpeechUnit> = emptyList(),
    val unitIndex: Int = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val statusMessage: String? = null
)

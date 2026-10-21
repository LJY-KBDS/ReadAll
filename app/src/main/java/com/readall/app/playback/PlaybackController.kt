package com.readall.app.playback

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.readall.app.data.DocumentRepository
import com.readall.app.pdf.PdfTextExtractor
import com.readall.app.pdf.SpeechTextProfile
import com.readall.app.tts.SpeechUnit
import com.readall.app.tts.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class PlaybackController(
    private val app: Application,
    private val repository: DocumentRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ttsPlayer = TtsPlayer(app)
    private var playbackJob: Job? = null
    private var speechProfile = SpeechTextProfile()
    private val textCache = mutableMapOf<Int, String>()
    private var pdfFile: File? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun attach(
        documentId: String,
        title: String,
        pageCount: Int,
        initialPage: Int,
        profile: SpeechTextProfile
    ) {
        if (_state.value.documentId == documentId) return
        pauseInternal()
        textCache.clear()
        speechProfile = profile
        pdfFile = repository.pdfFile(documentId)
        _state.value = PlaybackState(
            documentId = documentId,
            title = title,
            pageCount = pageCount,
            currentPage = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
            speed = _state.value.speed
        )
        scope.launch { loadPage(_state.value.currentPage, keepPlaying = false) }
    }

    fun togglePlay() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun play() {
        val current = _state.value
        if (current.documentId == null) return
        if (current.units.none { it is SpeechUnit.Speak } && current.units.isEmpty()) {
            skipEmptyPagesForward()
            return
        }
        startService()
        _state.update { it.copy(isPlaying = true, statusMessage = null) }
        startPlaybackLoop()
    }

    fun pause() {
        pauseInternal()
        _state.update { it.copy(isPlaying = false) }
    }

    fun nextPage() {
        val next = (_state.value.currentPage + 1).coerceAtMost(_state.value.pageCount - 1)
        if (next != _state.value.currentPage) changePage(next)
    }

    fun previousPage() {
        val prev = (_state.value.currentPage - 1).coerceAtLeast(0)
        if (prev != _state.value.currentPage) changePage(prev)
    }

    fun goToPageNumber(pageNumber: Int): Boolean {
        val pageCount = _state.value.pageCount
        if (pageCount <= 0) return false
        val target = pageNumber - 1
        if (target !in 0 until pageCount) return false
        if (target != _state.value.currentPage) changePage(target)
        return true
    }

    fun changePage(page: Int) {
        val wasPlaying = _state.value.isPlaying
        pauseInternal()
        scope.launch {
            loadPage(page, keepPlaying = wasPlaying)
            if (wasPlaying) {
                _state.update { it.copy(isPlaying = true) }
                startService()
                startPlaybackLoop()
            }
        }
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.5f)
        _state.update { it.copy(speed = clamped) }
        ttsPlayer.setRate(clamped)
    }

    private fun startService() {
        val intent = Intent(app, ReadingPlaybackService::class.java).setAction(ReadingPlaybackService.ACTION_START)
        ContextCompat.startForegroundService(app, intent)
    }

    private fun pauseInternal() {
        playbackJob?.cancel()
        playbackJob = null
        ttsPlayer.stop()
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            if (!ensureTtsReady()) {
                _state.update { it.copy(isPlaying = false, statusMessage = "음성 엔진을 준비하지 못했습니다.") }
                return@launch
            }
            while (_state.value.isPlaying) {
                val snapshot = _state.value
                val units = snapshot.units
                if (units.none { it is SpeechUnit.Speak }) {
                    if (!advanceToNextReadablePage()) {
                        _state.update {
                            it.copy(isPlaying = false, statusMessage = "문서 읽기를 마쳤습니다.")
                        }
                        break
                    }
                    continue
                }
                val index = snapshot.unitIndex.coerceIn(0, units.lastIndex)
                when (val unit = units[index]) {
                    is SpeechUnit.Speak -> {
                        val spoken = speak(unit.text, snapshot.speed)
                        if (!_state.value.isPlaying) break
                        if (!spoken) {
                            _state.update {
                                it.copy(isPlaying = false, statusMessage = "음성 엔진을 사용할 수 없습니다.")
                            }
                            break
                        }
                    }
                    is SpeechUnit.Pause -> {
                        val pauseMs = (unit.millis / snapshot.speed.coerceAtLeast(0.5f)).toLong().coerceAtLeast(40L)
                        delay(pauseMs)
                        if (!_state.value.isPlaying) break
                    }
                }
                val nextIndex = index + 1
                if (nextIndex < units.size) {
                    _state.update { it.copy(unitIndex = nextIndex) }
                } else {
                    val nextPage = snapshot.currentPage + 1
                    if (nextPage >= snapshot.pageCount) {
                        _state.update {
                            it.copy(isPlaying = false, unitIndex = units.lastIndex, statusMessage = "문서 읽기를 마쳤습니다.")
                        }
                        break
                    } else {
                        loadPage(nextPage, keepPlaying = true)
                    }
                }
            }
        }
    }

    private fun skipEmptyPagesForward() {
        scope.launch {
            _state.update { it.copy(isPlaying = true) }
            startService()
            if (advanceToNextReadablePage()) {
                startPlaybackLoop()
            } else {
                _state.update { it.copy(isPlaying = false, statusMessage = "읽을 텍스트가 없습니다.") }
            }
        }
    }

    private suspend fun advanceToNextReadablePage(): Boolean {
        var page = _state.value.currentPage
        while (page < _state.value.pageCount) {
            if (_state.value.units.any { it is SpeechUnit.Speak }) return true
            val next = page + 1
            if (next >= _state.value.pageCount) return false
            loadPage(next, keepPlaying = true)
            page = next
        }
        return _state.value.units.any { it is SpeechUnit.Speak }
    }

    private suspend fun loadPage(page: Int, keepPlaying: Boolean) {
        val file = pdfFile ?: return
        val pageCount = _state.value.pageCount
        val safePage = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        _state.update { it.copy(currentPage = safePage, unitIndex = 0) }
        val text = withContext(Dispatchers.IO) {
            textCache.getOrPut(safePage) {
                runCatching { PdfTextExtractor.extractPage(file, safePage, speechProfile) }.getOrDefault("")
            }
        }
        val units = PdfTextExtractor.splitForSpeech(text)
        _state.update {
            it.copy(
                currentPage = safePage,
                units = units,
                unitIndex = 0,
                isPlaying = keepPlaying && it.isPlaying,
                statusMessage = if (units.none { unit -> unit is SpeechUnit.Speak }) {
                    "이 페이지에서 텍스트를 찾지 못했습니다."
                } else {
                    it.statusMessage
                }
            )
        }
        _state.value.documentId?.let { id -> repository.updateProgress(id, safePage) }
    }

    private suspend fun ensureTtsReady(): Boolean {
        repeat(50) {
            if (ttsPlayer.isReady) return true
            delay(100)
        }
        return ttsPlayer.isReady
    }

    private suspend fun speak(text: String, speed: Float): Boolean {
        return suspendCancellableCoroutine { continuation ->
            ttsPlayer.speak(text, speed) { success ->
                if (continuation.isActive) continuation.resume(success)
            }
            continuation.invokeOnCancellation { ttsPlayer.stop() }
        }
    }
}

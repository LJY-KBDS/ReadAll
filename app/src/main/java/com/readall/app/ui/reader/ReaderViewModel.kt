package com.readall.app.ui.reader

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.readall.app.ReadAllApplication
import com.readall.app.data.DocumentRepository
import com.readall.app.pdf.PdfPageRenderer
import com.readall.app.pdf.PdfTextExtractor
import com.readall.app.playback.PlaybackController
import com.readall.app.tts.SpeechUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderUiState(
    val title: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val pageBitmap: Bitmap? = null,
    val pageText: String = "",
    val sentences: List<SpeechUnit> = emptyList(),
    val sentenceIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = true,
    val speed: Float = 1.0f,
    val statusMessage: String? = null,
    val error: String? = null
)

class ReaderViewModel(
    application: Application,
    private val documentId: String,
    private val repository: DocumentRepository,
    private val playback: PlaybackController
) : AndroidViewModel(application) {

    private val pdfFile: File = repository.pdfFile(documentId)
    private var renderer: PdfPageRenderer? = null
    private var lastRenderedPage: Int = -1
    private var renderWidth = 1080

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { openDocument() }
        viewModelScope.launch {
            playback.state.collect { playbackState ->
                if (playbackState.documentId != documentId) return@collect
                _uiState.update {
                    it.copy(
                        title = playbackState.title.ifBlank { it.title },
                        pageCount = if (playbackState.pageCount > 0) playbackState.pageCount else it.pageCount,
                        currentPage = playbackState.currentPage,
                        sentences = playbackState.units,
                        sentenceIndex = playbackState.unitIndex,
                        isPlaying = playbackState.isPlaying,
                        speed = playbackState.speed,
                        statusMessage = playbackState.statusMessage ?: it.statusMessage
                    )
                }
                if (playbackState.currentPage != lastRenderedPage) {
                    renderPage(playbackState.currentPage)
                }
            }
        }
    }

    private suspend fun openDocument() {
        val document = repository.getDocument(documentId)
        if (document == null || !pdfFile.exists()) {
            _uiState.update { it.copy(isPreparing = false, error = "문서를 찾을 수 없습니다.") }
            return
        }
        val profile = runCatching {
            withContext(Dispatchers.IO) {
                renderer = PdfPageRenderer(pdfFile)
                PdfTextExtractor.analyze(pdfFile, document.title)
            }
        }.getOrElse {
            _uiState.update { state -> state.copy(isPreparing = false, error = "PDF를 열 수 없습니다.") }
            return
        }
        _uiState.update {
            it.copy(
                title = document.title,
                pageCount = document.pageCount,
                isPreparing = false
            )
        }
        playback.attach(
            documentId = document.id,
            title = document.title,
            pageCount = document.pageCount,
            initialPage = document.lastPage.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0)),
            profile = profile
        )
        renderPage(playback.state.value.currentPage)
    }

    fun togglePlay() = playback.togglePlay()
    fun play() = playback.play()
    fun pause() = playback.pause()
    fun nextPage() = playback.nextPage()
    fun previousPage() = playback.previousPage()
    fun goToPageNumber(pageNumber: Int): Boolean = playback.goToPageNumber(pageNumber)
    fun setSpeed(speed: Float) = playback.setSpeed(speed)

    fun setRenderZoom(scale: Float) {
        val width = (1080 * scale.coerceAtLeast(1f)).toInt().coerceIn(1080, 2880)
        if (width == renderWidth || lastRenderedPage < 0) return
        renderWidth = width
        viewModelScope.launch { renderPage(lastRenderedPage) }
    }

    private suspend fun renderPage(page: Int) {
        val renderer = renderer ?: return
        val safePage = page.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
        lastRenderedPage = safePage
        val bitmap = withContext(Dispatchers.IO) { renderer.render(safePage, renderWidth) }
        val previous = _uiState.value.pageBitmap
        _uiState.update { it.copy(currentPage = safePage, pageBitmap = bitmap, isPreparing = false) }
        if (previous != null && previous != bitmap) {
            previous.recycle()
        }
    }

    override fun onCleared() {
        renderer?.close()
        _uiState.value.pageBitmap?.recycle()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val documentId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val container = (application as ReadAllApplication).container
            return ReaderViewModel(application, documentId, container.repository, container.playback) as T
        }
    }
}

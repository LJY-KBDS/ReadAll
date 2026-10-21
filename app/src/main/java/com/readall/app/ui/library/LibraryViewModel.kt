package com.readall.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.readall.app.ReadAllApplication
import com.readall.app.data.DocumentRepository
import com.readall.app.data.StoredDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val importing: Boolean = false,
    val error: String? = null
)

class LibraryViewModel(
    application: Application,
    private val repository: DocumentRepository
) : AndroidViewModel(application) {

    val documents: StateFlow<List<StoredDocument>> = repository.documents.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repository.load() }
    }

    fun importPdf(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = LibraryUiState(importing = true)
            runCatching { repository.import(uri) }
                .onSuccess { _uiState.value = LibraryUiState() }
                .onFailure { _uiState.value = LibraryUiState(error = it.message ?: "PDF를 저장하지 못했습니다.") }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = (application as ReadAllApplication).container.repository
            return LibraryViewModel(application, repository) as T
        }
    }
}

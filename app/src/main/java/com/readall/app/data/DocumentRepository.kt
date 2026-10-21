package com.readall.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.readall.app.pdf.PdfPageRenderer
import com.readall.app.pdf.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class DocumentRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private val indexFile = File(context.filesDir, "library.json")
    private val pdfDir = File(context.filesDir, "pdfs").apply { mkdirs() }
    private val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }

    private val _documents = MutableStateFlow<List<StoredDocument>>(emptyList())
    val documents: StateFlow<List<StoredDocument>> = _documents.asStateFlow()

    suspend fun load() {
        mutex.withLock {
            _documents.value = readIndex()
        }
    }

    fun pdfFile(id: String): File = File(pdfDir, "$id.pdf")

    fun thumbFile(id: String): File = File(thumbDir, "$id.jpg")

    fun getDocument(id: String): StoredDocument? = _documents.value.firstOrNull { it.id == id }

    suspend fun import(uri: Uri): StoredDocument = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dest = pdfFile(id)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("PDF 파일을 열 수 없습니다.")

        val displayName = queryDisplayName(uri) ?: "문서.pdf"
        val title = displayName.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "제목 없는 문서" }

        val pageCount = PdfTextExtractor.pageCount(dest)
        if (pageCount <= 0) {
            dest.delete()
            error("유효한 PDF가 아닙니다.")
        }

        runCatching { createThumbnail(dest, thumbFile(id)) }

        val now = System.currentTimeMillis()
        val document = StoredDocument(
            id = id,
            title = title,
            originalName = displayName,
            pageCount = pageCount,
            lastPage = 0,
            addedAt = now,
            lastOpenedAt = now
        )
        mutex.withLock {
            val next = listOf(document) + readIndex()
            writeIndex(next)
            _documents.value = next
        }
        document
    }

    suspend fun delete(id: String) {
        mutex.withLock {
            pdfFile(id).delete()
            thumbFile(id).delete()
            val next = readIndex().filterNot { it.id == id }
            writeIndex(next)
            _documents.value = next
        }
    }

    suspend fun updateProgress(id: String, lastPage: Int) {
        mutex.withLock {
            val next = readIndex().map { doc ->
                if (doc.id == id) {
                    doc.copy(
                        lastPage = lastPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)),
                        lastOpenedAt = System.currentTimeMillis()
                    )
                } else {
                    doc
                }
            }.sortedByDescending { it.lastOpenedAt }
            writeIndex(next)
            _documents.value = next
        }
    }

    private fun createThumbnail(pdf: File, dest: File) {
        PdfPageRenderer(pdf).use { renderer ->
            val bitmap = renderer.render(0, 360)
            dest.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
            }
            bitmap.recycle()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return it.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun readIndex(): List<StoredDocument> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredDocument>>(indexFile.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(documents: List<StoredDocument>) {
        indexFile.writeText(json.encodeToString(documents))
    }
}

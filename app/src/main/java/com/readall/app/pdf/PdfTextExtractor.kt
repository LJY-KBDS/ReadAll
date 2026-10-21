package com.readall.app.pdf

import com.readall.app.tts.SpeechUnit
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SpeechTextProfile(
    val repeatingLines: Set<String> = emptySet(),
    val documentTitle: String = ""
)

object PdfTextExtractor {
    fun pageCount(file: File): Int {
        PDDocument.load(file).use { document ->
            return document.numberOfPages
        }
    }

    fun analyze(file: File, documentTitle: String): SpeechTextProfile {
        PDDocument.load(file).use { document ->
            val pageCount = document.numberOfPages
            if (pageCount <= 1) {
                return SpeechTextProfile(documentTitle = documentTitle)
            }
            val sampleCount = min(pageCount, 10)
            val samplePages = samplePageIndices(pageCount, sampleCount)
            val frequency = mutableMapOf<String, Int>()
            for (index in samplePages) {
                headerFooterCandidates(extractRaw(document, index))
                    .distinct()
                    .forEach { line ->
                        frequency[line] = (frequency[line] ?: 0) + 1
                    }
            }
            val threshold = if (samplePages.size <= 2) samplePages.size else (samplePages.size + 1) / 2
            val repeating = frequency
                .filter { (line, count) ->
                    count >= threshold && line.length in 2..80 && !isLikelyBodySentence(line)
                }
                .keys
                .toMutableSet()
            val titleKey = compact(documentTitle)
            if (titleKey.length >= 2) {
                frequency.keys
                    .filter { isTitleLike(it, documentTitle) && (frequency[it] ?: 0) >= 2 }
                    .forEach { repeating += it }
            }
            return SpeechTextProfile(
                repeatingLines = repeating,
                documentTitle = documentTitle
            )
        }
    }

    fun extractPage(file: File, pageIndex: Int, profile: SpeechTextProfile = SpeechTextProfile()): String {
        PDDocument.load(file).use { document ->
            return prepareForSpeech(extractRaw(document, pageIndex), profile)
        }
    }

    fun splitForSpeech(text: String): List<SpeechUnit> {
        if (text.isBlank()) return emptyList()
        val units = mutableListOf<SpeechUnit>()

        fun addSpeak(value: String) {
            val spoken = value.trim()
            if (spoken.isNotEmpty()) units += SpeechUnit.Speak(spoken)
        }

        fun addPause() {
            if (units.isNotEmpty() && units.last() !is SpeechUnit.Pause) {
                units += SpeechUnit.Pause(PARAGRAPH_PAUSE)
            }
        }

        val blocks = text.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        blocks.forEachIndexed { blockIndex, block ->
            val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                .flatMap { expandGluedHeading(it) }
            val buffer = StringBuilder()
            fun flush() {
                addSpeak(buffer.toString())
                buffer.clear()
            }

            var previous: String? = null
            lines.forEachIndexed { lineIndex, line ->
                val next = lines.getOrNull(lineIndex + 1)
                val shouldBreak = buffer.isNotEmpty() && (
                    isBulletLine(line) ||
                        isHeadingLine(line, next) ||
                        (previous != null && isHeadingLine(previous, line)) ||
                        (previous != null && endsWithSentence(previous) && !isWrappedContinuation(previous, line))
                    )
                if (shouldBreak) {
                    flush()
                    addPause()
                }
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(line)
                if (isHeadingLine(line, next)) {
                    flush()
                    addPause()
                }
                previous = line
            }
            flush()
            if (blockIndex < blocks.lastIndex) addPause()
        }
        while (units.isNotEmpty() && units.last() is SpeechUnit.Pause) {
            units.removeAt(units.lastIndex)
        }
        return units
    }

    private fun extractRaw(document: PDDocument, pageIndex: Int): String {
        val pageNumber = (pageIndex + 1).coerceIn(1, document.numberOfPages)
        val layoutText = runCatching { extractByLayout(document, pageNumber) }.getOrNull()
        if (!layoutText.isNullOrBlank()) {
            return clean(layoutText)
        }
        val stripper = PDFTextStripper().apply {
            startPage = pageNumber
            endPage = pageNumber
            sortByPosition = true
        }
        return clean(stripper.getText(document))
    }

    private fun extractByLayout(document: PDDocument, pageNumber: Int): String {
        val stripper = LayoutAwareStripper().apply {
            startPage = pageNumber
            endPage = pageNumber
            sortByPosition = true
        }
        stripper.getText(document)
        return reconstructWithParagraphBreaks(stripper.runs)
    }

    private fun reconstructWithParagraphBreaks(runs: List<TextRun>): String {
        val lines = groupIntoLines(runs)
        if (lines.isEmpty()) return ""
        val ordered = orderTopToBottom(lines)
        if (ordered.size == 1) return ordered.first().text

        val gaps = ordered.zipWithNext { current, next -> abs(next.y - current.y) }
        val heights = ordered.map { it.height }.filter { it > 0f }
        val typicalHeight = median(heights).coerceAtLeast(1f)
        val typicalGap = median(gaps.filter { it > 0f }.ifEmpty { gaps }).coerceAtLeast(typicalHeight)

        val typicalFont = median(ordered.map { it.fontSize }.filter { it > 0f }).coerceAtLeast(1f)
        val builder = StringBuilder()
        ordered.forEachIndexed { index, line ->
            if (index > 0) {
                val previous = ordered[index - 1]
                val next = ordered.getOrNull(index + 1)
                val gap = abs(line.y - previous.y)
                val gapBreak = gap >= typicalGap * 1.15f && gap >= typicalHeight * 1.28f
                val fontBreak = previous.fontSize >= typicalFont * 1.08f &&
                    previous.text.length <= 42 &&
                    !endsWithSentence(previous.text)
                val structureBreak = isHeadingLine(previous.text, line.text) ||
                    isBulletLine(line.text) ||
                    isHeadingLine(line.text, next?.text)
                builder.append(if (gapBreak || fontBreak || structureBreak) "\n\n" else "\n")
            }
            expandGluedHeading(line.text).forEachIndexed { partIndex, part ->
                if (partIndex > 0) builder.append("\n\n")
                builder.append(part)
            }
        }
        return builder.toString()
    }

    private fun groupIntoLines(runs: List<TextRun>): List<TextLine> {
        val usable = runs.filter { it.text.isNotEmpty() && !it.text.all { char -> char.isWhitespace() && char != ' ' } }
        if (usable.isEmpty()) return emptyList()
        val sorted = usable.sortedWith(compareBy<TextRun> { it.y }.thenBy { it.x })
        val buckets = mutableListOf<MutableList<TextRun>>()
        for (run in sorted) {
            val bucket = buckets.lastOrNull()
            val lineHeight = bucket?.map { it.height }?.average()?.toFloat()?.coerceAtLeast(1f) ?: run.height.coerceAtLeast(1f)
            val lineY = bucket?.map { it.y }?.average()?.toFloat()
            val avgFont = bucket?.map { it.fontSize }?.average()?.toFloat() ?: run.fontSize
            val yTol = min(lineHeight, run.height.coerceAtLeast(1f)) * 0.30f
            val fontChanged = avgFont > 0f && (run.fontSize < avgFont * 0.86f || run.fontSize > avgFont * 1.16f)
            val leftAlignedNewLine = bucket != null && fontChanged && run.x <= bucket.minOf { it.x } + 8f
            if (bucket == null || lineY == null || abs(run.y - lineY) > yTol || leftAlignedNewLine) {
                buckets += mutableListOf(run)
            } else {
                bucket += run
            }
        }
        return buckets.flatMap { splitBucketByFont(it) }.map { ordered ->
            val height = ordered.map { it.height }.average().toFloat().coerceAtLeast(1f)
            TextLine(
                text = joinLine(ordered, height),
                y = ordered.map { it.y }.average().toFloat(),
                height = height,
                fontSize = ordered.map { it.fontSize }.average().toFloat()
            )
        }.filter { it.text.isNotBlank() }
    }

    private fun splitBucketByFont(bucket: List<TextRun>): List<List<TextRun>> {
        val ordered = bucket.sortedBy { it.x }
        if (ordered.size < 3) return listOf(ordered)
        val groups = mutableListOf<MutableList<TextRun>>()
        groups += mutableListOf(ordered.first())
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val current = ordered[index]
            val sizeJump = current.fontSize < previous.fontSize * 0.88f ||
                current.fontSize > previous.fontSize * 1.14f
            val gap = current.x - (previous.x + previous.width)
            if (sizeJump && gap > previous.height * 0.12f) {
                groups += mutableListOf(current)
            } else {
                groups.last() += current
            }
        }
        return groups
    }

    private fun joinLine(runs: List<TextRun>, lineHeight: Float): String {
        val builder = StringBuilder()
        val spaceThreshold = lineHeight * 0.22f
        runs.forEachIndexed { index, run ->
            if (index > 0) {
                val previous = runs[index - 1]
                val gap = run.x - (previous.x + previous.width)
                if (gap > spaceThreshold && (builder.isEmpty() || !builder.last().isWhitespace())) {
                    builder.append(' ')
                }
            }
            builder.append(run.text)
        }
        return builder.toString().replace(Regex(" +"), " ").trim()
    }

    private fun orderTopToBottom(lines: List<TextLine>): List<TextLine> {
        if (lines.size < 2) return lines
        val ascending = lines.sortedBy { it.y }
        val ascendingPositive = ascending.zipWithNext { a, b -> b.y - a.y }.count { it > 0 }
        return if (ascendingPositive >= lines.size / 2) ascending else lines.sortedByDescending { it.y }
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    private fun prepareForSpeech(text: String, profile: SpeechTextProfile): String {
        val lines = text.lines()
        val nonEmpty = lines.mapIndexed { index, line -> index to line.trim() }
            .filter { it.second.isNotEmpty() }
        if (nonEmpty.isEmpty()) return ""
        val edgeIndices = (
            nonEmpty.take(3).map { it.first } + nonEmpty.takeLast(3).map { it.first }
            ).toSet()
        val kept = lines.mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapIndexedNotNull raw
            val normalized = normalize(line)
            when {
                normalized in profile.repeatingLines -> null
                isPageNumber(line) && index in edgeIndices -> null
                index in edgeIndices && isTitleLike(line, profile.documentTitle) -> null
                else -> raw
            }
        }
        return clean(kept.joinToString("\n"))
    }

    private fun headerFooterCandidates(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val take = min(3, lines.size)
        val edges = lines.take(take) + if (lines.size > take) lines.takeLast(take) else emptyList()
        return edges.map { normalize(it) }.filter { it.length in 2..80 }
    }

    private fun samplePageIndices(pageCount: Int, sampleCount: Int): List<Int> {
        if (pageCount <= sampleCount) return (0 until pageCount).toList()
        val divisor = (sampleCount - 1).coerceAtLeast(1)
        return (0 until sampleCount)
            .map { index -> (index * (pageCount - 1)) / divisor }
            .distinct()
    }

    private fun isBulletLine(line: String): Boolean {
        val text = line.trim()
        return text.matches(Regex("""^[-\-–—•·*▪●]\s+\S.*""")) ||
            text.matches(Regex("""^\d+[.)]\s+\S.*"""))
    }

    private fun endsWithSentence(line: String): Boolean {
        val text = line.trim()
        return text.endsWith("다.") ||
            text.endsWith("요.") ||
            text.endsWith("다") && text.length >= 8 ||
            text.matches(Regex(""".*(?:니다|습니다|답니다|세요)[.。!！?？]*$""")) ||
            text.endsWith('.') ||
            text.endsWith('。') ||
            text.endsWith('!') ||
            text.endsWith('？') ||
            text.endsWith('?')
    }

    private fun isWrappedContinuation(previous: String, current: String): Boolean {
        if (isBulletLine(current) || isHeadingLine(current, null) && current.length <= 24) return false
        if (!endsWithSentence(previous)) return true
        return CONTINUATION_PREFIXES.any { current.startsWith(it) }
    }

    private fun expandGluedHeading(line: String): List<String> {
        val text = line.trim()
        if (text.length < 12 || isBulletLine(text) || isHeadingLine(text, null)) {
            return listOf(text)
        }
        val match = HEADING_GLUE_PATTERN.find(text) ?: return listOf(text)
        val heading = match.groupValues[1].trim()
        val body = match.groupValues[2].trim()
        if (heading.length in 3..24 && body.length >= 10 && !endsWithSentence(heading) && !heading.contains('→')) {
            return listOf(heading, body)
        }
        return listOf(text)
    }

    private fun isHeadingLine(line: String, next: String?): Boolean {
        if (isBulletLine(line)) return false
        val text = line.trim()
        if (text.contains('→') || text.contains('•')) return false
        if (endsWithSentence(text)) return false
        val looksLikeHeading = text.length <= 22 ||
            (text.length <= 36 && HEADING_ENDINGS.any { text.endsWith(it) })
        if (!looksLikeHeading) return false
        if (next == null) return true
        if (isBulletLine(next)) return true
        return next.length >= 12
    }

    private fun isTitleLike(line: String, title: String): Boolean {
        val compactLine = compact(line)
        val compactTitle = compact(title)
        if (compactTitle.length < 2 || compactLine.length < 2) return false
        if (compactLine == compactTitle) return true
        if (compactLine.startsWith(compactTitle) && compactLine.length <= compactTitle.length + 8) return true
        if (compactTitle.startsWith(compactLine) && compactTitle.length - compactLine.length <= 8 && compactLine.length >= 4) {
            return true
        }
        return false
    }

    private fun isPageNumber(line: String): Boolean {
        return line.matches(Regex("""^[-–—]?\s*(페이지|page)?\s*\d+\s*[-–—]?$""", RegexOption.IGNORE_CASE))
    }

    private fun isLikelyBodySentence(line: String): Boolean {
        return line.length > 40 && line.any { it == '.' || it == '다' || it == '요' }
    }

    private fun normalize(value: String): String {
        return value.trim().replace(Regex("\\s+"), " ")
    }

    private fun compact(value: String): String {
        return value.lowercase()
            .removeSuffix(".pdf")
            .replace(Regex("[\\s_\\-·•:：./\\\\]+"), "")
    }

    private const val PARAGRAPH_PAUSE = 500L
    private val HEADING_ENDINGS = listOf(
        "점", "예시", "핵심", "요약", "개요", "정리", "특징", "주의", "참고",
        "배경", "목적", "결론", "소개", "개념", "정의", "방법", "원리",
        "비교", "종류", "구조", "흐름", "이유", "차이"
    )
    private val HEADING_GLUE_PATTERN = Regex(
        """^(.{2,24}?(?:${HEADING_ENDINGS.joinToString("|")}))\s+(\S.{9,})$"""
    )
    private val CONTINUATION_PREFIXES = listOf(
        "이 ", "그 ", "저 ", "또 ", "또한", "그리고", "하지만", "그래서",
        "즉 ", "예를", "또는", "및 ", "때 ", "또,"
    )

    private fun clean(raw: String): String {
        return raw
            .replace("\r", "")
            .replace(Regex("[\\t\\u000c]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}

private data class TextRun(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fontSize: Float
)

private data class TextLine(
    val text: String,
    val y: Float,
    val height: Float,
    val fontSize: Float
)

private class LayoutAwareStripper : PDFTextStripper() {
    val runs = mutableListOf<TextRun>()

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        for (position in textPositions) {
            val unicode = position.unicode ?: continue
            if (unicode.isEmpty() || unicode == "\n" || unicode == "\r") continue
            runs += TextRun(
                text = unicode,
                x = position.xDirAdj,
                y = position.yDirAdj,
                width = max(position.widthDirAdj, 0.05f),
                height = max(position.heightDir, position.fontSizeInPt * 0.65f),
                fontSize = max(position.fontSizeInPt, position.heightDir)
            )
        }
    }
}

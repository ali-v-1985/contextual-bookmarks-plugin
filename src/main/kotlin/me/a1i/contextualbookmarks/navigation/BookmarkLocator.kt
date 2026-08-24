package me.a1i.contextualbookmarks.navigation

import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.LocationSignature
import me.a1i.contextualbookmarks.model.LocationSignatures
import kotlin.math.abs

sealed interface BookmarkLocationResult {
    val line: Int?

    data class Live(override val line: Int) : BookmarkLocationResult
    data class Exact(override val line: Int) : BookmarkLocationResult
    data class Relocated(override val line: Int) : BookmarkLocationResult
    data class Ambiguous(val candidates: List<Int>) : BookmarkLocationResult {
        override val line: Int? = null
    }
    data object Missing : BookmarkLocationResult {
        override val line: Int? = null
    }
}

class BookmarkLocator(private val searchRadius: Int = 200) {
    fun locate(
        record: BookmarkRecord,
        lines: List<String>,
        liveMarkerLine: Int? = null,
        firstLine: Int = 0,
    ): BookmarkLocationResult {
        val availableLines = firstLine until (firstLine + lines.size)
        if (liveMarkerLine != null && liveMarkerLine in availableLines) {
            return BookmarkLocationResult.Live(liveMarkerLine)
        }
        if (lines.isEmpty()) return BookmarkLocationResult.Missing

        val signature = record.signature()
        if (signature.isEmpty) {
            return if (record.line in availableLines) BookmarkLocationResult.Exact(record.line)
            else BookmarkLocationResult.Missing
        }

        if (record.line in availableLines && matchesCurrent(lines, firstLine, record.line, signature)) {
            return BookmarkLocationResult.Exact(record.line)
        }

        val center = record.line.coerceIn(availableLines)
        val candidates = availableLines
            .asSequence()
            .filter { abs(it - center) <= searchRadius }
            .filter { matchesCurrent(lines, firstLine, it, signature) }
            .toList()

        if (candidates.isEmpty()) return BookmarkLocationResult.Missing
        if (candidates.size == 1) return BookmarkLocationResult.Relocated(candidates.single())

        val scored = candidates.map { it to neighborScore(lines, firstLine, it, signature) }
        val bestScore = scored.maxOf { it.second }
        val best = scored.filter { it.second == bestScore }.map { it.first }
        return if (best.size == 1 && bestScore > 0) BookmarkLocationResult.Relocated(best.single())
        else BookmarkLocationResult.Ambiguous(best.sorted())
    }

    private fun matchesCurrent(
        lines: List<String>,
        firstLine: Int,
        line: Int,
        signature: LocationSignature,
    ): Boolean = LocationSignatures.hash(lines[line - firstLine]) == signature.currentLineHash

    private fun neighborScore(
        lines: List<String>,
        firstLine: Int,
        line: Int,
        signature: LocationSignature,
    ): Int {
        var score = 0
        val localLine = line - firstLine
        if (signature.previousLineHash.isNotBlank() && localLine > 0 &&
            LocationSignatures.hash(lines[localLine - 1]) == signature.previousLineHash
        ) score++
        if (signature.nextLineHash.isNotBlank() && localLine + 1 < lines.size &&
            LocationSignatures.hash(lines[localLine + 1]) == signature.nextLineHash
        ) score++
        return score
    }
}

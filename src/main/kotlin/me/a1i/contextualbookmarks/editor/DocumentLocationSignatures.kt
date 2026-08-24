package me.a1i.contextualbookmarks.editor

import com.intellij.openapi.editor.Document
import me.a1i.contextualbookmarks.model.LocationSignature
import me.a1i.contextualbookmarks.model.LocationSignatures

object DocumentLocationSignatures {
    fun fromDocument(document: Document, line: Int): LocationSignature {
        if (line !in 0 until document.lineCount) return LocationSignature()
        return LocationSignature(
            currentLineHash = hashLine(document, line),
            previousLineHash = if (line > 0) hashLine(document, line - 1) else "",
            nextLineHash = if (line + 1 < document.lineCount) hashLine(document, line + 1) else "",
        )
    }

    private fun hashLine(document: Document, line: Int): String {
        val text = document.charsSequence.subSequence(
            document.getLineStartOffset(line),
            document.getLineEndOffset(line),
        ).toString()
        return LocationSignatures.hash(text)
    }
}

package me.a1i.contextualbookmarks.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import me.a1i.contextualbookmarks.model.BookmarkLocationStatus
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.LocationSignatures
import me.a1i.contextualbookmarks.navigation.BookmarkLocationResult
import me.a1i.contextualbookmarks.navigation.BookmarkLocator
import me.a1i.contextualbookmarks.navigation.BookmarkNavigator
import me.a1i.contextualbookmarks.service.BookmarkLivePosition
import me.a1i.contextualbookmarks.service.BookmarkOperationResult
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class BookmarkPositionTracker(private val project: Project) : Disposable {
    private data class DocumentLineWindow(
        val firstLine: Int,
        val lines: List<String>,
    )

    private data class TrackedPosition(
        val document: Document,
        val marker: RangeMarker,
        val highlighters: List<RangeHighlighter>,
        var persistedFileUrl: String,
        var persistedLine: Int,
        var persistedColumn: Int,
    )

    private val tracked = ConcurrentHashMap<String, TrackedPosition>()
    private val manager by lazy { project.service<ContextualBookmarkManager>() }
    private val locator = BookmarkLocator(LOCATION_SEARCH_RADIUS)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        manager.addListener(this, ::requestRefresh)
        manager.addLivePositionProvider(this, ::livePosition)
        requestRefresh()
    }

    fun requestRefresh() {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater(
            { if (started && !project.isDisposed) refreshNow() },
            ModalityState.any(),
            { project.isDisposed },
        )
    }

    fun refreshNow() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!started) return
        val all = manager.allBookmarks().associateBy { it.id }
        val visible = manager.visibleBookmarks()
            .filter { it.locationStatus == BookmarkLocationStatus.AVAILABLE }
            .associateBy { it.id }
        val lineSnapshots = mutableMapOf<Document, MutableMap<Int, String>>()
        val updatesBeforeDisposal = mutableListOf<BookmarkRecord>()
        tracked.entries.removeIf { (id, position) ->
            val record = all[id]
            val visibleRecord = visible[id]
            val currentFileUrl = FileDocumentManager.getInstance().getFile(position.document)?.url
                ?: position.persistedFileUrl
            val editorCount = visibleRecord?.let { openTextEditorCount(currentFileUrl) } ?: 0
            val persistedLocationChanged = record != null && (
                record.fileUrl != position.persistedFileUrl ||
                    record.line != position.persistedLine ||
                    record.column != position.persistedColumn
                )
            val remove = visibleRecord == null || persistedLocationChanged || !position.marker.isValid ||
                editorCount == 0 || editorCount != position.highlighters.size
            if (remove) {
                if (shouldSnapshotTrackedPosition(record, visibleRecord, persistedLocationChanged)) {
                    updatesBeforeDisposal += snapshotPosition(checkNotNull(record), position)
                }
                dispose(position)
            }
            remove
        }
        manager.updateLocations(updatesBeforeDisposal)

        visible.values.forEach { record ->
            val position = tracked[record.id]
            if (position == null) {
                createTrackedPosition(record, lineSnapshots)?.let { tracked[record.id] = it }
            } else {
                position.highlighters.forEach { it.gutterIconRenderer = renderer(record) }
            }
        }
    }

    fun flushPositions() {
        val flush = {
            val byId = manager.allBookmarks().associateBy { it.id }
            val updated = tracked.mapNotNull { (id, position) ->
                val record = byId[id] ?: return@mapNotNull null
                snapshotPosition(record, position)
            }
            manager.updateLocations(updated)
            val persistedById = manager.allBookmarks().associateBy { it.id }
            tracked.forEach { (id, position) ->
                val persisted = persistedById[id] ?: return@forEach
                position.persistedFileUrl = persisted.fileUrl
                position.persistedLine = persisted.line
                position.persistedColumn = persisted.column
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) flush() else ApplicationManager.getApplication().invokeAndWait(flush)
    }

    fun liveMarkerLine(bookmarkId: String): Int? {
        return livePosition(bookmarkId)?.line
    }

    fun livePosition(bookmarkId: String): BookmarkLivePosition? {
        val position = tracked[bookmarkId] ?: return null
        if (!position.marker.isValid) return null
        val fileUrl = FileDocumentManager.getInstance().getFile(position.document)?.url ?: position.persistedFileUrl
        val line = position.document.getLineNumber(position.marker.startOffset.coerceIn(0, position.document.textLength))
        val column = position.marker.startOffset - position.document.getLineStartOffset(line)
        return BookmarkLivePosition(fileUrl, line, column)
    }

    fun livePositions(): Map<String, BookmarkLivePosition> = tracked.keys.mapNotNull { id ->
        livePosition(id)?.let { id to it }
    }.toMap()

    internal fun trackedBookmarkIds(): Set<String> = tracked.keys.toSet()

    override fun dispose() {
        if (started && !project.isDisposed) flushPositions()
        started = false
        tracked.values.forEach(::dispose)
        tracked.clear()
    }

    private fun createTrackedPosition(
        record: BookmarkRecord,
        lineSnapshots: MutableMap<Document, MutableMap<Int, String>>,
    ): TrackedPosition? {
        val file = VirtualFileManager.getInstance().findFileByUrl(record.fileUrl) ?: return null
        val editors = FileEditorManager.getInstance(project).getEditors(file)
            .asSequence()
            .filterIsInstance<TextEditor>()
            .map { it.editor }
            .toList()
        val document = editors.firstOrNull()?.document ?: return null
        if (document.lineCount == 0) return null
        val window = documentLineWindow(document, record.line, lineSnapshots)
        val location = locator.locate(record, window.lines, firstLine = window.firstLine)
        val line = when (location) {
            is BookmarkLocationResult.Live -> location.line
            is BookmarkLocationResult.Exact -> location.line
            is BookmarkLocationResult.Relocated -> location.line
            is BookmarkLocationResult.Ambiguous -> {
                manager.updateLocationStatus(record.id, BookmarkLocationStatus.AMBIGUOUS)
                return null
            }
            BookmarkLocationResult.Missing -> {
                manager.updateLocationStatus(record.id, BookmarkLocationStatus.MISSING)
                return null
            }
        }
        val signature = LocationSignatures.fromLines(window.lines, line - window.firstLine)
        val resolvedRecord = record.copy(
            line = line,
            currentLineHash = signature.currentLineHash,
            previousLineHash = signature.previousLineHash,
            nextLineHash = signature.nextLineHash,
            locationStatus = BookmarkLocationStatus.AVAILABLE,
        )
        val trackedRecord = if (resolvedRecord == record) {
            record
        } else {
            when (val update = manager.updateLocation(resolvedRecord)) {
                is BookmarkOperationResult.Updated -> update.record
                else -> return null
            }
        }
        val trackedLine = trackedRecord.line
        val start = document.getLineStartOffset(trackedLine)
        val end = document.getLineEndOffset(trackedLine)
        val offset = (start + trackedRecord.column).coerceAtMost(end)
        val marker = document.createRangeMarker(offset, offset)
        val highlighters = editors.map { editor ->
            editor.markupModel.addRangeHighlighter(
                start,
                end,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null,
                HighlighterTargetArea.LINES_IN_RANGE,
            ).also { it.gutterIconRenderer = renderer(trackedRecord) }
        }
        return TrackedPosition(
            document = document,
            marker = marker,
            highlighters = highlighters,
            persistedFileUrl = trackedRecord.fileUrl,
            persistedLine = trackedRecord.line,
            persistedColumn = trackedRecord.column,
        )
    }

    private fun snapshotPosition(record: BookmarkRecord, position: TrackedPosition): BookmarkRecord {
        if (!position.marker.isValid) return record.copy(locationStatus = BookmarkLocationStatus.MISSING)
        val line = position.document.getLineNumber(
            position.marker.startOffset.coerceIn(0, position.document.textLength),
        )
        val column = position.marker.startOffset - position.document.getLineStartOffset(line)
        val signature = DocumentLocationSignatures.fromDocument(position.document, line)
        val fileUrl = FileDocumentManager.getInstance().getFile(position.document)?.url ?: record.fileUrl
        return record.copy(
            fileUrl = fileUrl,
            line = line,
            column = column,
            currentLineHash = signature.currentLineHash,
            previousLineHash = signature.previousLineHash,
            nextLineHash = signature.nextLineHash,
            locationStatus = BookmarkLocationStatus.AVAILABLE,
        )
    }

    private fun openTextEditorCount(fileUrl: String): Int {
        val file = VirtualFileManager.getInstance().findFileByUrl(fileUrl) ?: return 0
        return FileEditorManager.getInstance(project).getEditors(file).count { it is TextEditor }
    }

    private fun renderer(record: BookmarkRecord) = ContextualBookmarkGutterRenderer(record.id, record.mnemonic) { id ->
        manager.allBookmarks().firstOrNull { it.id == id }?.let { project.service<BookmarkNavigator>().navigate(it) }
    }

    private fun dispose(position: TrackedPosition) {
        position.highlighters.forEach { it.dispose() }
        position.marker.dispose()
    }

    private fun documentLineWindow(
        document: Document,
        persistedLine: Int,
        snapshots: MutableMap<Document, MutableMap<Int, String>>,
    ): DocumentLineWindow {
        val center = persistedLine.coerceIn(0, document.lineCount - 1)
        val firstLine = (center - LOCATION_SEARCH_RADIUS - 1).coerceAtLeast(0)
        val lastLine = (center + LOCATION_SEARCH_RADIUS + 1).coerceAtMost(document.lineCount - 1)
        val documentSnapshots = snapshots.getOrPut(document) { mutableMapOf() }
        val lines = (firstLine..lastLine).map { line ->
            documentSnapshots.getOrPut(line) {
                val start = document.getLineStartOffset(line)
                val end = document.getLineEndOffset(line)
                document.charsSequence.subSequence(start, end).toString()
            }
        }
        return DocumentLineWindow(firstLine, lines)
    }

    private companion object {
        const val LOCATION_SEARCH_RADIUS = 200
    }
}

internal fun shouldSnapshotTrackedPosition(
    record: BookmarkRecord?,
    visibleRecord: BookmarkRecord?,
    persistedLocationChanged: Boolean,
): Boolean = record?.locationStatus == BookmarkLocationStatus.AVAILABLE &&
    visibleRecord != null && !persistedLocationChanged

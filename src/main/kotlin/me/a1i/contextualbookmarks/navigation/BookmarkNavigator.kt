package me.a1i.contextualbookmarks.navigation

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import me.a1i.contextualbookmarks.editor.BookmarkPositionTracker
import me.a1i.contextualbookmarks.model.BookmarkLocationStatus
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.LocationSignatures
import me.a1i.contextualbookmarks.service.BookmarkLivePosition
import me.a1i.contextualbookmarks.service.BookmarkOperationResult
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class BookmarkNavigator(private val project: Project) {
    private val locator = BookmarkLocator()
    private val manager by lazy { project.service<ContextualBookmarkManager>() }
    private val tracker by lazy { project.service<BookmarkPositionTracker>() }

    fun navigate(record: BookmarkRecord): CompletableFuture<BookmarkLocationResult> {
        val future = CompletableFuture<BookmarkLocationResult>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val currentRecord = manager.allBookmarks().firstOrNull { it.id == record.id }
                if (currentRecord == null) {
                    future.complete(BookmarkLocationResult.Missing)
                    return@executeOnPooledThread
                }
                val livePositions = ApplicationManager.getApplication().runReadAction<Map<String, BookmarkLivePosition>> {
                    tracker.livePositions()
                }
                val livePosition = livePositions[currentRecord.id]
                val file = sequenceOf(livePosition?.fileUrl, currentRecord.fileUrl)
                    .filterNotNull()
                    .distinct()
                    .mapNotNull(VirtualFileManager.getInstance()::findFileByUrl)
                    .firstOrNull { it.isValid && !it.isDirectory }
                if (file == null || !file.isValid || file.isDirectory) {
                    val result = BookmarkLocationResult.Missing
                    if (markUnavailable(currentRecord, BookmarkLocationStatus.MISSING)) {
                        notifyUnavailable("Bookmark file is unavailable")
                    }
                    future.complete(result)
                    return@executeOnPooledThread
                }

                val cachedText = ApplicationManager.getApplication().runReadAction<CharSequence?> {
                    ProgressManager.checkCanceled()
                    FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence
                }
                val text = cachedText ?: VfsUtilCore.loadText(file)
                ProgressManager.checkCanceled()
                val lines = text.toString().split('\n').map { it.removeSuffix("\r") }
                val livePositionForFile = livePosition?.takeIf { it.fileUrl == file.url }
                val liveLine = livePositionForFile?.line
                val result = locator.locate(currentRecord, lines, liveLine)
                when (result) {
                    is BookmarkLocationResult.Live,
                    is BookmarkLocationResult.Exact,
                    is BookmarkLocationResult.Relocated,
                    -> {
                        val line = checkNotNull(result.line)
                        val column = if (result is BookmarkLocationResult.Live) {
                            livePositionForFile?.column ?: currentRecord.column
                        } else {
                            currentRecord.column
                        }.coerceIn(0, lines[line].length)
                        val signature = LocationSignatures.fromLines(lines, line)
                        val update = manager.updateLocationFromNavigation(
                            updated = currentRecord.copy(
                                fileUrl = file.url,
                                line = line,
                                column = column,
                                currentLineHash = signature.currentLineHash,
                                previousLineHash = signature.previousLineHash,
                                nextLineHash = signature.nextLineHash,
                                locationStatus = BookmarkLocationStatus.AVAILABLE,
                            ),
                            expectedLocation = currentRecord,
                            livePositions = livePositions,
                        )
                        if (update !is BookmarkOperationResult.Updated) {
                            val rejectedResult = if (update is BookmarkOperationResult.DuplicateLocation) {
                                notifyUnavailable("Bookmark relocation conflicts with another bookmark; relink it from the tool window")
                                BookmarkLocationResult.Ambiguous(listOf(line))
                            } else {
                                BookmarkLocationResult.Missing
                            }
                            future.complete(rejectedResult)
                            return@executeOnPooledThread
                        }
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                val locationIsCurrent = manager.allBookmarks().any { persisted ->
                                    persisted.id == update.record.id && persisted.hasSameLocationAs(update.record)
                                }
                                if (locationIsCurrent) OpenFileDescriptor(project, file, line, column).navigate(true)
                            }
                        }
                    }
                    is BookmarkLocationResult.Ambiguous -> {
                        if (!markUnavailable(currentRecord, BookmarkLocationStatus.AMBIGUOUS)) {
                            future.complete(BookmarkLocationResult.Missing)
                            return@executeOnPooledThread
                        }
                        notifyUnavailable("Bookmark location is ambiguous; relink it from the tool window")
                    }
                    BookmarkLocationResult.Missing -> {
                        if (!markUnavailable(currentRecord, BookmarkLocationStatus.MISSING)) {
                            future.complete(BookmarkLocationResult.Missing)
                            return@executeOnPooledThread
                        }
                        notifyUnavailable("Bookmark location could not be found")
                    }
                }
                future.complete(result)
            } catch (cancellation: ProcessCanceledException) {
                future.cancel(false)
                throw cancellation
            } catch (failure: Exception) {
                future.completeExceptionally(failure)
            }
        }
        return future
    }

    private fun markUnavailable(record: BookmarkRecord, status: BookmarkLocationStatus): Boolean =
        manager.updateLocationStatusIfUnchanged(record, status) is BookmarkOperationResult.Updated

    private fun notifyUnavailable(content: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Contextual Bookmarks")
                    .createNotification(content, NotificationType.WARNING)
                    .notify(project)
            }
        }
    }

    private fun BookmarkRecord.hasSameLocationAs(other: BookmarkRecord): Boolean =
        fileUrl == other.fileUrl && line == other.line && column == other.column &&
            currentLineHash == other.currentLineHash && previousLineHash == other.previousLineHash &&
            nextLineHash == other.nextLineHash && locationStatus == other.locationStatus
}

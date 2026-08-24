package me.a1i.contextualbookmarks.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.a1i.contextualbookmarks.model.BookmarkLocationStatus
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.model.LocationSignatures
import me.a1i.contextualbookmarks.navigation.BookmarkLocationResult
import me.a1i.contextualbookmarks.navigation.BookmarkNavigator
import me.a1i.contextualbookmarks.service.BookmarkOperationResult
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import me.a1i.contextualbookmarks.service.CreateBookmarkRequest
import java.util.concurrent.TimeUnit

class BookmarkPositionTrackerTest : BasePlatformTestCase() {
    fun testDeletingCharacterAtBookmarkColumnKeepsPointAnchorAvailable() {
        val psiFile = myFixture.configureByText("point-anchor.txt", "target")
        val document = myFixture.editor.document
        val manager = project.service<ContextualBookmarkManager>()
        val created = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 0,
                column = 2,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = DocumentLocationSignatures.fromDocument(document, 0),
            ),
        ) as BookmarkOperationResult.Created
        val tracker = BookmarkPositionTracker(project)
        try {
            tracker.start()
            tracker.refreshNow()

            WriteCommandAction.runWriteCommandAction(project) {
                document.deleteString(2, 3)
            }
            tracker.flushPositions()

            assertNotNull(tracker.livePosition(created.record.id))
            val persisted = manager.allBookmarks().single { it.id == created.record.id }
            assertEquals(0, persisted.line)
            assertEquals(BookmarkLocationStatus.AVAILABLE, persisted.locationStatus)
        } finally {
            tracker.dispose()
        }
    }

    fun testNavigationRejectsRelocationOntoUnflushedLiveBookmark() {
        val psiFile = myFixture.configureByText("live-collision.txt", "occupied\nother")
        val document = myFixture.editor.document
        val manager = project.service<ContextualBookmarkManager>()
        val occupied = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 0,
                column = 1,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = DocumentLocationSignatures.fromDocument(document, 0),
            ),
        ) as BookmarkOperationResult.Created
        val relocating = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 1,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = LocationSignatures.fromLines(listOf("occupied"), 0),
            ),
        ) as BookmarkOperationResult.Created
        manager.updateLocationStatus(relocating.record.id, BookmarkLocationStatus.MISSING)
        val tracker = project.service<BookmarkPositionTracker>()
        tracker.start()
        tracker.refreshNow()

        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "inserted-one\ninserted-two\n")
        }
        assertEquals(2, tracker.livePosition(occupied.record.id)?.line)
        assertEquals(0, manager.allBookmarks().single { it.id == occupied.record.id }.line)

        val result = project.service<BookmarkNavigator>()
            .navigate(relocating.record)
            .get(10, TimeUnit.SECONDS)

        assertTrue(result is BookmarkLocationResult.Ambiguous)
        val records = manager.allBookmarks().associateBy { it.id }
        assertEquals(0, records.getValue(occupied.record.id).line)
        assertEquals(BookmarkLocationStatus.AVAILABLE, records.getValue(occupied.record.id).locationStatus)
        assertEquals(1, records.getValue(relocating.record.id).line)
        assertEquals(BookmarkLocationStatus.AMBIGUOUS, records.getValue(relocating.record.id).locationStatus)
    }

    fun testBranchSwitchDoesNotSnapshotMarkerAfterScopeBecomesInvisible() {
        val branchRecord = BookmarkRecord(
            id = "branch",
            fileUrl = "file:///repo/file.txt",
            line = 4,
            scopeKind = BookmarkScopeKind.BRANCH,
            repositoryRootUrl = "file:///repo",
            branchName = "main",
        )

        assertTrue(shouldSnapshotTrackedPosition(branchRecord, branchRecord, persistedLocationChanged = false))
        assertFalse(shouldSnapshotTrackedPosition(branchRecord, visibleRecord = null, persistedLocationChanged = false))
    }

    fun testSameFileRelinkRecreatesMarkerAtAcceptedLocation() {
        val psiFile = myFixture.configureByText("relink.txt", "first\nmiddle\nlast")
        val document = myFixture.editor.document
        val manager = project.service<ContextualBookmarkManager>()
        val created = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 0,
                column = 1,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = DocumentLocationSignatures.fromDocument(document, 0),
            ),
        ) as BookmarkOperationResult.Created
        val tracker = BookmarkPositionTracker(project)
        try {
            tracker.start()
            tracker.refreshNow()

            val relinked = manager.relink(
                id = created.record.id,
                fileUrl = psiFile.virtualFile.url,
                line = 2,
                column = 2,
                signature = DocumentLocationSignatures.fromDocument(document, 2),
            )
            assertTrue(relinked is BookmarkOperationResult.Updated)
            tracker.refreshNow()

            assertEquals(2, tracker.livePosition(created.record.id)?.line)
            assertEquals(2, tracker.livePosition(created.record.id)?.column)
            tracker.flushPositions()
            val persisted = manager.allBookmarks().single { it.id == created.record.id }
            assertEquals(2, persisted.line)
            assertEquals(2, persisted.column)
        } finally {
            tracker.dispose()
        }
    }

    fun testRejectedRelocationDoesNotCreateTrackedPosition() {
        val psiFile = myFixture.configureByText("collision.txt", "occupied\ninserted\ntarget")
        val document = myFixture.editor.document
        val manager = project.service<ContextualBookmarkManager>()
        val occupied = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 2,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = DocumentLocationSignatures.fromDocument(document, 2),
            ),
        ) as BookmarkOperationResult.Created
        val relocating = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 1,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = LocationSignatures.fromLines(listOf("target"), 0),
            ),
        ) as BookmarkOperationResult.Created
        val tracker = BookmarkPositionTracker(project)
        try {
            tracker.start()
            tracker.refreshNow()

            assertEquals(setOf(occupied.record.id), tracker.trackedBookmarkIds())
            val rejected = manager.allBookmarks().single { it.id == relocating.record.id }
            assertEquals(1, rejected.line)
            assertEquals(BookmarkLocationStatus.AMBIGUOUS, rejected.locationStatus)
        } finally {
            tracker.dispose()
        }
    }

    fun testNavigationDoesNotOpenRejectedRelocation() {
        val psiFile = myFixture.configureByText("navigation-collision.txt", "occupied\ninserted\ntarget")
        val document = myFixture.editor.document
        val manager = project.service<ContextualBookmarkManager>()
        manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 2,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = DocumentLocationSignatures.fromDocument(document, 2),
            ),
        )
        val relocating = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 1,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = LocationSignatures.fromLines(listOf("target"), 0),
            ),
        ) as BookmarkOperationResult.Created
        myFixture.editor.caretModel.moveToLogicalPosition(LogicalPosition(0, 0))

        val result = project.service<BookmarkNavigator>()
            .navigate(relocating.record)
            .get(10, TimeUnit.SECONDS)

        assertTrue(result is BookmarkLocationResult.Ambiguous)
        assertEquals(0, myFixture.editor.caretModel.logicalPosition.line)
        val rejected = manager.allBookmarks().single { it.id == relocating.record.id }
        assertEquals(1, rejected.line)
        assertEquals(BookmarkLocationStatus.AMBIGUOUS, rejected.locationStatus)
    }

    fun testRefreshFlushesMovedLineBeforeDisposingClosedEditorMarker() {
        val psiFile = myFixture.configureByText("closed.txt", "target\nnext")
        val document = myFixture.editor.document
        val manager = project.service<ContextualBookmarkManager>()
        val created = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 0,
                column = 2,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = LocationSignatures.fromLines(listOf("target", "next"), 0),
            ),
        ) as BookmarkOperationResult.Created
        val tracker = BookmarkPositionTracker(project)
        try {
            tracker.start()
            tracker.refreshNow()
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(0, "inserted\n")
            }

            FileEditorManager.getInstance(project).closeFile(psiFile.virtualFile)
            tracker.refreshNow()

            assertTrue(tracker.trackedBookmarkIds().isEmpty())
            val persisted = manager.allBookmarks().single { it.id == created.record.id }
            assertEquals(1, persisted.line)
            assertEquals(2, persisted.column)
            assertEquals(LocationSignatures.hash("target"), persisted.currentLineHash)
        } finally {
            tracker.dispose()
        }
    }

    fun testTracksUnsignedRecordWhenPersistedLineIsInRange() {
        val psiFile = myFixture.configureByText("unsigned.txt", "first\ntarget\nlast")
        val manager = project.service<ContextualBookmarkManager>()
        val created = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 1,
                scopeKind = BookmarkScopeKind.GLOBAL,
            ),
        ) as BookmarkOperationResult.Created
        val tracker = BookmarkPositionTracker(project)
        try {
            tracker.start()
            tracker.refreshNow()

            assertEquals(setOf(created.record.id), tracker.trackedBookmarkIds())
            assertEquals(1, tracker.liveMarkerLine(created.record.id))
            assertEquals(
                LocationSignatures.hash("target"),
                manager.allBookmarks().single { it.id == created.record.id }.currentLineHash,
            )
        } finally {
            tracker.dispose()
        }
    }

    fun testNavigationUsesLiveFileUrlAfterTrackedFileRename() {
        val psiFile = myFixture.configureByText("before.txt", "target\nnext")
        val document = myFixture.editor.document
        val manager = project.service<ContextualBookmarkManager>()
        val created = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 0,
                column = 2,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = LocationSignatures.fromLines(listOf("target", "next"), 0),
            ),
        ) as BookmarkOperationResult.Created
        val tracker = project.service<BookmarkPositionTracker>()
        tracker.start()
        tracker.refreshNow()

        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "XX")
            psiFile.virtualFile.rename(this, "after.txt")
        }
        val persistedBeforeNavigation = manager.allBookmarks().single { it.id == created.record.id }
        assertEquals(created.record.fileUrl, persistedBeforeNavigation.fileUrl)

        val result = project.service<BookmarkNavigator>()
            .navigate(persistedBeforeNavigation)
            .get(10, TimeUnit.SECONDS)

        assertTrue(result is BookmarkLocationResult.Live)
        val persisted = manager.allBookmarks().single { it.id == created.record.id }
        assertEquals(psiFile.virtualFile.url, persisted.fileUrl)
        assertEquals(4, persisted.column)
    }

    fun testRelocatesPersistedSignatureBeforeCreatingLiveMarker() {
        val psiFile = myFixture.configureByText("relocated.txt", "inserted\ntarget\nnext")
        val manager = project.service<ContextualBookmarkManager>()
        val created = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 0,
                column = 2,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = LocationSignatures.fromLines(listOf("target", "next"), 0),
            ),
        ) as BookmarkOperationResult.Created
        val tracker = BookmarkPositionTracker(project)
        try {
            tracker.start()
            tracker.refreshNow()

            assertEquals(1, tracker.liveMarkerLine(created.record.id))
            val persisted = manager.allBookmarks().single { it.id == created.record.id }
            assertEquals(1, persisted.line)
            assertEquals(LocationSignatures.hash("target"), persisted.currentLineHash)
        } finally {
            tracker.dispose()
        }
    }

    fun testTracksGutterAndPersistsRangeMovementAfterInsertion() {
        val psiFile = myFixture.configureByText("sample.txt", "target\nnext")
        val editor = myFixture.editor
        val document = editor.document
        val manager = project.service<ContextualBookmarkManager>()
        val created = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 0,
                column = 2,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = LocationSignatures.fromLines(listOf("target", "next"), 0),
            ),
        ) as BookmarkOperationResult.Created
        val tracker = BookmarkPositionTracker(project)
        try {
            tracker.start()
            tracker.refreshNow()
            assertEquals(setOf(created.record.id), tracker.trackedBookmarkIds())

            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(0, "inserted\n")
            }
            tracker.flushPositions()

            val persisted = manager.allBookmarks().single { it.id == created.record.id }
            assertEquals(1, persisted.line)
            assertEquals(2, persisted.column)
            assertEquals(LocationSignatures.hash("target"), persisted.currentLineHash)
        } finally {
            tracker.dispose()
        }
        assertTrue(tracker.trackedBookmarkIds().isEmpty())
    }
}

package me.a1i.contextualbookmarks.actions

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.a1i.contextualbookmarks.editor.BookmarkPositionTracker
import me.a1i.contextualbookmarks.editor.DocumentLocationSignatures
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.service.BookmarkOperationResult
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import me.a1i.contextualbookmarks.service.CreateBookmarkRequest

class RelativeBookmarkActionPlatformTest : BasePlatformTestCase() {
    fun testUsesTrackedLinesAfterUnsavedDocumentEdit() {
        val psiFile = myFixture.configureByText(
            "relative.txt",
            "head\nfirst\nbetween\nsecond\ntail",
        )
        val document = myFixture.editor.document
        val manager = project.service<ContextualBookmarkManager>()
        val first = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 1,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = DocumentLocationSignatures.fromDocument(document, 1),
            ),
        ) as BookmarkOperationResult.Created
        val second = manager.create(
            CreateBookmarkRequest(
                fileUrl = psiFile.virtualFile.url,
                line = 3,
                scopeKind = BookmarkScopeKind.GLOBAL,
                signature = DocumentLocationSignatures.fromDocument(document, 3),
            ),
        ) as BookmarkOperationResult.Created
        val tracker = project.service<BookmarkPositionTracker>()
        tracker.start()
        tracker.refreshNow()

        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.getLineStartOffset(2), "inserted-one\ninserted-two\n")
        }
        assertEquals(3, manager.allBookmarks().single { it.id == second.record.id }.line)

        val selected = relativeBookmarkForNavigation(
            project = project,
            fileUrl = psiFile.virtualFile.url,
            line = 4,
            column = 0,
            forward = true,
        )

        assertEquals(second.record.id, selected?.id)
        assertEquals(5, manager.allBookmarks().single { it.id == second.record.id }.line)
        assertEquals(1, manager.allBookmarks().single { it.id == first.record.id }.line)
    }
}

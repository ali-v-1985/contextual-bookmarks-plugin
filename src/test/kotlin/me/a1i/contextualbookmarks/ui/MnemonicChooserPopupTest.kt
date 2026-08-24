package me.a1i.contextualbookmarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.service.BookmarkOperationResult
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import me.a1i.contextualbookmarks.service.CreateBookmarkRequest

class MnemonicChooserPopupTest : BasePlatformTestCase() {
    fun testExistingMnemonicIsPrefilledAndBlankInputClearsIt() {
        val manager = project.getService(ContextualBookmarkManager::class.java)
        manager.delete(manager.allBookmarks().map { it.id })
        val created = manager.create(
            CreateBookmarkRequest(
                fileUrl = "file:///mnemonic.kt",
                line = 3,
                mnemonic = "B",
                scopeKind = BookmarkScopeKind.GLOBAL,
            ),
        ) as BookmarkOperationResult.Created
        var initialValue: String? = null
        var result: BookmarkOperationResult? = null

        MnemonicChooserPopup.assign(
            project = project,
            record = created.record,
            after = { result = it },
            requestMnemonic = {
                initialValue = it
                " "
            },
        )

        assertEquals("B", initialValue)
        assertTrue(result is BookmarkOperationResult.Updated)
        assertNull(manager.allBookmarks().single { it.id == created.record.id }.mnemonic)
    }
}

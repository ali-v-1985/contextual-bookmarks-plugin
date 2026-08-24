package me.a1i.contextualbookmarks.ui

import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.service.BookmarkOperationResult
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import me.a1i.contextualbookmarks.service.CreateBookmarkRequest
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.tree.TreePath

class ContextualBookmarksPanelTest : BasePlatformTestCase() {
    fun testMnemonicButtonExplainsSelectionAndAssignsToSelectedBookmark() {
        val manager = project.getService(ContextualBookmarkManager::class.java)
        manager.delete(manager.allBookmarks().map { it.id })
        val unselected = manager.create(
            CreateBookmarkRequest(
                fileUrl = "file:///unselected.kt",
                line = 1,
                mnemonic = "B",
                scopeKind = BookmarkScopeKind.GLOBAL,
            ),
        ) as BookmarkOperationResult.Created
        val selected = manager.create(
            CreateBookmarkRequest(
                fileUrl = "file:///selected.kt",
                line = 3,
                scopeKind = BookmarkScopeKind.GLOBAL,
            ),
        ) as BookmarkOperationResult.Created
        val panel = ContextualBookmarksPanel(project)
        Disposer.register(testRootDisposable, panel)
        val mnemonicButton = components(panel)
            .filterIsInstance<JButton>()
            .single { it.text == "Assign mnemonic…" }
        val details = components(panel).filterIsInstance<JTextArea>().single()

        assertFalse(mnemonicButton.isEnabled)
        assertTrue(details.text.startsWith("Select a bookmark"))

        val tree = components(panel).filterIsInstance<JTree>().single()
        val root = tree.model.root as BookmarkTreeNode
        val nodes = root.depthFirstEnumeration()
        var recordNode: BookmarkTreeNode? = null
        while (nodes.hasMoreElements()) {
            val node = nodes.nextElement() as? BookmarkTreeNode ?: continue
            if (node.record?.id == selected.record.id) {
                recordNode = node
                break
            }
        }
        assertNotNull(recordNode)
        val selectedNode = recordNode!!
        tree.selectionPath = TreePath(selectedNode.path)
        assertTrue(mnemonicButton.isEnabled)
        assertFalse(details.text.startsWith("Select a bookmark"))

        tree.clearSelection()
        assertFalse(mnemonicButton.isEnabled)
        assertTrue(details.text.startsWith("Select a bookmark"))

        tree.selectionPath = TreePath(selectedNode.path)
        assertTrue(mnemonicButton.isEnabled)

        val previousDialog = TestDialogManager.setTestInputDialog(TestInputDialog { "a" })
        try {
            mnemonicButton.doClick()
        } finally {
            TestDialogManager.setTestInputDialog(previousDialog)
        }

        val recordsById = manager.allBookmarks().associateBy { it.id }
        assertEquals("A", recordsById.getValue(selected.record.id).mnemonic)
        assertEquals("B", recordsById.getValue(unselected.record.id).mnemonic)
    }

    private fun components(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) {
            component.components.forEach { yieldAll(components(it)) }
        }
    }
}

package me.a1i.contextualbookmarks.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ActionLocalizationTest : BasePlatformTestCase() {
    fun testRegisteredActionsAndGroupsUseLocalizedPresentations() {
        val expectedTexts = mapOf(
            "ContextualBookmarks.Group" to "Contextual Bookmarks",
            "ContextualBookmarks.Toggle" to "Toggle Contextual Bookmark",
            "ContextualBookmarks.AddMnemonic" to "Add Contextual Mnemonic Bookmark…",
            "ContextualBookmarks.AddGlobal" to "Add Global Contextual Bookmark",
            "ContextualBookmarks.Show" to "Show Contextual Bookmarks",
            "ContextualBookmarks.Next" to "Next Contextual Bookmark",
            "ContextualBookmarks.Previous" to "Previous Contextual Bookmark",
            "ContextualBookmarks.Mnemonics" to "Navigate by Mnemonic",
        )
        val actionManager = ActionManager.getInstance()

        expectedTexts.forEach { (id, expectedText) ->
            val action = actionManager.getAction(id)
            assertNotNull("Action $id should be registered", action)
            assertEquals(expectedText, action!!.templatePresentation.text)
        }

        assertEquals(
            "Create or remove a bookmark in the preferred context scope",
            actionManager.getAction("ContextualBookmarks.Toggle").templatePresentation.description,
        )

        val contextualBookmarks = actionManager.getAction("ContextualBookmarks.Group")
        val editorPopup = actionManager.getAction("EditorPopupMenu") as ActionGroup
        val gutterPopup = actionManager.getAction("EditorGutterPopupMenu") as ActionGroup
        assertFalse(editorPopup.getChildren(null).contains(contextualBookmarks))
        assertTrue(gutterPopup.getChildren(null).contains(contextualBookmarks))
    }
}

package me.a1i.contextualbookmarks.actions

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager

class EditorBookmarkActionTest : BasePlatformTestCase() {
    fun testCreatesBookmarkAtRightClickedGutterLine() {
        val psiFile = myFixture.configureByText(
            "gutter.txt",
            "caret line\nother line\nclicked line",
        )
        val editor = myFixture.editor
        editor.caretModel.moveToLogicalPosition(LogicalPosition(0, 4))
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
            .add(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR, 2)
            .build()
        val action = AddGlobalBookmarkAction()
        val event = AnActionEvent.createEvent(
            action,
            dataContext,
            action.templatePresentation.clone(),
            ActionPlaces.EDITOR_GUTTER_POPUP,
            ActionUiKind.POPUP,
            null,
        )

        action.actionPerformed(event)

        val bookmark = project.service<ContextualBookmarkManager>().allBookmarks().single()
        assertEquals(2, bookmark.line)
        assertEquals(0, bookmark.column)
    }
}

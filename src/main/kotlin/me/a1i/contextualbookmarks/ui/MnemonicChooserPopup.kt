package me.a1i.contextualbookmarks.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import me.a1i.contextualbookmarks.actions.scopeLabel
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.navigation.BookmarkNavigator
import me.a1i.contextualbookmarks.service.BookmarkOperationResult
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import javax.swing.JList

object MnemonicChooserPopup {
    fun showNavigation(project: Project, records: List<BookmarkRecord>) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(records)
            .setTitle("Choose Contextual Bookmark")
            .setNamerForFiltering { record ->
                listOfNotNull(record.mnemonic, record.description, record.fileUrl.substringAfterLast('/'))
                    .joinToString(" ")
            }
            .setRenderer(object : ColoredListCellRenderer<BookmarkRecord>() {
                override fun customizeCellRenderer(
                    list: JList<out BookmarkRecord>,
                    value: BookmarkRecord,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(value.mnemonic.orEmpty(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${value.scopeLabel()}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(value.description ?: value.fileUrl.substringAfterLast('/'))
                }
            })
            .setItemChosenCallback { project.service<BookmarkNavigator>().navigate(it) }
            .createPopup()
            .showInFocusCenter()
    }

    fun assign(project: Project, record: BookmarkRecord, after: (BookmarkOperationResult) -> Unit) {
        val mnemonic = Messages.showInputDialog(
            project,
            "Enter one digit or Latin letter, or leave empty to clear",
            "Assign Mnemonic",
            Messages.getQuestionIcon(),
        ) ?: return
        after(project.service<ContextualBookmarkManager>().edit(record.id, record.description, mnemonic.ifBlank { null }))
    }
}

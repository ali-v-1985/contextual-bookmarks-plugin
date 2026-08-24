package me.a1i.contextualbookmarks.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel

class BookmarkScopeStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Contextual Bookmark Scope"
    override fun isAvailable(project: Project): Boolean = !project.isDefault
    override fun createWidget(project: Project): StatusBarWidget = BookmarkScopeStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "ContextualBookmarks.Scope"
    }
}

private class BookmarkScopeStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val manager = project.service<ContextualBookmarkManager>()
    private val label = JLabel().apply {
        border = JBUI.Borders.empty(0, 6)
        toolTipText = "Preferred scope for new contextual bookmarks"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) = showScopeChooser()
        })
    }

    init {
        manager.addListener(this) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) refresh()
            }
        }
        refresh()
    }

    override fun ID(): String = BookmarkScopeStatusBarWidgetFactory.ID
    override fun getComponent(): JComponent = label
    override fun install(statusBar: StatusBar) = Unit
    override fun dispose() = Unit

    private fun refresh() {
        val context = manager.contextSnapshot()
        val branches = context.branches.joinToString { it.branchName }
        val changelist = context.activeChangelist?.displayName
        label.text = buildString {
            append("Bookmarks: ")
            append(manager.preferredScope().name.lowercase().replaceFirstChar(Char::uppercase))
            if (branches.isNotBlank()) append(" · $branches")
            if (!changelist.isNullOrBlank()) append(" · $changelist")
        }
    }

    private fun showScopeChooser() {
        JBPopupFactory.getInstance().createPopupChooserBuilder(BookmarkScopeKind.entries.toList())
            .setTitle("Preferred Bookmark Scope")
            .setItemChosenCallback(manager::setPreferredScope)
            .createPopup()
            .showUnderneathOf(label)
    }
}

package me.a1i.contextualbookmarks.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import me.a1i.contextualbookmarks.context.BookmarkContextResolver
import me.a1i.contextualbookmarks.editor.DocumentLocationSignatures
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.navigation.BookmarkNavigator
import me.a1i.contextualbookmarks.service.BookmarkCreationContext
import me.a1i.contextualbookmarks.service.BookmarkOperationResult
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import me.a1i.contextualbookmarks.service.CreateBookmarkRequest
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JToolBar
import javax.swing.JTree
import javax.swing.tree.TreePath

class ContextualBookmarksPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val manager = project.service<ContextualBookmarkManager>()
    private val resolver = project.service<BookmarkContextResolver>()
    private val tree = JTree()
    private val details = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        preferredSize = Dimension(220, 100)
        text = SELECTION_GUIDANCE
    }
    private val activeOnly = JCheckBox("Active contexts only", true)
    private val scope = JComboBox(BookmarkScopeKind.entries.toTypedArray()).apply {
        selectedItem = manager.preferredScope()
    }
    private val mnemonicButton = JButton("Assign mnemonic…").apply {
        isEnabled = false
        toolTipText = "Assign, change, or clear the mnemonic of the selected bookmark"
        addActionListener { selectedRecord()?.let(::assignMnemonic) }
    }

    init {
        val toolbar = JToolBar().apply {
            isFloatable = false
            add(JButton("Add current").apply { addActionListener { addCurrent() } })
            add(JButton("Navigate").apply { addActionListener { selectedRecord()?.let(::navigate) } })
            add(mnemonicButton)
            add(JButton("Rename").apply { addActionListener { selectedRecord()?.let(::editDescription) } })
            add(JButton("Reassign").apply { addActionListener { selectedRecord()?.let(::reassign) } })
            add(JButton("Relink").apply { addActionListener { selectedRecord()?.let(::relink) } })
            add(JButton("Delete").apply { addActionListener { selectedRecord()?.let(::delete) } })
        }
        val filters = JPanel(FlowLayout(FlowLayout.LEADING, 8, 2)).apply {
            add(JLabel("Create in:"))
            add(scope)
            add(activeOnly)
        }
        val north = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(filters, BorderLayout.SOUTH)
        }
        add(north, BorderLayout.NORTH)
        add(
            JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(tree), JScrollPane(details)).apply {
                resizeWeight = 0.8
            },
            BorderLayout.CENTER,
        )

        scope.addActionListener {
            (scope.selectedItem as? BookmarkScopeKind)?.let(manager::setPreferredScope)
        }
        activeOnly.addActionListener { refresh() }
        tree.addTreeSelectionListener { updateSelection() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) selectedRecord()?.let(::navigate)
            }
        })
        manager.addListener(this) {
            ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) refresh() }
        }
        refresh()
    }

    fun refresh() {
        val selectedId = selectedRecord()?.id
        tree.model = ContextualBookmarksTreeModel.build(manager.allBookmarks(), manager.contextSnapshot(), activeOnly.isSelected)
        for (row in 0 until tree.rowCount) tree.expandRow(row)
        selectedId?.let(::selectId)
        scope.selectedItem = manager.preferredScope()
        updateSelection()
    }

    override fun dispose() = Unit

    private fun selectedRecord(): BookmarkRecord? = (tree.lastSelectedPathComponent as? BookmarkTreeNode)?.record

    private fun updateSelection() {
        val record = selectedRecord()
        mnemonicButton.isEnabled = record != null
        details.text = record?.let {
            buildString {
                appendLine(it.description ?: "No description")
                appendLine(it.fileUrl)
                append("Line ${it.line + 1}, ${it.scopeKind.name.lowercase()}, ${it.locationStatus.name.lowercase()}")
            }
        } ?: SELECTION_GUIDANCE
    }

    private fun selectId(id: String) {
        val root = tree.model.root as? BookmarkTreeNode ?: return
        root.depthFirstEnumeration().asSequence().filterIsInstance<BookmarkTreeNode>().firstOrNull { it.record?.id == id }?.let {
            tree.selectionPath = TreePath(it.path)
        }
    }

    private fun addCurrent() {
        val editor = FileEditorManager.getInstance(project).selectedEditor as? TextEditor ?: return
        val file = editor.file
        val line = editor.editor.caretModel.logicalPosition.line
        val document = editor.editor.document
        val result = manager.create(
            CreateBookmarkRequest(
                file.url,
                line,
                editor.editor.caretModel.logicalPosition.column,
                signature = DocumentLocationSignatures.fromDocument(document, line),
                context = BookmarkCreationContext(resolver.branchForFile(file), resolver.activeChangelist()),
            ),
        )
        showError(result)
    }

    private fun navigate(record: BookmarkRecord) = project.service<BookmarkNavigator>().navigate(record).let { Unit }

    private fun assignMnemonic(record: BookmarkRecord) = MnemonicChooserPopup.assign(project, record, ::showError)

    private fun editDescription(record: BookmarkRecord) {
        val value = Messages.showInputDialog(project, "Bookmark description", "Edit Contextual Bookmark", Messages.getQuestionIcon(), record.description, null)
            ?: return
        showError(manager.edit(record.id, value, record.mnemonic))
    }

    private fun reassign(record: BookmarkRecord) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(BookmarkScopeKind.entries.toList())
            .setTitle("Reassign Contextual Bookmark")
            .setItemChosenCallback { kind -> reassign(record, kind) }
            .createPopup()
            .showInFocusCenter()
    }

    private fun reassign(record: BookmarkRecord, kind: BookmarkScopeKind) {
        val file = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(record.fileUrl)
        showError(
            manager.reassign(
                record.id,
                kind,
                BookmarkCreationContext(file?.let(resolver::branchForFile), resolver.activeChangelist()),
            ),
        )
    }

    private fun relink(record: BookmarkRecord) {
        val editor = FileEditorManager.getInstance(project).selectedEditor as? TextEditor ?: return
        val document = editor.editor.document
        val line = editor.editor.caretModel.logicalPosition.line
        val signature = DocumentLocationSignatures.fromDocument(document, line)
        showError(
            manager.relink(
                id = record.id,
                fileUrl = editor.file.url,
                line = line,
                column = editor.editor.caretModel.logicalPosition.column,
                signature = signature,
            ),
        )
    }

    private fun delete(record: BookmarkRecord) {
        if (Messages.showYesNoDialog(project, "Delete this contextual bookmark?", "Delete Bookmark", Messages.getQuestionIcon()) == Messages.YES) {
            manager.delete(listOf(record.id))
        }
    }

    private fun showError(result: BookmarkOperationResult) {
        val message = when (result) {
            is BookmarkOperationResult.ScopeUnavailable -> "${result.scopeKind.name.lowercase()} scope is unavailable in the current context"
            is BookmarkOperationResult.MnemonicConflict -> if (result.records.isEmpty()) {
                "Mnemonics must be one digit or Latin letter"
            } else {
                "That mnemonic is already used in this exact scope"
            }
            is BookmarkOperationResult.DuplicateLocation ->
                "A contextual bookmark already exists at this line in the selected scope"
            is BookmarkOperationResult.AmbiguousToggle -> "Several records match; choose one in the tree"
            BookmarkOperationResult.ReadOnly ->
                "Bookmarks are read-only because this project contains data from a newer plugin version"
            BookmarkOperationResult.NotFound -> "The bookmark no longer exists"
            else -> null
        }
        message?.let { Messages.showWarningDialog(project, it, "Contextual Bookmarks") }
    }

    private companion object {
        const val SELECTION_GUIDANCE =
            "Select a bookmark to navigate, assign a mnemonic, rename, reassign, relink, or delete it."
    }
}

private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
    while (hasMoreElements()) yield(nextElement())
}

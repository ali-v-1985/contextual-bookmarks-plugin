package me.a1i.contextualbookmarks.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import me.a1i.contextualbookmarks.context.BookmarkContextResolver
import me.a1i.contextualbookmarks.editor.BookmarkPositionTracker
import me.a1i.contextualbookmarks.editor.DocumentLocationSignatures
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.model.MnemonicResolution
import me.a1i.contextualbookmarks.navigation.BookmarkNavigator
import me.a1i.contextualbookmarks.service.BookmarkCreationContext
import me.a1i.contextualbookmarks.service.BookmarkOperationResult
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager
import me.a1i.contextualbookmarks.service.CreateBookmarkRequest
import me.a1i.contextualbookmarks.ui.MnemonicChooserPopup

abstract class EditorBookmarkAction : DumbAwareAction() {
    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val hasEditor = event.getData(CommonDataKeys.EDITOR) != null
        event.presentation.isVisible = project != null && hasEditor && file != null
        if (project == null || !hasEditor || file == null) {
            event.presentation.isEnabled = false
            return
        }
        val resolver = project.service<BookmarkContextResolver>()
        event.presentation.isEnabled = when (scopeForUpdate(project)) {
            BookmarkScopeKind.GLOBAL -> true
            BookmarkScopeKind.BRANCH -> resolver.branchForFile(file) != null
            BookmarkScopeKind.CHANGELIST -> resolver.activeChangelist() != null
        }
    }

    protected open fun scopeForUpdate(project: Project): BookmarkScopeKind =
        project.service<ContextualBookmarkManager>().preferredScope()

    protected fun request(event: AnActionEvent, scopeKind: BookmarkScopeKind? = null, mnemonic: String? = null): CreateBookmarkRequest? {
        val project = event.project ?: return null
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val gutterLine = event.getData(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR)
        val caretPosition = editor.caretModel.logicalPosition
        val line = (gutterLine ?: caretPosition.line).coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
        val resolver = project.service<BookmarkContextResolver>()
        return CreateBookmarkRequest(
            fileUrl = file.url,
            line = line,
            column = if (gutterLine == null) caretPosition.column else 0,
            mnemonic = mnemonic,
            signature = DocumentLocationSignatures.fromDocument(editor.document, line),
            scopeKind = scopeKind,
            context = BookmarkCreationContext(
                branch = resolver.branchForFile(file),
                changelist = resolver.activeChangelist(),
            ),
        )
    }

    protected fun report(event: AnActionEvent, result: BookmarkOperationResult) {
        val project = event.project ?: return
        val message = when (result) {
            is BookmarkOperationResult.ScopeUnavailable -> when (result.scopeKind) {
                BookmarkScopeKind.BRANCH -> "Branch scope is unavailable for this file or detached HEAD"
                BookmarkScopeKind.CHANGELIST -> "No active changelist is available"
                BookmarkScopeKind.GLOBAL -> "Global bookmark scope is unavailable"
            }
            is BookmarkOperationResult.MnemonicConflict -> if (result.records.isEmpty()) {
                "Mnemonics must be one digit or Latin letter"
            } else {
                "Mnemonic ${result.mnemonic} is already used in this exact scope"
            }
            is BookmarkOperationResult.DuplicateLocation ->
                "A contextual bookmark already exists at this line in the selected scope"
            is BookmarkOperationResult.AmbiguousToggle -> "Several bookmarks match this line and scope; use the tool window"
            BookmarkOperationResult.ReadOnly ->
                "Bookmarks are read-only because this project contains data from a newer plugin version"
            else -> return
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Contextual Bookmarks")
            .createNotification(message, NotificationType.WARNING).notify(project)
    }

}

class ToggleContextualBookmarkAction : EditorBookmarkAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val request = request(event) ?: return
        report(event, event.project!!.service<ContextualBookmarkManager>().toggle(request))
    }
}

class AddGlobalBookmarkAction : EditorBookmarkAction() {
    override fun scopeForUpdate(project: Project): BookmarkScopeKind = BookmarkScopeKind.GLOBAL

    override fun actionPerformed(event: AnActionEvent) {
        val request = request(event, BookmarkScopeKind.GLOBAL) ?: return
        report(event, event.project!!.service<ContextualBookmarkManager>().create(request))
    }
}

class AddMnemonicBookmarkAction : EditorBookmarkAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val value = Messages.showInputDialog(
            event.project,
            "Enter one digit or Latin letter",
            "Add Contextual Mnemonic Bookmark",
            Messages.getQuestionIcon(),
        ) ?: return
        val request = request(event, mnemonic = value) ?: return
        report(event, event.project!!.service<ContextualBookmarkManager>().create(request))
    }
}

class ShowContextualBookmarksAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Contextual Bookmarks")?.show()
    }
}

abstract class RelativeBookmarkAction(private val forward: Boolean) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project?.service<ContextualBookmarkManager>()?.visibleBookmarks()?.isNotEmpty() == true
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)?.url
        val caret = event.getData(CommonDataKeys.EDITOR)?.caretModel?.logicalPosition
        relativeBookmarkForNavigation(
            project = project,
            fileUrl = file,
            line = caret?.line ?: -1,
            column = caret?.column ?: -1,
            forward = forward,
        )?.let { project.service<BookmarkNavigator>().navigate(it) }
    }
}

internal fun relativeBookmarkForNavigation(
    project: Project,
    fileUrl: String?,
    line: Int,
    column: Int,
    forward: Boolean,
): BookmarkRecord? {
    project.service<BookmarkPositionTracker>().flushPositions()
    return relativeBookmarkForNavigation(
        records = project.service<ContextualBookmarkManager>().visibleBookmarks(),
        fileUrl = fileUrl,
        line = line,
        column = column,
        forward = forward,
    )
}

internal fun relativeBookmarkForNavigation(
    records: Iterable<BookmarkRecord>,
    fileUrl: String?,
    line: Int,
    column: Int,
    forward: Boolean,
): BookmarkRecord? {
    val ordered = records.sortedWith(
        compareBy(
            BookmarkRecord::fileUrl,
            BookmarkRecord::line,
            BookmarkRecord::column,
            BookmarkRecord::order,
            BookmarkRecord::id,
        ),
    )
    if (ordered.isEmpty()) return null
    if (fileUrl == null) return if (forward) ordered.first() else ordered.last()

    val candidate = if (forward) {
        ordered.firstOrNull { comparePosition(it, fileUrl, line, column) > 0 }
    } else {
        ordered.lastOrNull { comparePosition(it, fileUrl, line, column) < 0 }
    }
    return candidate ?: if (forward) ordered.first() else ordered.last()
}

private fun comparePosition(record: BookmarkRecord, fileUrl: String, line: Int, column: Int): Int {
    val fileComparison = record.fileUrl.compareTo(fileUrl)
    if (fileComparison != 0) return fileComparison
    val lineComparison = record.line.compareTo(line)
    if (lineComparison != 0) return lineComparison
    return record.column.compareTo(column)
}

class NextContextualBookmarkAction : RelativeBookmarkAction(true)
class PreviousContextualBookmarkAction : RelativeBookmarkAction(false)

abstract class NavigateMnemonicAction(private val mnemonic: String) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val activeFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val activeRoot = activeFile?.let { project.service<BookmarkContextResolver>().branchForFile(it)?.repositoryRootUrl }
        when (val resolution = project.service<ContextualBookmarkManager>().resolveMnemonic(mnemonic, activeRoot)) {
            MnemonicResolution.None -> NotificationGroupManager.getInstance()
                .getNotificationGroup("Contextual Bookmarks")
                .createNotification("No visible contextual bookmark uses mnemonic $mnemonic", NotificationType.INFORMATION)
                .notify(project)
            is MnemonicResolution.Selected -> project.service<BookmarkNavigator>().navigate(resolution.record)
            is MnemonicResolution.Choices -> MnemonicChooserPopup.showNavigation(project, resolution.records)
        }
    }
}

class NavigateMnemonic0Action : NavigateMnemonicAction("0")
class NavigateMnemonic1Action : NavigateMnemonicAction("1")
class NavigateMnemonic2Action : NavigateMnemonicAction("2")
class NavigateMnemonic3Action : NavigateMnemonicAction("3")
class NavigateMnemonic4Action : NavigateMnemonicAction("4")
class NavigateMnemonic5Action : NavigateMnemonicAction("5")
class NavigateMnemonic6Action : NavigateMnemonicAction("6")
class NavigateMnemonic7Action : NavigateMnemonicAction("7")
class NavigateMnemonic8Action : NavigateMnemonicAction("8")
class NavigateMnemonic9Action : NavigateMnemonicAction("9")
class NavigateMnemonicAAction : NavigateMnemonicAction("A")
class NavigateMnemonicBAction : NavigateMnemonicAction("B")
class NavigateMnemonicCAction : NavigateMnemonicAction("C")
class NavigateMnemonicDAction : NavigateMnemonicAction("D")
class NavigateMnemonicEAction : NavigateMnemonicAction("E")
class NavigateMnemonicFAction : NavigateMnemonicAction("F")
class NavigateMnemonicGAction : NavigateMnemonicAction("G")
class NavigateMnemonicHAction : NavigateMnemonicAction("H")
class NavigateMnemonicIAction : NavigateMnemonicAction("I")
class NavigateMnemonicJAction : NavigateMnemonicAction("J")
class NavigateMnemonicKAction : NavigateMnemonicAction("K")
class NavigateMnemonicLAction : NavigateMnemonicAction("L")
class NavigateMnemonicMAction : NavigateMnemonicAction("M")
class NavigateMnemonicNAction : NavigateMnemonicAction("N")
class NavigateMnemonicOAction : NavigateMnemonicAction("O")
class NavigateMnemonicPAction : NavigateMnemonicAction("P")
class NavigateMnemonicQAction : NavigateMnemonicAction("Q")
class NavigateMnemonicRAction : NavigateMnemonicAction("R")
class NavigateMnemonicSAction : NavigateMnemonicAction("S")
class NavigateMnemonicTAction : NavigateMnemonicAction("T")
class NavigateMnemonicUAction : NavigateMnemonicAction("U")
class NavigateMnemonicVAction : NavigateMnemonicAction("V")
class NavigateMnemonicWAction : NavigateMnemonicAction("W")
class NavigateMnemonicXAction : NavigateMnemonicAction("X")
class NavigateMnemonicYAction : NavigateMnemonicAction("Y")
class NavigateMnemonicZAction : NavigateMnemonicAction("Z")

internal fun BookmarkRecord.scopeLabel(): String = when (scopeKind) {
    BookmarkScopeKind.GLOBAL -> "Global"
    BookmarkScopeKind.BRANCH -> "Branch ${branchName.orEmpty()} (${repositoryRootUrl.orEmpty()})"
    BookmarkScopeKind.CHANGELIST -> "Changelist ${changelistName.orEmpty()}"
}

package me.a1i.contextualbookmarks.startup

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vcs.BranchChangeListener
import com.intellij.openapi.vcs.BranchRenameListener
import com.intellij.openapi.vcs.changes.ChangeList
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vfs.VirtualFile
import me.a1i.contextualbookmarks.editor.BookmarkPositionTracker
import me.a1i.contextualbookmarks.service.ContextualBookmarkManager

class ContextualBookmarksProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val manager = project.service<ContextualBookmarkManager>()
        val tracker = project.service<BookmarkPositionTracker>()
        manager.refreshContext()
        tracker.start()

        val connection = project.messageBus.connect(project)
        connection.subscribe(BranchChangeListener.VCS_BRANCH_CHANGED, object : BranchChangeListener {
            override fun branchWillChange(branchName: String) = tracker.flushPositions()
            override fun branchHasChanged(branchName: String) {
                manager.refreshContext()
                tracker.requestRefresh()
            }
        })
        connection.subscribe(BranchRenameListener.VCS_BRANCH_RENAMED, object : BranchRenameListener {
            override fun branchNameChanged(root: VirtualFile, oldName: String, newName: String) {
                manager.handleBranchRename(root.url, oldName, newName)
                manager.refreshContext()
            }
        })
        connection.subscribe(
            VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
            VcsRepositoryMappingListener {
                refreshAfterRepositoryMappingChange(
                    flushPositions = tracker::flushPositions,
                    refreshContext = manager::refreshContext,
                    requestRefresh = tracker::requestRefresh,
                )
            },
        )
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun defaultListChanged(oldDefaultList: ChangeList?, newDefaultList: ChangeList?) {
                tracker.flushPositions()
                manager.refreshContext()
            }

            override fun defaultListChanged(oldDefaultList: ChangeList?, newDefaultList: ChangeList?, automatic: Boolean) {
                tracker.flushPositions()
                manager.refreshContext()
            }

            override fun changeListRenamed(list: ChangeList, oldName: String) {
                manager.refreshContext()
            }

            override fun changeListRemoved(list: ChangeList) {
                manager.refreshContext()
            }
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = tracker.requestRefresh()
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = tracker.requestRefresh()
        })
        connection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) = tracker.flushPositions()
        })
    }
}

internal fun refreshAfterRepositoryMappingChange(
    flushPositions: () -> Unit,
    refreshContext: () -> Unit,
    requestRefresh: () -> Unit,
) {
    flushPositions()
    refreshContext()
    requestRefresh()
}

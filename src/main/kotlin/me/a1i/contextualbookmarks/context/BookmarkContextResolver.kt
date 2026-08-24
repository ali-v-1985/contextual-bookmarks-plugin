package me.a1i.contextualbookmarks.context

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import me.a1i.contextualbookmarks.model.BookmarkContextSnapshot
import me.a1i.contextualbookmarks.model.BranchKey
import me.a1i.contextualbookmarks.model.ChangelistKey

data class RepositoryContext(
    val rootUrl: String,
    val branchName: String?,
)

interface BookmarkContextSource {
    fun repositories(): Collection<RepositoryContext>
    fun repositoryForFile(file: VirtualFile): RepositoryContext?
    fun activeChangelist(): ChangelistKey?
}

private class IdeaBookmarkContextSource(private val project: Project) : BookmarkContextSource {
    private val repositoryManager by lazy { VcsRepositoryManager.getInstance(project) }
    private val changeLists by lazy { ChangeListManager.getInstance(project) }

    override fun repositories(): Collection<RepositoryContext> = repositoryManager.getRepositories().map {
        RepositoryContext(it.root.url, it.currentBranchName)
    }

    override fun repositoryForFile(file: VirtualFile): RepositoryContext? =
        repositoryManager.getRepositoryForFileQuick(file)?.let {
            RepositoryContext(it.root.url, it.currentBranchName)
        }

    override fun activeChangelist(): ChangelistKey? {
        if (!changeLists.areChangeListsEnabled()) return null
        return changeLists.defaultChangeList.let {
            ChangelistKey(it.id, it.name)
        }
    }
}

internal interface BookmarkContextAccess {
    fun snapshot(): BookmarkContextSnapshot
    fun refresh(): BookmarkContextSnapshot
}

@Service(Service.Level.PROJECT)
class BookmarkContextResolver(private val project: Project) {
    private val core by lazy { BookmarkContextResolverCore(IdeaBookmarkContextSource(project)) }

    internal val access: BookmarkContextAccess
        get() = core

    fun snapshot(): BookmarkContextSnapshot = core.snapshot()

    fun refresh(): BookmarkContextSnapshot = core.refresh()

    fun branchForFile(file: VirtualFile): BranchKey? = core.branchForFile(file)

    fun activeChangelist(): ChangelistKey? = core.activeChangelist()
}

internal class BookmarkContextResolverCore(
    private val source: BookmarkContextSource,
) : BookmarkContextAccess {

    @Volatile
    private var currentSnapshot: BookmarkContextSnapshot = readSnapshot()

    override fun snapshot(): BookmarkContextSnapshot = currentSnapshot

    override fun refresh(): BookmarkContextSnapshot = readSnapshot().also { currentSnapshot = it }

    fun branchForFile(file: VirtualFile): BranchKey? = source.repositoryForFile(file)?.let {
        val branch = it.branchName ?: return null
        BranchKey(it.rootUrl, branch)
    }

    fun activeChangelist(): ChangelistKey? = source.activeChangelist()

    private fun readSnapshot(): BookmarkContextSnapshot = BookmarkContextSnapshot(
        branches = source.repositories().mapNotNullTo(linkedSetOf()) {
            val branch = it.branchName ?: return@mapNotNullTo null
            BranchKey(it.rootUrl, branch)
        },
        activeChangelist = source.activeChangelist(),
    )
}

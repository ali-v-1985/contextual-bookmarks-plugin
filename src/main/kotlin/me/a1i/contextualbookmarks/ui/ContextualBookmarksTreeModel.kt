package me.a1i.contextualbookmarks.ui

import me.a1i.contextualbookmarks.model.BookmarkContextSnapshot
import me.a1i.contextualbookmarks.model.BookmarkLocationStatus
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.model.BookmarkVisibilityPolicy
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class BookmarkTreeNode(
    private val label: String,
    val record: BookmarkRecord? = null,
) : DefaultMutableTreeNode(record ?: label) {
    override fun toString(): String = label
}

object ContextualBookmarksTreeModel {
    fun build(
        records: List<BookmarkRecord>,
        context: BookmarkContextSnapshot,
        activeOnly: Boolean,
    ): DefaultTreeModel {
        val root = BookmarkTreeNode("Contextual Bookmarks")
        val visibleIds = BookmarkVisibilityPolicy.visible(records, context).mapTo(hashSetOf()) { it.id }
        val included = if (activeOnly) records.filter { it.id in visibleIds } else records
        val unavailableVisibleIds = if (activeOnly) emptySet() else included
            .filter { it.id in visibleIds && it.locationStatus != BookmarkLocationStatus.AVAILABLE }
            .mapTo(hashSetOf()) { it.id }
        val scoped = included.filter { it.id !in unavailableVisibleIds }

        addGroup(root, "Global", scoped.filter { it.scopeKind == BookmarkScopeKind.GLOBAL })

        context.branches.sortedWith(compareBy({ it.repositoryRootUrl }, { it.branchName })).forEach { branch ->
            addGroup(
                root,
                "Branch · ${branch.branchName} · ${branch.repositoryRootUrl.substringAfterLast('/')}",
                scoped.filter {
                    it.scopeKind == BookmarkScopeKind.BRANCH &&
                        it.repositoryRootUrl == branch.repositoryRootUrl && it.branchName == branch.branchName
                },
            )
        }

        context.activeChangelist?.let { changelist ->
            addGroup(
                root,
                "Changelist · ${changelist.displayName}",
                scoped.filter { it.scopeKind == BookmarkScopeKind.CHANGELIST && it.changelistId == changelist.id },
            )
        }

        if (!activeOnly) {
            addGroup(root, "Other / Missing Contexts", scoped.filter { it.id !in visibleIds })
            addGroup(root, "Unavailable Locations", included.filter { it.id in unavailableVisibleIds })
        }
        return DefaultTreeModel(root)
    }

    private fun addGroup(root: BookmarkTreeNode, name: String, records: List<BookmarkRecord>) {
        if (records.isEmpty()) return
        val group = BookmarkTreeNode(name)
        records.sortedWith(compareBy(BookmarkRecord::order, BookmarkRecord::id)).forEach { record ->
            val mnemonic = record.mnemonic?.let { "[$it] " }.orEmpty()
            val description = record.description ?: record.fileUrl.substringAfterLast('/').ifBlank { record.fileUrl }
            group.add(BookmarkTreeNode("$mnemonic$description:${record.line + 1}", record))
        }
        root.add(group)
    }
}

package me.a1i.contextualbookmarks.model

object BookmarkVisibilityPolicy {
    fun isVisible(record: BookmarkRecord, context: BookmarkContextSnapshot): Boolean = when (record.scopeKind) {
        BookmarkScopeKind.GLOBAL -> true
        BookmarkScopeKind.BRANCH -> {
            val root = record.repositoryRootUrl
            val branch = record.branchName
            root != null && branch != null && BranchKey(root, branch) in context.branches
        }
        BookmarkScopeKind.CHANGELIST -> {
            val id = record.changelistId
            id != null && id == context.activeChangelist?.id
        }
    }

    fun visible(records: Iterable<BookmarkRecord>, context: BookmarkContextSnapshot): List<BookmarkRecord> =
        records.filter { isVisible(it, context) }.sortedWith(compareBy(BookmarkRecord::order, BookmarkRecord::id))
}

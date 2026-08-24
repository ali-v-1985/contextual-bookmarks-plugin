package me.a1i.contextualbookmarks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkVisibilityPolicyTest {
    @Test
    fun `global records remain visible without vcs`() {
        assertTrue(BookmarkVisibilityPolicy.isVisible(record(BookmarkScopeKind.GLOBAL), BookmarkContextSnapshot.EMPTY))
    }

    @Test
    fun `branch visibility matches both root and branch`() {
        val record = record(BookmarkScopeKind.BRANCH).copy(repositoryRootUrl = "file:///a", branchName = "main")
        val matching = BookmarkContextSnapshot(branches = setOf(BranchKey("file:///a", "main")))
        val wrongRoot = BookmarkContextSnapshot(branches = setOf(BranchKey("file:///b", "main")))
        val wrongBranch = BookmarkContextSnapshot(branches = setOf(BranchKey("file:///a", "feature")))

        assertTrue(BookmarkVisibilityPolicy.isVisible(record, matching))
        assertFalse(BookmarkVisibilityPolicy.isVisible(record, wrongRoot))
        assertFalse(BookmarkVisibilityPolicy.isVisible(record, wrongBranch))
    }

    @Test
    fun `detached and missing branch contexts stay hidden`() {
        val record = record(BookmarkScopeKind.BRANCH).copy(repositoryRootUrl = "file:///a", branchName = "main")
        assertFalse(BookmarkVisibilityPolicy.isVisible(record, BookmarkContextSnapshot.EMPTY))
        assertFalse(BookmarkVisibilityPolicy.isVisible(record.copy(branchName = null), BookmarkContextSnapshot.EMPTY))
    }

    @Test
    fun `changelist visibility follows stable id not display name`() {
        val record = record(BookmarkScopeKind.CHANGELIST).copy(changelistId = "id-1", changelistName = "Old")
        val context = BookmarkContextSnapshot(activeChangelist = ChangelistKey("id-1", "Renamed"))
        assertTrue(BookmarkVisibilityPolicy.isVisible(record, context))
        assertFalse(BookmarkVisibilityPolicy.isVisible(record, context.copy(activeChangelist = ChangelistKey("id-2"))))
    }

    @Test
    fun `multiple roots contribute their independently matching records`() {
        val records = listOf(
            record(BookmarkScopeKind.GLOBAL, 1),
            record(BookmarkScopeKind.BRANCH, 2).copy(repositoryRootUrl = "file:///a", branchName = "main"),
            record(BookmarkScopeKind.BRANCH, 3).copy(repositoryRootUrl = "file:///b", branchName = "feature"),
            record(BookmarkScopeKind.BRANCH, 4).copy(repositoryRootUrl = "file:///b", branchName = "main"),
        )
        val context = BookmarkContextSnapshot(
            branches = setOf(BranchKey("file:///a", "main"), BranchKey("file:///b", "feature")),
        )

        assertEquals(listOf("1", "2", "3"), BookmarkVisibilityPolicy.visible(records, context).map { it.id })
    }

    private fun record(scope: BookmarkScopeKind, order: Long = 0) = BookmarkRecord(
        id = order.toString(),
        scopeKind = scope,
        order = order,
    )
}

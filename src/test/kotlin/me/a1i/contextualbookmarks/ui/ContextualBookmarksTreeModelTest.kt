package me.a1i.contextualbookmarks.ui

import me.a1i.contextualbookmarks.model.BookmarkContextSnapshot
import me.a1i.contextualbookmarks.model.BookmarkLocationStatus
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualBookmarksTreeModelTest {
    @Test
    fun `all-contexts tree emits unavailable visible record exactly once`() {
        val records = listOf(
            BookmarkRecord(id = "available", fileUrl = "file:///available", order = 1),
            BookmarkRecord(
                id = "unavailable",
                fileUrl = "file:///unavailable",
                locationStatus = BookmarkLocationStatus.MISSING,
                order = 2,
            ),
            BookmarkRecord(
                id = "inactive",
                fileUrl = "file:///inactive",
                scopeKind = BookmarkScopeKind.BRANCH,
                repositoryRootUrl = "file:///repo",
                branchName = "other",
                locationStatus = BookmarkLocationStatus.AMBIGUOUS,
                order = 3,
            ),
        )

        val root = ContextualBookmarksTreeModel.build(records, BookmarkContextSnapshot.EMPTY, activeOnly = false).root
            as BookmarkTreeNode
        val recordNodes = buildList {
            val nodes = root.depthFirstEnumeration()
            while (nodes.hasMoreElements()) {
                val node = nodes.nextElement() as? BookmarkTreeNode ?: continue
                if (node.record != null) add(node)
            }
        }

        assertEquals(mapOf("available" to 1, "unavailable" to 1, "inactive" to 1), recordNodes.groupingBy { it.record!!.id }.eachCount())
        assertEquals(
            "Unavailable Locations",
            recordNodes.single { it.record?.id == "unavailable" }.parent.toString(),
        )
        assertEquals(
            "Other / Missing Contexts",
            recordNodes.single { it.record?.id == "inactive" }.parent.toString(),
        )
    }
}

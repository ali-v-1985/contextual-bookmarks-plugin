package me.a1i.contextualbookmarks.actions

import me.a1i.contextualbookmarks.model.BookmarkRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeBookmarkNavigationTest {
    private val first = bookmark("first", "file:///a", 4, order = 30)
    private val second = bookmark("second", "file:///a", 9, order = 20)
    private val last = bookmark("last", "file:///z", 1, order = 10)
    private val creationOrdered = listOf(last, second, first)

    @Test
    fun `next and previous use file position rather than creation order`() {
        assertEquals(
            second,
            relativeBookmarkForNavigation(creationOrdered, first.fileUrl, first.line, first.column, forward = true),
        )
        assertEquals(
            first,
            relativeBookmarkForNavigation(creationOrdered, second.fileUrl, second.line, second.column, forward = false),
        )
    }

    @Test
    fun `previous before first wraps to last and next after last wraps to first`() {
        assertEquals(
            last,
            relativeBookmarkForNavigation(creationOrdered, "file:///a", 0, 0, forward = false),
        )
        assertEquals(
            first,
            relativeBookmarkForNavigation(creationOrdered, "file:///z", 99, 0, forward = true),
        )
    }

    @Test
    fun `navigation without an active file selects the directional endpoint`() {
        assertEquals(first, relativeBookmarkForNavigation(creationOrdered, null, -1, -1, forward = true))
        assertEquals(last, relativeBookmarkForNavigation(creationOrdered, null, -1, -1, forward = false))
    }

    private fun bookmark(id: String, fileUrl: String, line: Int, order: Long) = BookmarkRecord(
        id = id,
        fileUrl = fileUrl,
        line = line,
        column = 0,
        order = order,
    )
}

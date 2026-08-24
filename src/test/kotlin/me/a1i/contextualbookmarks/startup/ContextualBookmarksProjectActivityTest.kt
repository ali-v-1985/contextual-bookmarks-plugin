package me.a1i.contextualbookmarks.startup

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualBookmarksProjectActivityTest {
    @Test
    fun `mapping removal flushes positions before context hides branch bookmarks`() {
        val events = mutableListOf<String>()

        refreshAfterRepositoryMappingChange(
            flushPositions = { events += "flush" },
            refreshContext = { events += "refresh-context" },
            requestRefresh = { events += "refresh-gutters" },
        )

        assertEquals(listOf("flush", "refresh-context", "refresh-gutters"), events)
    }
}

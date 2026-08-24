package me.a1i.contextualbookmarks.persistence

import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.model.CURRENT_SCHEMA_VERSION
import me.a1i.contextualbookmarks.model.ContextualBookmarkState
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualBookmarkStateServiceTest {
    @Test
    fun `round trips schema one with every context field`() {
        val service = ContextualBookmarkStateService()
        val records = mutableListOf(
            BookmarkRecord(id = "g", fileUrl = "file:///g", scopeKind = BookmarkScopeKind.GLOBAL, order = 1),
            BookmarkRecord(
                id = "b",
                fileUrl = "file:///b",
                scopeKind = BookmarkScopeKind.BRANCH,
                repositoryRootUrl = "file:///repo",
                branchName = "main",
                order = 2,
            ),
            BookmarkRecord(
                id = "c",
                fileUrl = "file:///c",
                scopeKind = BookmarkScopeKind.CHANGELIST,
                changelistId = "stable-id",
                changelistName = "Work",
                order = 3,
            ),
        )
        service.loadState(
            XmlSerializer.serialize(
                ContextualBookmarkState(
                    preferredScope = BookmarkScopeKind.CHANGELIST,
                    nextOrder = 4,
                    bookmarks = records,
                ),
            ),
        )

        val restoredService = ContextualBookmarkStateService().apply { loadState(service.state) }
        val restored = restoredService.snapshot()
        assertEquals(CURRENT_SCHEMA_VERSION, restored.schemaVersion)
        assertEquals(BookmarkScopeKind.CHANGELIST, restored.preferredScope)
        assertEquals(records, restored.bookmarks)
    }

    @Test
    fun `load sanitizes invalid optional values and positions`() {
        val service = ContextualBookmarkStateService()
        service.loadState(
            XmlSerializer.serialize(
                ContextualBookmarkState(
                    schemaVersion = 0,
                    bookmarks = mutableListOf(
                        BookmarkRecord(
                            id = "x",
                            line = -4,
                            column = -2,
                            mnemonic = "?",
                            description = "   ",
                            scopeKind = BookmarkScopeKind.BRANCH,
                        ),
                    ),
                ),
            ),
        )

        val record = service.snapshot().bookmarks.single()
        assertEquals(0, record.line)
        assertEquals(0, record.column)
        assertNull(record.mnemonic)
        assertNull(record.description)
        assertEquals(BookmarkScopeKind.BRANCH, record.scopeKind)
        assertNull(record.repositoryRootUrl)
        assertTrue(service.snapshot().nextOrder >= 1)
    }

    @Test
    fun `state snapshots and updates cannot mutate service state by alias`() {
        val service = ContextualBookmarkStateService()
        service.updateBookmarks { it += BookmarkRecord(id = "one") }
        val snapshot = service.snapshot()
        snapshot.bookmarks.clear()

        assertEquals(listOf("one"), service.snapshot().bookmarks.map { it.id })
    }

    @Test
    fun `future schema XML is preserved verbatim and mutations are blocked`() {
        val service = ContextualBookmarkStateService()
        val futureVersion = CURRENT_SCHEMA_VERSION + 1
        val futureXml = Element("state").addContent(
            Element("option")
                .setAttribute("name", "schemaVersion")
                .setAttribute("value", futureVersion.toString()),
        )
        futureXml.addContent(
            Element("option")
                .setAttribute("name", "futureOnlyField")
                .setAttribute("value", "preserve-me"),
        )
        futureXml.addContent(
            Element("option")
                .setAttribute("name", "preferredScope")
                .setAttribute("value", "FUTURE_SCOPE"),
        )
        service.loadState(futureXml)

        val update = service.updateBookmarks { it += BookmarkRecord(id = "known-field-update") }

        val savedXml = service.state
        val unknownField = savedXml.getChildren("option")
            .firstOrNull { it.getAttributeValue("name") == "futureOnlyField" }
        val futureEnum = savedXml.getChildren("option")
            .firstOrNull { it.getAttributeValue("name") == "preferredScope" }
        assertTrue(service.isReadOnlyForFutureSchema())
        assertFalse(update.accepted)
        assertEquals(futureVersion, service.snapshot().schemaVersion)
        assertTrue(service.snapshot().bookmarks.isEmpty())
        assertNotNull(unknownField)
        assertEquals("preserve-me", unknownField?.getAttributeValue("value"))
        assertEquals("FUTURE_SCOPE", futureEnum?.getAttributeValue("value"))
    }
}

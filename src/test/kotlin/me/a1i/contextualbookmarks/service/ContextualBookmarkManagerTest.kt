package me.a1i.contextualbookmarks.service

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Disposer
import com.intellij.util.xmlb.XmlSerializer
import me.a1i.contextualbookmarks.context.BookmarkContextResolverCore
import me.a1i.contextualbookmarks.context.BookmarkContextSource
import me.a1i.contextualbookmarks.context.RepositoryContext
import me.a1i.contextualbookmarks.model.BookmarkLocationStatus
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.model.BranchKey
import me.a1i.contextualbookmarks.model.ChangelistKey
import me.a1i.contextualbookmarks.model.ContextualBookmarkState
import me.a1i.contextualbookmarks.model.CURRENT_SCHEMA_VERSION
import me.a1i.contextualbookmarks.model.LocationSignature
import me.a1i.contextualbookmarks.persistence.ContextualBookmarkStateService
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualBookmarkManagerTest {
    @Test
    fun `create and edit results reflect sanitized descriptions`() {
        val (manager, _) = manager()

        val created = manager.create(
            CreateBookmarkRequest("file:///sanitized", 1, description = "  Description  "),
        ) as BookmarkOperationResult.Created
        assertEquals("Description", created.record.description)
        assertEquals("Description", manager.allBookmarks().single().description)

        val edited = manager.edit(created.record.id, "   ", null) as BookmarkOperationResult.Updated
        assertNull(edited.record.description)
        assertNull(manager.allBookmarks().single().description)
    }

    @Test
    fun `setting preferred scope to current value does not notify listeners`() {
        val (manager, _) = manager()
        val disposable = Disposer.newDisposable()
        var notifications = 0
        try {
            manager.addListener(disposable) { notifications++ }

            manager.setPreferredScope(BookmarkScopeKind.GLOBAL)
            manager.setPreferredScope(BookmarkScopeKind.BRANCH)
            manager.setPreferredScope(BookmarkScopeKind.BRANCH)

            assertEquals(1, notifications)
            assertEquals(BookmarkScopeKind.BRANCH, manager.preferredScope())
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `create toggle edit delete and preferred scope are persistent`() {
        val (manager, state) = manager()
        manager.setPreferredScope(BookmarkScopeKind.GLOBAL)
        val created = manager.toggle(CreateBookmarkRequest("file:///a", 4, mnemonic = "a"))
        val record = (created as BookmarkOperationResult.Created).record
        assertEquals("A", record.mnemonic)
        assertEquals(BookmarkScopeKind.GLOBAL, state.snapshot().preferredScope)

        val edited = manager.edit(record.id, "Important", "7") as BookmarkOperationResult.Updated
        assertEquals("Important", edited.record.description)
        assertEquals("7", edited.record.mnemonic)

        assertTrue(manager.toggle(CreateBookmarkRequest("file:///a", 4)) is BookmarkOperationResult.Removed)
        assertTrue(manager.delete(listOf(record.id)) is BookmarkOperationResult.NotFound)
    }

    @Test
    fun `unavailable preferred branch never falls back to global`() {
        val (manager, _) = manager()
        manager.setPreferredScope(BookmarkScopeKind.BRANCH)

        assertEquals(
            BookmarkOperationResult.ScopeUnavailable(BookmarkScopeKind.BRANCH),
            manager.create(CreateBookmarkRequest("file:///a", 1)),
        )
        assertTrue(manager.allBookmarks().isEmpty())
    }

    @Test
    fun `same mnemonic conflicts only within exact scope key`() {
        val (manager, _) = manager()
        val main = BookmarkCreationContext(branch = BranchKey("file:///repo", "main"))
        val feature = BookmarkCreationContext(branch = BranchKey("file:///repo", "feature"))
        val first = manager.create(CreateBookmarkRequest("file:///one", 1, mnemonic = "B", scopeKind = BookmarkScopeKind.BRANCH, context = main))
        assertTrue(first is BookmarkOperationResult.Created)

        assertTrue(
            manager.create(CreateBookmarkRequest("file:///two", 2, mnemonic = "b", scopeKind = BookmarkScopeKind.BRANCH, context = main))
                is BookmarkOperationResult.MnemonicConflict,
        )
        assertTrue(
            manager.create(CreateBookmarkRequest("file:///three", 3, mnemonic = "b", scopeKind = BookmarkScopeKind.BRANCH, context = feature))
                is BookmarkOperationResult.Created,
        )
    }

    @Test
    fun `create rejects duplicate effective location within exact scope`() {
        val (manager, _) = manager()
        val created = manager.create(CreateBookmarkRequest("file:///same", 2)) as BookmarkOperationResult.Created
        val persistedDuplicate = manager.create(CreateBookmarkRequest("file:///same", 2, mnemonic = "A"))
        assertEquals(BookmarkOperationResult.DuplicateLocation(created.record), persistedDuplicate)
        val disposable = Disposer.newDisposable()
        try {
            manager.addLivePositionProvider(disposable) { id ->
                if (id == created.record.id) BookmarkLivePosition("file:///same", 8) else null
            }

            val duplicate = manager.create(CreateBookmarkRequest("file:///same", 8))

            assertEquals(BookmarkOperationResult.DuplicateLocation(created.record), duplicate)
            assertEquals(listOf(created.record.id), manager.allBookmarks().map { it.id })
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `create permits same location in a different exact scope`() {
        val (manager, _) = manager()
        manager.create(CreateBookmarkRequest("file:///same", 2, scopeKind = BookmarkScopeKind.GLOBAL))
        val branch = manager.create(
            CreateBookmarkRequest(
                "file:///same",
                2,
                scopeKind = BookmarkScopeKind.BRANCH,
                context = BookmarkCreationContext(branch = BranchKey("file:///repo", "main")),
            ),
        )

        assertTrue(branch is BookmarkOperationResult.Created)
        assertEquals(2, manager.allBookmarks().size)
    }

    @Test
    fun `reassign checks target mnemonic namespace`() {
        val (manager, _) = manager()
        val global = manager.create(CreateBookmarkRequest("file:///global", 1, mnemonic = "C")) as BookmarkOperationResult.Created
        val branchContext = BookmarkCreationContext(branch = BranchKey("file:///repo", "main"))
        val branch = manager.create(
            CreateBookmarkRequest(
                "file:///branch",
                2,
                mnemonic = "C",
                scopeKind = BookmarkScopeKind.BRANCH,
                context = branchContext,
            ),
        ) as BookmarkOperationResult.Created

        assertTrue(
            manager.reassign(branch.record.id, BookmarkScopeKind.GLOBAL, BookmarkCreationContext())
                is BookmarkOperationResult.MnemonicConflict,
        )
        assertEquals(BookmarkScopeKind.GLOBAL, manager.allBookmarks().single { it.id == global.record.id }.scopeKind)
    }

    @Test
    fun `reassign rejects duplicate effective location in target scope`() {
        val (manager, _) = manager()
        val global = manager.create(CreateBookmarkRequest("file:///same", 8)) as BookmarkOperationResult.Created
        val branch = manager.create(
            CreateBookmarkRequest(
                "file:///same",
                2,
                scopeKind = BookmarkScopeKind.BRANCH,
                context = BookmarkCreationContext(branch = BranchKey("file:///repo", "main")),
            ),
        ) as BookmarkOperationResult.Created
        val disposable = Disposer.newDisposable()
        try {
            manager.addLivePositionProvider(disposable) { id ->
                if (id == branch.record.id) BookmarkLivePosition("file:///same", 8) else null
            }

            val result = manager.reassign(branch.record.id, BookmarkScopeKind.GLOBAL, BookmarkCreationContext())

            assertEquals(BookmarkOperationResult.DuplicateLocation(global.record), result)
            assertEquals(BookmarkScopeKind.BRANCH, manager.allBookmarks().single { it.id == branch.record.id }.scopeKind)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `relink rejects duplicate location in the same exact scope`() {
        val (manager, _) = manager()
        val occupied = manager.create(CreateBookmarkRequest("file:///same", 4)) as BookmarkOperationResult.Created
        val moving = manager.create(CreateBookmarkRequest("file:///same", 8)) as BookmarkOperationResult.Created
        val disposable = Disposer.newDisposable()
        try {
            manager.addLivePositionProvider(disposable) { id ->
                if (id == moving.record.id) BookmarkLivePosition("file:///same", 9) else null
            }

            val duplicate = manager.relink(
                moving.record.id,
                "file:///same",
                4,
                2,
                LocationSignature(currentLineHash = "target"),
            )

            assertEquals(BookmarkOperationResult.DuplicateLocation(occupied.record), duplicate)
            val unchanged = manager.allBookmarks().single { it.id == moving.record.id }
            assertEquals(8, unchanged.line)
            assertEquals("", unchanged.currentLineHash)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `branch rename rewrites only matching root and old name`() {
        val (manager, _) = manager()
        fun add(root: String, branch: String) = manager.create(
            CreateBookmarkRequest(
                "file:///$root-$branch",
                0,
                scopeKind = BookmarkScopeKind.BRANCH,
                context = BookmarkCreationContext(branch = BranchKey(root, branch)),
            ),
        )
        add("root-a", "old")
        add("root-b", "old")
        add("root-a", "other")

        manager.handleBranchRename("root-a", "old", "new")
        assertEquals(listOf("new", "old", "other"), manager.allBookmarks().map { it.branchName })
    }

    @Test
    fun `branch rename keeps conflicting records in repairable old context`() {
        val (manager, _) = manager()
        val oldContext = BookmarkCreationContext(branch = BranchKey("root", "old"))
        val newContext = BookmarkCreationContext(branch = BranchKey("root", "new"))
        val target = manager.create(
            CreateBookmarkRequest(
                "file:///same",
                4,
                mnemonic = "A",
                scopeKind = BookmarkScopeKind.BRANCH,
                context = newContext,
            ),
        ) as BookmarkOperationResult.Created
        val duplicateLocation = manager.create(
            CreateBookmarkRequest(
                "file:///same",
                4,
                mnemonic = "B",
                scopeKind = BookmarkScopeKind.BRANCH,
                context = oldContext,
            ),
        ) as BookmarkOperationResult.Created
        val duplicateMnemonic = manager.create(
            CreateBookmarkRequest(
                "file:///other",
                8,
                mnemonic = "A",
                scopeKind = BookmarkScopeKind.BRANCH,
                context = oldContext,
            ),
        ) as BookmarkOperationResult.Created
        val safe = manager.create(
            CreateBookmarkRequest(
                "file:///safe",
                12,
                mnemonic = "C",
                scopeKind = BookmarkScopeKind.BRANCH,
                context = oldContext,
            ),
        ) as BookmarkOperationResult.Created

        manager.handleBranchRename("root", "old", "new")

        val byId = manager.allBookmarks().associateBy { it.id }
        assertEquals("new", byId.getValue(target.record.id).branchName)
        assertEquals("old", byId.getValue(duplicateLocation.record.id).branchName)
        assertEquals(BookmarkLocationStatus.AMBIGUOUS, byId.getValue(duplicateLocation.record.id).locationStatus)
        assertEquals("old", byId.getValue(duplicateMnemonic.record.id).branchName)
        assertEquals(BookmarkLocationStatus.AMBIGUOUS, byId.getValue(duplicateMnemonic.record.id).locationStatus)
        assertEquals("new", byId.getValue(safe.record.id).branchName)
        assertEquals(BookmarkLocationStatus.AVAILABLE, byId.getValue(safe.record.id).locationStatus)
    }

    @Test
    fun `active changelist rename refreshes snapshot but deletion retains missing records`() {
        val source = FakeSource(changelist = ChangelistKey("stable", "Old"))
        val (manager, _) = manager(source)
        manager.refreshContext()
        manager.create(
            CreateBookmarkRequest(
                "file:///change",
                0,
                scopeKind = BookmarkScopeKind.CHANGELIST,
                context = BookmarkCreationContext(changelist = source.changelist),
            ),
        )

        source.changelist = ChangelistKey("stable", "Renamed")
        manager.refreshContext()
        assertEquals("Renamed", manager.allBookmarks().single().changelistName)
        assertEquals(1, manager.visibleBookmarks().size)

        source.changelist = null
        manager.refreshContext()
        assertEquals(1, manager.allBookmarks().size)
        assertTrue(manager.visibleBookmarks().isEmpty())
    }

    @Test
    fun `toggle matches a tracked bookmark at its live moved line`() {
        val (manager, _) = manager()
        val created = manager.create(CreateBookmarkRequest("file:///tracked", 2)) as BookmarkOperationResult.Created
        val disposable = Disposer.newDisposable()
        try {
            manager.addLivePositionProvider(disposable) { id ->
                if (id == created.record.id) BookmarkLivePosition("file:///tracked", 8) else null
            }

            val result = manager.toggle(CreateBookmarkRequest("file:///tracked", 8))
            assertTrue(result is BookmarkOperationResult.Removed)
            assertTrue(manager.allBookmarks().isEmpty())
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `location updates preserve concurrent bookmark metadata and scope changes`() {
        val (manager, _) = manager()
        val original = (
            manager.create(CreateBookmarkRequest("file:///old", 1, mnemonic = "A", description = "Original"))
                as BookmarkOperationResult.Created
            ).record
        manager.edit(original.id, "Edited", "B")

        val result = manager.updateLocation(
            original.copy(
                fileUrl = "file:///moved",
                line = 12,
                column = 3,
                mnemonic = "A",
                description = "Stale",
                scopeKind = BookmarkScopeKind.BRANCH,
                repositoryRootUrl = "file:///stale-root",
                branchName = "stale-branch",
            ),
        ) as BookmarkOperationResult.Updated

        assertEquals("file:///moved", result.record.fileUrl)
        assertEquals(12, result.record.line)
        assertEquals(3, result.record.column)
        assertEquals("B", result.record.mnemonic)
        assertEquals("Edited", result.record.description)
        assertEquals(BookmarkScopeKind.GLOBAL, result.record.scopeKind)
        assertEquals(null, result.record.repositoryRootUrl)
    }

    @Test
    fun `location update does not query editor live positions`() {
        val (manager, _) = manager()
        manager.create(CreateBookmarkRequest("file:///same", 1))
        val moving = manager.create(CreateBookmarkRequest("file:///same", 2)) as BookmarkOperationResult.Created
        val disposable = Disposer.newDisposable()
        var livePositionQueries = 0
        try {
            manager.addLivePositionProvider(disposable) {
                livePositionQueries++
                BookmarkLivePosition("file:///same", 1)
            }

            val result = manager.updateLocation(
                moving.record.copy(line = 3),
                useExistingLivePositions = false,
            )

            assertTrue(result is BookmarkOperationResult.Updated)
            assertEquals(0, livePositionQueries)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `conditional navigation writes do not overwrite a newer relink`() {
        val (manager, _) = manager()
        val captured = manager.create(
            CreateBookmarkRequest(
                fileUrl = "file:///old",
                line = 1,
                signature = LocationSignature(currentLineHash = "old"),
            ),
        ) as BookmarkOperationResult.Created
        val relinked = manager.relink(
            id = captured.record.id,
            fileUrl = "file:///new",
            line = 8,
            column = 3,
            signature = LocationSignature(currentLineHash = "new"),
        ) as BookmarkOperationResult.Updated

        val locationResult = manager.updateLocation(
            updated = captured.record.copy(fileUrl = "file:///stale", line = 4),
            useExistingLivePositions = false,
            expectedLocation = captured.record,
        )
        val statusResult = manager.updateLocationStatus(
            id = captured.record.id,
            status = BookmarkLocationStatus.MISSING,
            expectedLocation = captured.record,
        )

        assertEquals(BookmarkOperationResult.StaleLocation(relinked.record), locationResult)
        assertEquals(BookmarkOperationResult.StaleLocation(relinked.record), statusResult)
        assertEquals(relinked.record, manager.allBookmarks().single())
    }

    @Test
    fun `single location update rejects exact-scope collision and marks mover ambiguous`() {
        val (manager, _) = manager()
        val occupied = manager.create(CreateBookmarkRequest("file:///same", 1)) as BookmarkOperationResult.Created
        val moving = manager.create(CreateBookmarkRequest("file:///same", 2)) as BookmarkOperationResult.Created

        val result = manager.updateLocation(moving.record.copy(line = 1))

        assertEquals(BookmarkOperationResult.DuplicateLocation(occupied.record), result)
        val persisted = manager.allBookmarks().single { it.id == moving.record.id }
        assertEquals(2, persisted.line)
        assertEquals(BookmarkLocationStatus.AMBIGUOUS, persisted.locationStatus)
    }

    @Test
    fun `bulk location update rejects colliding movers and preserves unique locations`() {
        val (manager, _) = manager()
        val first = manager.create(CreateBookmarkRequest("file:///same", 1)) as BookmarkOperationResult.Created
        val second = manager.create(CreateBookmarkRequest("file:///same", 2)) as BookmarkOperationResult.Created

        manager.updateLocations(
            listOf(
                first.record.copy(line = 3),
                second.record.copy(line = 3),
            ),
        )

        val records = manager.allBookmarks()
        assertEquals(setOf(1, 2), records.mapTo(hashSetOf()) { it.line })
        assertTrue(records.all { it.locationStatus == BookmarkLocationStatus.AMBIGUOUS })
    }

    @Test
    fun `status-only updates preserve the current persisted location`() {
        val (manager, _) = manager()
        val record = (
            manager.create(CreateBookmarkRequest("file:///current", 7, column = 4, description = "Current"))
                as BookmarkOperationResult.Created
            ).record

        val result = manager.updateLocationStatus(record.id, BookmarkLocationStatus.AMBIGUOUS) as BookmarkOperationResult.Updated

        assertEquals("file:///current", result.record.fileUrl)
        assertEquals(7, result.record.line)
        assertEquals(4, result.record.column)
        assertEquals("Current", result.record.description)
        assertEquals(BookmarkLocationStatus.AMBIGUOUS, result.record.locationStatus)
    }

    @Test
    fun `future schema makes manager mutations explicitly read only`() {
        val state = ContextualBookmarkStateService()
        val futureXml = XmlSerializer.serialize(
            ContextualBookmarkState(schemaVersion = CURRENT_SCHEMA_VERSION + 1),
        ).apply {
            addContent(
                Element("option")
                    .setAttribute("name", "preferredScope")
                    .setAttribute("value", "FUTURE_SCOPE"),
            )
        }
        state.loadState(futureXml)
        val manager = ContextualBookmarkManagerCore(state, BookmarkContextResolverCore(FakeSource()))
        val disposable = Disposer.newDisposable()
        var notifications = 0
        try {
            manager.addListener(disposable) { notifications++ }

            assertEquals(BookmarkOperationResult.ReadOnly, manager.create(CreateBookmarkRequest("file:///new", 1)))
            assertEquals(BookmarkOperationResult.ReadOnly, manager.toggle(CreateBookmarkRequest("file:///new", 1)))
            assertEquals(BookmarkOperationResult.ReadOnly, manager.delete(listOf("future-record")))
            assertFalse(manager.setPreferredScope(BookmarkScopeKind.BRANCH))

            assertEquals(0, notifications)
            assertTrue(manager.allBookmarks().isEmpty())
            assertEquals(BookmarkScopeKind.GLOBAL, manager.preferredScope())
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun manager(source: FakeSource = FakeSource()): Pair<ContextualBookmarkManagerCore, ContextualBookmarkStateService> {
        val state = ContextualBookmarkStateService()
        val resolver = BookmarkContextResolverCore(source)
        return ContextualBookmarkManagerCore(state, resolver) to state
    }

    private class FakeSource(
        var repositories: Collection<RepositoryContext> = emptyList(),
        var changelist: ChangelistKey? = null,
    ) : BookmarkContextSource {
        override fun repositories(): Collection<RepositoryContext> = repositories
        override fun repositoryForFile(file: VirtualFile): RepositoryContext? = null
        override fun activeChangelist(): ChangelistKey? = changelist
    }
}

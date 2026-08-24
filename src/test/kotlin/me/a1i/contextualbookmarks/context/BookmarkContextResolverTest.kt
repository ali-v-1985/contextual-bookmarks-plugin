package me.a1i.contextualbookmarks.context

import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.vfs.VirtualFile
import me.a1i.contextualbookmarks.model.BranchKey
import me.a1i.contextualbookmarks.model.ChangelistKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarkContextResolverTest {
    @Test
    fun `snapshot excludes detached heads and retains multiple roots`() {
        val source = FakeSource(
            repositoryContexts = mutableListOf(
                RepositoryContext("file:///one", "main"),
                RepositoryContext("file:///two", "feature"),
                RepositoryContext("file:///detached", null),
            ),
            changelist = ChangelistKey("id", "Work"),
        )
        val resolver = BookmarkContextResolverCore(source)

        assertEquals(
            setOf(BranchKey("file:///one", "main"), BranchKey("file:///two", "feature")),
            resolver.snapshot().branches,
        )
        assertEquals(ChangelistKey("id", "Work"), resolver.snapshot().activeChangelist)
    }

    @Test
    fun `file mapping reports branch only for repository with named head`() {
        val attached = LightVirtualFile("attached.txt")
        val detached = LightVirtualFile("detached.txt")
        val outside = LightVirtualFile("outside.txt")
        val source = FakeSource().apply {
            files[attached] = RepositoryContext("file:///repo", "main")
            files[detached] = RepositoryContext("file:///repo", null)
        }
        val resolver = BookmarkContextResolverCore(source)

        assertEquals(BranchKey("file:///repo", "main"), resolver.branchForFile(attached))
        assertNull(resolver.branchForFile(detached))
        assertNull(resolver.branchForFile(outside))
    }

    @Test
    fun `refresh replaces immutable branch and changelist snapshot`() {
        val source = FakeSource(
            repositoryContexts = mutableListOf(RepositoryContext("file:///repo", "main")),
            changelist = ChangelistKey("one", "One"),
        )
        val resolver = BookmarkContextResolverCore(source)
        source.repositoryContexts[0] = RepositoryContext("file:///repo", "feature")
        source.changelist = ChangelistKey("two", "Two")

        val refreshed = resolver.refresh()
        assertEquals(setOf(BranchKey("file:///repo", "feature")), refreshed.branches)
        assertEquals(ChangelistKey("two", "Two"), refreshed.activeChangelist)
    }

    private class FakeSource(
        val repositoryContexts: MutableList<RepositoryContext> = mutableListOf(),
        var changelist: ChangelistKey? = null,
        val files: MutableMap<VirtualFile, RepositoryContext> = mutableMapOf(),
    ) : BookmarkContextSource {
        override fun repositories(): Collection<RepositoryContext> = repositoryContexts
        override fun repositoryForFile(file: VirtualFile): RepositoryContext? = files[file]
        override fun activeChangelist(): ChangelistKey? = changelist
    }
}

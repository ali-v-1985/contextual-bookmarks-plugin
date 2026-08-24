package me.a1i.contextualbookmarks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MnemonicPolicyTest {
    @Test
    fun `normalizes only digits and latin letters`() {
        assertEquals("A", MnemonicPolicy.normalize(" a "))
        assertEquals("7", MnemonicPolicy.normalize('7'))
        assertEquals(null, MnemonicPolicy.normalize("AA"))
        assertEquals(null, MnemonicPolicy.normalize("?"))
    }

    @Test
    fun `same exact scope conflicts but inactive contexts may reuse mnemonic`() {
        val main = branch("main", "A", "main")
        val feature = branch("feature", "A", "feature")

        assertEquals(listOf(main), MnemonicPolicy.sameScopeConflicts(listOf(main, feature), "a", main.exactScopeKey()))
        assertTrue(MnemonicPolicy.sameScopeConflicts(listOf(main), "A", feature.exactScopeKey()).isEmpty())
    }

    @Test
    fun `visible global and context collision requires choices`() {
        val global = BookmarkRecord(id = "global", mnemonic = "A", order = 1)
        val branch = branch("branch", "A", "main", order = 2)
        val context = BookmarkContextSnapshot(branches = setOf(BranchKey("file:///repo", "main")))

        val result = MnemonicPolicy.resolveVisible(listOf(global, branch), "a", context)
        assertEquals(MnemonicResolution.Choices(listOf(global, branch)), result)
    }

    @Test
    fun `active editor root uniquely resolves multi-root collision`() {
        val one = branch("one", "9", "main", "file:///one", 1)
        val two = branch("two", "9", "main", "file:///two", 2)
        val context = BookmarkContextSnapshot(
            branches = setOf(BranchKey("file:///one", "main"), BranchKey("file:///two", "main")),
        )

        assertEquals(
            MnemonicResolution.Selected(two),
            MnemonicPolicy.resolveVisible(listOf(one, two), "9", context, "file:///two"),
        )
    }

    @Test
    fun `inactive branch collision is not a visible conflict`() {
        val main = branch("main", "B", "main")
        val feature = branch("feature", "B", "feature")
        val context = BookmarkContextSnapshot(branches = setOf(BranchKey("file:///repo", "main")))

        assertEquals(MnemonicResolution.Selected(main), MnemonicPolicy.resolveVisible(listOf(main, feature), "B", context))
    }

    private fun branch(
        id: String,
        mnemonic: String,
        name: String,
        root: String = "file:///repo",
        order: Long = 0,
    ) = BookmarkRecord(
        id = id,
        mnemonic = mnemonic,
        scopeKind = BookmarkScopeKind.BRANCH,
        repositoryRootUrl = root,
        branchName = name,
        order = order,
    )
}

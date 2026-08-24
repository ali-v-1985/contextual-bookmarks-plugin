package me.a1i.contextualbookmarks.model

sealed interface MnemonicResolution {
    data object None : MnemonicResolution
    data class Selected(val record: BookmarkRecord) : MnemonicResolution
    data class Choices(val records: List<BookmarkRecord>) : MnemonicResolution
}

object MnemonicPolicy {
    private val allowed = ('0'..'9').toSet() + ('A'..'Z').toSet()

    fun normalize(value: Char?): String? = value?.uppercaseChar()?.takeIf { it in allowed }?.toString()

    fun normalize(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.length == 1) normalize(trimmed[0]) else null
    }

    fun sameScopeConflicts(
        records: Iterable<BookmarkRecord>,
        mnemonic: String?,
        scopeKey: BookmarkScopeKey,
        excludingId: String? = null,
    ): List<BookmarkRecord> {
        val normalized = normalize(mnemonic) ?: return emptyList()
        return records
            .filter { it.id != excludingId }
            .filter { normalize(it.mnemonic) == normalized && it.exactScopeKey() == scopeKey }
            .sortedWith(compareBy(BookmarkRecord::order, BookmarkRecord::id))
    }

    fun visibleCandidates(
        records: Iterable<BookmarkRecord>,
        mnemonic: String?,
        context: BookmarkContextSnapshot,
    ): List<BookmarkRecord> {
        val normalized = normalize(mnemonic) ?: return emptyList()
        return BookmarkVisibilityPolicy.visible(records, context)
            .filter { normalize(it.mnemonic) == normalized }
    }

    fun resolveVisible(
        records: Iterable<BookmarkRecord>,
        mnemonic: String?,
        context: BookmarkContextSnapshot,
        activeEditorRepositoryRootUrl: String? = null,
    ): MnemonicResolution {
        val candidates = visibleCandidates(records, mnemonic, context)
        if (candidates.isEmpty()) return MnemonicResolution.None
        if (candidates.size == 1) return MnemonicResolution.Selected(candidates.single())

        if (activeEditorRepositoryRootUrl != null) {
            val inActiveRoot = candidates.filter {
                it.scopeKind == BookmarkScopeKind.BRANCH &&
                    it.repositoryRootUrl == activeEditorRepositoryRootUrl
            }
            if (inActiveRoot.size == 1) return MnemonicResolution.Selected(inActiveRoot.single())
        }

        return MnemonicResolution.Choices(candidates)
    }
}

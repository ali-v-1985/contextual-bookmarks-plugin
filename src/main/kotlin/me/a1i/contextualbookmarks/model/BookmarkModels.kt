package me.a1i.contextualbookmarks.model

import java.security.MessageDigest
import java.util.UUID

const val CURRENT_SCHEMA_VERSION: Int = 1

enum class BookmarkScopeKind {
    GLOBAL,
    BRANCH,
    CHANGELIST,
}

enum class BookmarkLocationStatus {
    AVAILABLE,
    MISSING,
    AMBIGUOUS,
}

data class BranchKey(
    val repositoryRootUrl: String,
    val branchName: String,
)

data class ChangelistKey(
    val id: String,
    val displayName: String = "",
)

data class BookmarkContextSnapshot(
    val branches: Set<BranchKey> = emptySet(),
    val activeChangelist: ChangelistKey? = null,
) {
    companion object {
        val EMPTY = BookmarkContextSnapshot()
    }
}

data class LocationSignature(
    val currentLineHash: String = "",
    val previousLineHash: String = "",
    val nextLineHash: String = "",
) {
    val isEmpty: Boolean
        get() = currentLineHash.isBlank()
}

object LocationSignatures {
    fun fromLines(lines: List<String>, line: Int): LocationSignature {
        if (line !in lines.indices) return LocationSignature()
        return LocationSignature(
            currentLineHash = hash(lines[line]),
            previousLineHash = lines.getOrNull(line - 1)?.let(::hash).orEmpty(),
            nextLineHash = lines.getOrNull(line + 1)?.let(::hash).orEmpty(),
        )
    }

    fun hash(line: String): String {
        val normalized = line.trim().replace(Regex("\\s+"), " ")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

sealed interface BookmarkScopeKey {
    data object Global : BookmarkScopeKey
    data class Branch(val key: BranchKey) : BookmarkScopeKey
    data class Changelist(val id: String) : BookmarkScopeKey
    data class Incomplete(val kind: BookmarkScopeKind, val discriminator: String) : BookmarkScopeKey
}

data class BookmarkRecord(
    var id: String = UUID.randomUUID().toString(),
    var fileUrl: String = "",
    var line: Int = 0,
    var column: Int = 0,
    var mnemonic: String? = null,
    var description: String? = null,
    var currentLineHash: String = "",
    var previousLineHash: String = "",
    var nextLineHash: String = "",
    var scopeKind: BookmarkScopeKind = BookmarkScopeKind.GLOBAL,
    var repositoryRootUrl: String? = null,
    var branchName: String? = null,
    var changelistId: String? = null,
    var changelistName: String? = null,
    var order: Long = 0,
    var locationStatus: BookmarkLocationStatus = BookmarkLocationStatus.AVAILABLE,
) {
    fun signature(): LocationSignature = LocationSignature(currentLineHash, previousLineHash, nextLineHash)

    fun exactScopeKey(): BookmarkScopeKey = when (scopeKind) {
        BookmarkScopeKind.GLOBAL -> BookmarkScopeKey.Global
        BookmarkScopeKind.BRANCH -> {
            val root = repositoryRootUrl
            val branch = branchName
            if (root != null && branch != null) BookmarkScopeKey.Branch(BranchKey(root, branch))
            else BookmarkScopeKey.Incomplete(scopeKind, listOfNotNull(root, branch).joinToString("\u0000"))
        }
        BookmarkScopeKind.CHANGELIST -> changelistId
            ?.let { BookmarkScopeKey.Changelist(it) }
            ?: BookmarkScopeKey.Incomplete(scopeKind, changelistName.orEmpty())
    }
}

data class ContextualBookmarkState(
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    var preferredScope: BookmarkScopeKind = BookmarkScopeKind.GLOBAL,
    var nextOrder: Long = 1,
    var bookmarks: MutableList<BookmarkRecord> = mutableListOf(),
) {
    fun deepCopy(): ContextualBookmarkState = copy(
        bookmarks = bookmarks.map { it.copy() }.toMutableList(),
    )
}

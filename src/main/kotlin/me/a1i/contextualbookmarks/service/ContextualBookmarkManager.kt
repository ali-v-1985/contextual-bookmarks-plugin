package me.a1i.contextualbookmarks.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import me.a1i.contextualbookmarks.context.BookmarkContextAccess
import me.a1i.contextualbookmarks.context.BookmarkContextResolver
import me.a1i.contextualbookmarks.model.BookmarkContextSnapshot
import me.a1i.contextualbookmarks.model.BookmarkLocationStatus
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.BookmarkScopeKey
import me.a1i.contextualbookmarks.model.BookmarkScopeKind
import me.a1i.contextualbookmarks.model.BookmarkVisibilityPolicy
import me.a1i.contextualbookmarks.model.BranchKey
import me.a1i.contextualbookmarks.model.ChangelistKey
import me.a1i.contextualbookmarks.model.LocationSignature
import me.a1i.contextualbookmarks.model.MnemonicPolicy
import me.a1i.contextualbookmarks.model.MnemonicResolution
import me.a1i.contextualbookmarks.persistence.ContextualBookmarkStateService
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class BookmarkCreationContext(
    val branch: BranchKey? = null,
    val changelist: ChangelistKey? = null,
)

data class BookmarkLivePosition(
    val fileUrl: String,
    val line: Int,
    val column: Int,
) {
    constructor(fileUrl: String, line: Int) : this(fileUrl, line, 0)
}

data class CreateBookmarkRequest(
    val fileUrl: String,
    val line: Int,
    val column: Int = 0,
    val mnemonic: String? = null,
    val description: String? = null,
    val signature: LocationSignature = LocationSignature(),
    val scopeKind: BookmarkScopeKind? = null,
    val context: BookmarkCreationContext = BookmarkCreationContext(),
)

sealed interface BookmarkOperationResult {
    data class Created(val record: BookmarkRecord) : BookmarkOperationResult
    data class Removed(val records: List<BookmarkRecord>) : BookmarkOperationResult
    data class Updated(val record: BookmarkRecord) : BookmarkOperationResult
    data class StaleLocation(val record: BookmarkRecord) : BookmarkOperationResult
    data class MnemonicConflict(val mnemonic: String, val records: List<BookmarkRecord>) : BookmarkOperationResult
    data class DuplicateLocation(val record: BookmarkRecord) : BookmarkOperationResult
    data class ScopeUnavailable(val scopeKind: BookmarkScopeKind) : BookmarkOperationResult
    data class AmbiguousToggle(val records: List<BookmarkRecord>) : BookmarkOperationResult
    data object ReadOnly : BookmarkOperationResult
    data object NotFound : BookmarkOperationResult
}

@Service(Service.Level.PROJECT)
class ContextualBookmarkManager(private val project: Project) {
    private val core by lazy {
        ContextualBookmarkManagerCore(
            stateService = project.service(),
            contextResolver = project.service<BookmarkContextResolver>().access,
        )
    }

    fun allBookmarks(): List<BookmarkRecord> = core.allBookmarks()

    fun visibleBookmarks(): List<BookmarkRecord> = core.visibleBookmarks()

    fun contextSnapshot(): BookmarkContextSnapshot = core.contextSnapshot()

    fun preferredScope(): BookmarkScopeKind = core.preferredScope()

    fun setPreferredScope(scopeKind: BookmarkScopeKind) = core.setPreferredScope(scopeKind)

    fun refreshContext(): BookmarkContextSnapshot = core.refreshContext()

    fun create(request: CreateBookmarkRequest): BookmarkOperationResult = core.create(request)

    fun toggle(request: CreateBookmarkRequest): BookmarkOperationResult = core.toggle(request)

    fun edit(id: String, description: String?, mnemonic: String?): BookmarkOperationResult =
        core.edit(id, description, mnemonic)

    fun delete(ids: Collection<String>): BookmarkOperationResult = core.delete(ids)

    fun reassign(
        id: String,
        scopeKind: BookmarkScopeKind,
        creationContext: BookmarkCreationContext,
    ): BookmarkOperationResult = core.reassign(id, scopeKind, creationContext)

    fun updateLocation(updated: BookmarkRecord): BookmarkOperationResult = core.updateLocation(updated)

    internal fun updateLocationFromNavigation(
        updated: BookmarkRecord,
        expectedLocation: BookmarkRecord,
        livePositions: Map<String, BookmarkLivePosition>,
    ): BookmarkOperationResult = core.updateLocation(
        updated = updated,
        useExistingLivePositions = false,
        expectedLocation = expectedLocation,
        existingLivePositions = livePositions,
    )

    fun relink(
        id: String,
        fileUrl: String,
        line: Int,
        column: Int,
        signature: LocationSignature,
    ): BookmarkOperationResult = core.relink(id, fileUrl, line, column, signature)

    fun updateLocationStatus(id: String, status: BookmarkLocationStatus): BookmarkOperationResult =
        core.updateLocationStatus(id, status)

    internal fun updateLocationStatusIfUnchanged(
        expectedLocation: BookmarkRecord,
        status: BookmarkLocationStatus,
    ): BookmarkOperationResult = core.updateLocationStatus(
        id = expectedLocation.id,
        status = status,
        expectedLocation = expectedLocation,
    )

    fun updateLocations(updatedRecords: Collection<BookmarkRecord>) = core.updateLocations(updatedRecords)

    fun handleBranchRename(rootUrl: String, oldName: String, newName: String) =
        core.handleBranchRename(rootUrl, oldName, newName)

    fun resolveMnemonic(mnemonic: String?, activeEditorRootUrl: String? = null): MnemonicResolution =
        core.resolveMnemonic(mnemonic, activeEditorRootUrl)

    fun addListener(parent: Disposable, listener: () -> Unit) = core.addListener(parent, listener)

    fun addLivePositionProvider(
        parent: Disposable,
        provider: (String) -> BookmarkLivePosition?,
    ) = core.addLivePositionProvider(parent, provider)
}

internal class ContextualBookmarkManagerCore(
    private val stateService: ContextualBookmarkStateService,
    private val contextResolver: BookmarkContextAccess,
) {
    private data class ExactLocationKey(
        val scopeKey: BookmarkScopeKey,
        val fileUrl: String,
        val line: Int,
    )

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var livePositionProvider: ((String) -> BookmarkLivePosition?)? = null

    @Volatile
    private var context: BookmarkContextSnapshot = contextResolver.snapshot()

    fun allBookmarks(): List<BookmarkRecord> = stateService.snapshot().bookmarks
        .sortedWith(compareBy(BookmarkRecord::order, BookmarkRecord::id))

    fun visibleBookmarks(): List<BookmarkRecord> = BookmarkVisibilityPolicy.visible(allBookmarks(), context)

    fun contextSnapshot(): BookmarkContextSnapshot = context

    fun preferredScope(): BookmarkScopeKind = stateService.snapshot().preferredScope

    fun setPreferredScope(scopeKind: BookmarkScopeKind): Boolean {
        if (stateService.isReadOnlyForFutureSchema()) return false
        if (scopeKind == preferredScope()) return true
        val update = stateService.updateState { it.apply { preferredScope = scopeKind } }
        if (!update.accepted) return false
        notifyChanged()
        return true
    }

    fun refreshContext(): BookmarkContextSnapshot = contextResolver.refresh().also {
        context = it
        refreshActiveChangelistName(it.activeChangelist)
        notifyChanged()
    }

    fun create(request: CreateBookmarkRequest): BookmarkOperationResult {
        if (stateService.isReadOnlyForFutureSchema()) return BookmarkOperationResult.ReadOnly
        val scopeKind = request.scopeKind ?: preferredScope()
        val scopeRecord = scopeRecord(scopeKind, request.context) ?: return BookmarkOperationResult.ScopeUnavailable(scopeKind)
        val normalizedMnemonic = MnemonicPolicy.normalize(request.mnemonic)
        if (request.mnemonic != null && normalizedMnemonic == null) {
            return BookmarkOperationResult.MnemonicConflict(request.mnemonic, emptyList())
        }

        var created: BookmarkRecord? = null
        var duplicate: BookmarkRecord? = null
        var conflict: List<BookmarkRecord> = emptyList()
        val stateUpdate = stateService.updateState { state ->
            val candidate = BookmarkRecord(
                id = UUID.randomUUID().toString(),
                fileUrl = request.fileUrl,
                line = request.line.coerceAtLeast(0),
                column = request.column.coerceAtLeast(0),
                mnemonic = normalizedMnemonic,
                description = request.description,
                currentLineHash = request.signature.currentLineHash,
                previousLineHash = request.signature.previousLineHash,
                nextLineHash = request.signature.nextLineHash,
                scopeKind = scopeKind,
                repositoryRootUrl = scopeRecord.repositoryRootUrl,
                branchName = scopeRecord.branchName,
                changelistId = scopeRecord.changelistId,
                changelistName = scopeRecord.changelistName,
                order = state.nextOrder,
            )
            duplicate = duplicateLocation(state.bookmarks, candidate)
            if (duplicate == null) {
                conflict = MnemonicPolicy.sameScopeConflicts(
                    state.bookmarks,
                    candidate.mnemonic,
                    candidate.exactScopeKey(),
                )
            }
            if (duplicate == null && conflict.isEmpty()) {
                state.bookmarks += candidate
                state.nextOrder++
                created = candidate
            }
            state
        }
        if (!stateUpdate.accepted) return BookmarkOperationResult.ReadOnly
        duplicate?.let { return BookmarkOperationResult.DuplicateLocation(it.copy()) }
        if (conflict.isNotEmpty()) return BookmarkOperationResult.MnemonicConflict(normalizedMnemonic!!, conflict)
        val createdId = created?.id ?: return BookmarkOperationResult.NotFound
        val persisted = stateUpdate.state.bookmarks.firstOrNull { it.id == createdId } ?: return BookmarkOperationResult.NotFound
        notifyChanged()
        return BookmarkOperationResult.Created(persisted.copy())
    }

    fun toggle(request: CreateBookmarkRequest): BookmarkOperationResult {
        if (stateService.isReadOnlyForFutureSchema()) return BookmarkOperationResult.ReadOnly
        val scopeKind = request.scopeKind ?: preferredScope()
        val scopeRecord = scopeRecord(scopeKind, request.context) ?: return BookmarkOperationResult.ScopeUnavailable(scopeKind)
        val scopeKey = scopeRecord.exactScopeKey()
        val matches = allBookmarks().filter {
            val livePosition = livePositionProvider?.invoke(it.id)
            val fileUrl = livePosition?.fileUrl ?: it.fileUrl
            val line = livePosition?.line ?: it.line
            fileUrl == request.fileUrl && line == request.line.coerceAtLeast(0) && it.exactScopeKey() == scopeKey
        }
        return when (matches.size) {
            0 -> create(request.copy(scopeKind = scopeKind))
            1 -> {
                val update = stateService.updateBookmarks { records -> records.removeAll { it.id == matches.single().id } }
                if (!update.accepted) return BookmarkOperationResult.ReadOnly
                notifyChanged()
                BookmarkOperationResult.Removed(matches)
            }
            else -> BookmarkOperationResult.AmbiguousToggle(matches)
        }
    }

    fun edit(id: String, description: String?, mnemonic: String?): BookmarkOperationResult {
        if (stateService.isReadOnlyForFutureSchema()) return BookmarkOperationResult.ReadOnly
        val normalized = MnemonicPolicy.normalize(mnemonic)
        if (mnemonic != null && normalized == null) return BookmarkOperationResult.MnemonicConflict(mnemonic, emptyList())
        var updated: BookmarkRecord? = null
        var conflict: List<BookmarkRecord> = emptyList()
        val stateUpdate = stateService.updateState { state ->
            val index = state.bookmarks.indexOfFirst { it.id == id }
            if (index < 0) return@updateState state
            val original = state.bookmarks[index]
            conflict = MnemonicPolicy.sameScopeConflicts(state.bookmarks, normalized, original.exactScopeKey(), id)
            if (conflict.isEmpty()) {
                val replacement = original.copy(description = description, mnemonic = normalized)
                state.bookmarks[index] = replacement
                updated = replacement
            }
            state
        }
        if (!stateUpdate.accepted) return BookmarkOperationResult.ReadOnly
        if (conflict.isNotEmpty()) return BookmarkOperationResult.MnemonicConflict(normalized!!, conflict)
        val updatedId = updated?.id ?: return BookmarkOperationResult.NotFound
        val persisted = stateUpdate.state.bookmarks.firstOrNull { it.id == updatedId } ?: return BookmarkOperationResult.NotFound
        notifyChanged()
        return BookmarkOperationResult.Updated(persisted.copy())
    }

    fun delete(ids: Collection<String>): BookmarkOperationResult {
        if (stateService.isReadOnlyForFutureSchema()) return BookmarkOperationResult.ReadOnly
        val removed = allBookmarks().filter { it.id in ids }
        if (removed.isEmpty()) return BookmarkOperationResult.NotFound
        val update = stateService.updateBookmarks { records -> records.removeAll { it.id in ids } }
        if (!update.accepted) return BookmarkOperationResult.ReadOnly
        notifyChanged()
        return BookmarkOperationResult.Removed(removed)
    }

    fun reassign(
        id: String,
        scopeKind: BookmarkScopeKind,
        creationContext: BookmarkCreationContext,
    ): BookmarkOperationResult {
        if (stateService.isReadOnlyForFutureSchema()) return BookmarkOperationResult.ReadOnly
        val scopeRecord = scopeRecord(scopeKind, creationContext) ?: return BookmarkOperationResult.ScopeUnavailable(scopeKind)
        var updated: BookmarkRecord? = null
        var duplicate: BookmarkRecord? = null
        var conflict: List<BookmarkRecord> = emptyList()
        var candidateMnemonic: String? = null
        val stateUpdate = stateService.updateState { state ->
            val index = state.bookmarks.indexOfFirst { it.id == id }
            if (index < 0) return@updateState state
            val original = state.bookmarks[index]
            val candidate = original.copy(
                scopeKind = scopeKind,
                repositoryRootUrl = scopeRecord.repositoryRootUrl,
                branchName = scopeRecord.branchName,
                changelistId = scopeRecord.changelistId,
                changelistName = scopeRecord.changelistName,
            )
            candidateMnemonic = candidate.mnemonic
            duplicate = duplicateLocation(state.bookmarks, candidate, id)
            if (duplicate == null) {
                conflict = MnemonicPolicy.sameScopeConflicts(
                    state.bookmarks,
                    candidate.mnemonic,
                    candidate.exactScopeKey(),
                    id,
                )
            }
            if (duplicate == null && conflict.isEmpty()) {
                state.bookmarks[index] = candidate
                updated = candidate
            }
            state
        }
        if (!stateUpdate.accepted) return BookmarkOperationResult.ReadOnly
        duplicate?.let { return BookmarkOperationResult.DuplicateLocation(it.copy()) }
        if (conflict.isNotEmpty()) return BookmarkOperationResult.MnemonicConflict(candidateMnemonic.orEmpty(), conflict)
        val result = updated ?: return BookmarkOperationResult.NotFound
        notifyChanged()
        return BookmarkOperationResult.Updated(result.copy())
    }

    fun updateLocation(
        updated: BookmarkRecord,
        useExistingLivePositions: Boolean = true,
        expectedLocation: BookmarkRecord? = null,
        existingLivePositions: Map<String, BookmarkLivePosition>? = null,
    ): BookmarkOperationResult {
        if (stateService.isReadOnlyForFutureSchema()) return BookmarkOperationResult.ReadOnly
        var merged: BookmarkRecord? = null
        var duplicate: BookmarkRecord? = null
        var stale: BookmarkRecord? = null
        val stateUpdate = stateService.updateState { state ->
            val index = state.bookmarks.indexOfFirst { it.id == updated.id }
            if (index >= 0) {
                val original = state.bookmarks[index]
                if (expectedLocation != null && !original.hasSameLocationAs(expectedLocation)) {
                    stale = original
                    return@updateState state
                }
                val candidate = original.mergeLocationFrom(updated)
                duplicate = duplicateLocation(
                    records = state.bookmarks,
                    candidate = candidate,
                    excludedId = candidate.id,
                    useCandidateLivePosition = false,
                    useExistingLivePositions = useExistingLivePositions,
                    existingLivePositions = existingLivePositions,
                )
                val replacement = if (duplicate == null) candidate else original.copy(
                    locationStatus = BookmarkLocationStatus.AMBIGUOUS,
                )
                state.bookmarks[index] = replacement
                merged = replacement
            }
            state
        }
        if (!stateUpdate.accepted) return BookmarkOperationResult.ReadOnly
        stale?.let { return BookmarkOperationResult.StaleLocation(it.copy()) }
        val result = merged ?: return BookmarkOperationResult.NotFound
        notifyChanged()
        duplicate?.let { return BookmarkOperationResult.DuplicateLocation(it.copy()) }
        return BookmarkOperationResult.Updated(result.copy())
    }

    fun relink(
        id: String,
        fileUrl: String,
        line: Int,
        column: Int,
        signature: LocationSignature,
    ): BookmarkOperationResult {
        if (stateService.isReadOnlyForFutureSchema()) return BookmarkOperationResult.ReadOnly
        var updated: BookmarkRecord? = null
        var duplicate: BookmarkRecord? = null
        val stateUpdate = stateService.updateState { state ->
            val index = state.bookmarks.indexOfFirst { it.id == id }
            if (index < 0) return@updateState state
            val candidate = state.bookmarks[index].copy(
                fileUrl = fileUrl,
                line = line.coerceAtLeast(0),
                column = column.coerceAtLeast(0),
                currentLineHash = signature.currentLineHash,
                previousLineHash = signature.previousLineHash,
                nextLineHash = signature.nextLineHash,
                locationStatus = BookmarkLocationStatus.AVAILABLE,
            )
            duplicate = duplicateLocation(
                records = state.bookmarks,
                candidate = candidate,
                excludedId = id,
                useCandidateLivePosition = false,
            )
            if (duplicate == null) {
                state.bookmarks[index] = candidate
                updated = candidate
            }
            state
        }
        if (!stateUpdate.accepted) return BookmarkOperationResult.ReadOnly
        duplicate?.let { return BookmarkOperationResult.DuplicateLocation(it.copy()) }
        val result = updated ?: return BookmarkOperationResult.NotFound
        notifyChanged()
        return BookmarkOperationResult.Updated(result.copy())
    }

    fun updateLocationStatus(
        id: String,
        status: BookmarkLocationStatus,
        expectedLocation: BookmarkRecord? = null,
    ): BookmarkOperationResult {
        if (stateService.isReadOnlyForFutureSchema()) return BookmarkOperationResult.ReadOnly
        var updated: BookmarkRecord? = null
        var stale: BookmarkRecord? = null
        var changed = false
        val stateUpdate = stateService.updateState { state ->
            val index = state.bookmarks.indexOfFirst { it.id == id }
            if (index >= 0) {
                val original = state.bookmarks[index]
                if (expectedLocation != null && !original.hasSameLocationAs(expectedLocation)) {
                    stale = original
                    return@updateState state
                }
                val replacement = if (original.locationStatus == status) original else original.copy(locationStatus = status)
                state.bookmarks[index] = replacement
                updated = replacement
                changed = replacement !== original
            }
            state
        }
        if (!stateUpdate.accepted) return BookmarkOperationResult.ReadOnly
        stale?.let { return BookmarkOperationResult.StaleLocation(it.copy()) }
        val result = updated ?: return BookmarkOperationResult.NotFound
        if (changed) notifyChanged()
        return BookmarkOperationResult.Updated(result.copy())
    }

    fun updateLocations(updatedRecords: Collection<BookmarkRecord>): Boolean {
        if (stateService.isReadOnlyForFutureSchema()) return false
        if (updatedRecords.isEmpty()) return true
        val byId = updatedRecords.associateBy { it.id }
        val stateUpdate = stateService.updateState { state ->
            val originals = state.bookmarks.associateBy { it.id }
            val proposed = state.bookmarks.associate { record ->
                record.id to (byId[record.id]?.let { updated -> record.mergeLocationFrom(updated) } ?: record)
            }
            val rejected = mutableSetOf<String>()
            while (true) {
                val finalRecords = state.bookmarks.map { original ->
                    if (original.id in rejected) original else proposed.getValue(original.id)
                }
                val newlyRejected = finalRecords
                    .groupBy(::exactLocationKey)
                    .values
                    .asSequence()
                    .filter { it.size > 1 }
                    .flatten()
                    .mapNotNull { record ->
                        val original = originals.getValue(record.id)
                        val candidate = proposed.getValue(record.id)
                        record.id.takeIf {
                            record.id in byId && record.id !in rejected &&
                                exactLocationKey(original) != exactLocationKey(candidate)
                        }
                    }
                    .toSet()
                if (newlyRejected.isEmpty()) break
                rejected += newlyRejected
            }
            state.bookmarks.replaceAll { original ->
                if (original.id in rejected) {
                    original.copy(locationStatus = BookmarkLocationStatus.AMBIGUOUS)
                } else {
                    proposed.getValue(original.id)
                }
            }
            state
        }
        if (!stateUpdate.accepted) return false
        notifyChanged()
        return true
    }

    fun handleBranchRename(rootUrl: String, oldName: String, newName: String): Boolean {
        if (stateService.isReadOnlyForFutureSchema()) return false
        if (oldName == newName) return true
        val stateUpdate = stateService.updateState { state ->
            val incoming = state.bookmarks.filter { record ->
                record.scopeKind == BookmarkScopeKind.BRANCH &&
                    record.repositoryRootUrl == rootUrl && record.branchName == oldName
            }
            val incomingIds = incoming.mapTo(hashSetOf()) { it.id }
            val accepted = state.bookmarks.filter { it.id !in incomingIds }.toMutableList()
            val replacements = incoming.associate { original ->
                val candidate = original.copy(branchName = newName)
                val locationConflict = duplicateLocation(accepted, candidate, original.id)
                val mnemonicConflict = MnemonicPolicy.sameScopeConflicts(
                    accepted,
                    candidate.mnemonic,
                    candidate.exactScopeKey(),
                    original.id,
                )
                val replacement = if (locationConflict != null || mnemonicConflict.isNotEmpty()) {
                    original.copy(locationStatus = BookmarkLocationStatus.AMBIGUOUS)
                } else {
                    candidate
                }
                accepted += replacement
                original.id to replacement
            }
            state.bookmarks.replaceAll { record -> replacements[record.id] ?: record }
            state
        }
        if (!stateUpdate.accepted) return false
        notifyChanged()
        return true
    }

    fun resolveMnemonic(mnemonic: String?, activeEditorRootUrl: String? = null): MnemonicResolution =
        MnemonicPolicy.resolveVisible(allBookmarks(), mnemonic, context, activeEditorRootUrl)

    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent, Disposable { listeners -= listener })
    }

    fun addLivePositionProvider(
        parent: Disposable,
        provider: (String) -> BookmarkLivePosition?,
    ) {
        livePositionProvider = provider
        Disposer.register(parent, Disposable {
            if (livePositionProvider === provider) livePositionProvider = null
        })
    }

    private fun scopeRecord(kind: BookmarkScopeKind, creationContext: BookmarkCreationContext): BookmarkRecord? = when (kind) {
        BookmarkScopeKind.GLOBAL -> BookmarkRecord(scopeKind = kind)
        BookmarkScopeKind.BRANCH -> creationContext.branch?.let {
            BookmarkRecord(scopeKind = kind, repositoryRootUrl = it.repositoryRootUrl, branchName = it.branchName)
        }
        BookmarkScopeKind.CHANGELIST -> (creationContext.changelist ?: context.activeChangelist)?.let {
            BookmarkRecord(scopeKind = kind, changelistId = it.id, changelistName = it.displayName)
        }
    }

    private fun refreshActiveChangelistName(active: ChangelistKey?) {
        if (active == null) return
        stateService.updateBookmarks { records ->
            records.replaceAll { record ->
                if (record.scopeKind == BookmarkScopeKind.CHANGELIST && record.changelistId == active.id &&
                    record.changelistName != active.displayName
                ) record.copy(changelistName = active.displayName) else record
            }
        }
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    private fun duplicateLocation(
        records: List<BookmarkRecord>,
        candidate: BookmarkRecord,
        excludedId: String? = null,
        useCandidateLivePosition: Boolean = true,
        useExistingLivePositions: Boolean = true,
        existingLivePositions: Map<String, BookmarkLivePosition>? = null,
    ): BookmarkRecord? {
        val candidatePosition = if (useCandidateLivePosition) livePositionProvider?.invoke(candidate.id) else null
        val candidateFileUrl = candidatePosition?.fileUrl ?: candidate.fileUrl
        val candidateLine = candidatePosition?.line ?: candidate.line
        return records.firstOrNull { record ->
            if (record.id == excludedId) return@firstOrNull false
            val livePosition = existingLivePositions?.get(record.id)
                ?: if (useExistingLivePositions) livePositionProvider?.invoke(record.id) else null
            val fileUrl = livePosition?.fileUrl ?: record.fileUrl
            val line = livePosition?.line ?: record.line
            fileUrl == candidateFileUrl && line == candidateLine &&
                record.exactScopeKey() == candidate.exactScopeKey()
        }
    }

    private fun exactLocationKey(record: BookmarkRecord): ExactLocationKey = ExactLocationKey(
        scopeKey = record.exactScopeKey(),
        fileUrl = record.fileUrl,
        line = record.line,
    )

    private fun BookmarkRecord.mergeLocationFrom(updated: BookmarkRecord): BookmarkRecord = copy(
        fileUrl = updated.fileUrl,
        line = updated.line,
        column = updated.column,
        currentLineHash = updated.currentLineHash,
        previousLineHash = updated.previousLineHash,
        nextLineHash = updated.nextLineHash,
        locationStatus = updated.locationStatus,
    )

    private fun BookmarkRecord.hasSameLocationAs(other: BookmarkRecord): Boolean =
        fileUrl == other.fileUrl && line == other.line && column == other.column &&
            currentLineHash == other.currentLineHash && previousLineHash == other.previousLineHash &&
            nextLineHash == other.nextLineHash && locationStatus == other.locationStatus
}

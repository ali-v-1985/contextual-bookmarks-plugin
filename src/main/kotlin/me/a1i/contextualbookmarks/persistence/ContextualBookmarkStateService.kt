package me.a1i.contextualbookmarks.persistence

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.util.xmlb.XmlSerializer
import me.a1i.contextualbookmarks.model.BookmarkRecord
import me.a1i.contextualbookmarks.model.CURRENT_SCHEMA_VERSION
import me.a1i.contextualbookmarks.model.ContextualBookmarkState
import me.a1i.contextualbookmarks.model.MnemonicPolicy
import org.jdom.Element

data class BookmarkStateUpdateResult(
    val state: ContextualBookmarkState,
    val accepted: Boolean,
)

@Service(Service.Level.PROJECT)
@State(
    name = "ContextualBookmarkState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED)],
)
class ContextualBookmarkStateService : PersistentStateComponent<Element> {
    private val lock = Any()

    @Volatile
    private var current = ContextualBookmarkState()

    private var futureSchemaXml: Element? = null

    override fun getState(): Element = synchronized(lock) {
        futureSchemaXml?.clone() ?: XmlSerializer.serialize(current.deepCopy())
    }

    override fun loadState(state: Element) {
        synchronized(lock) {
            val rawSchemaVersion = state.getAttributeValue("schemaVersion")
                ?: state.getChildren("option")
                    .firstOrNull { it.getAttributeValue("name") == "schemaVersion" }
                    ?.getAttributeValue("value")
            val schemaVersion = rawSchemaVersion?.toIntOrNull()
            if (rawSchemaVersion != null && (schemaVersion == null || schemaVersion > CURRENT_SCHEMA_VERSION)) {
                futureSchemaXml = state.clone()
                current = ContextualBookmarkState(
                    schemaVersion = schemaVersion ?: (CURRENT_SCHEMA_VERSION + 1),
                )
                return@synchronized
            }
            val deserialized = XmlSerializer.deserialize(state, ContextualBookmarkState::class.java)
            futureSchemaXml = null
            current = migrate(deserialized.deepCopy()).sanitize()
        }
    }

    fun snapshot(): ContextualBookmarkState = synchronized(lock) { current.deepCopy() }

    internal fun isReadOnlyForFutureSchema(): Boolean = synchronized(lock) { futureSchemaXml != null }

    fun updateState(transform: (ContextualBookmarkState) -> ContextualBookmarkState): BookmarkStateUpdateResult =
        synchronized(lock) {
            if (futureSchemaXml != null) {
                return@synchronized BookmarkStateUpdateResult(current.deepCopy(), accepted = false)
            }
            current = transform(current.deepCopy()).sanitize()
            BookmarkStateUpdateResult(current.deepCopy(), accepted = true)
        }

    fun updateBookmarks(transform: (MutableList<BookmarkRecord>) -> Unit): BookmarkStateUpdateResult =
        updateState { state -> state.apply { transform(bookmarks) } }

    private fun migrate(state: ContextualBookmarkState): ContextualBookmarkState {
        // Schema 1 is the initial format. Future migrations must be appended and
        // applied in order without dropping records from missing contexts.
        if (state.schemaVersion <= 0) state.schemaVersion = CURRENT_SCHEMA_VERSION
        return state
    }

    private fun ContextualBookmarkState.sanitize(): ContextualBookmarkState {
        if (schemaVersion <= CURRENT_SCHEMA_VERSION) schemaVersion = CURRENT_SCHEMA_VERSION
        nextOrder = nextOrder.coerceAtLeast((bookmarks.maxOfOrNull { it.order } ?: 0L) + 1L)
        bookmarks = bookmarks.map { record ->
            record.copy(
                line = record.line.coerceAtLeast(0),
                column = record.column.coerceAtLeast(0),
                mnemonic = MnemonicPolicy.normalize(record.mnemonic),
                description = record.description?.trim()?.takeIf(String::isNotEmpty),
            )
        }.toMutableList()
        return this
    }
}

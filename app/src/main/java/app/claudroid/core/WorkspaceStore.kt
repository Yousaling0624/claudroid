package app.claudroid.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One switchable working directory. The default workspace keeps the legacy id. */
@Serializable
data class WorkspaceInfo(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
internal data class WorkspaceIndex(
    val activeId: String = WorkspaceStore.DEFAULT_ID,
    val spaces: List<WorkspaceInfo> = emptyList(),
)

/**
 * Names the directory the jail binds to `/work`.
 *
 * The original single workspace (`filesDir/workspace`) stays the default so every
 * existing install keeps its files. Extra workspaces live under
 * `filesDir/workspaces/<id>` and are created from the UI; the guest always sees
 * them as `/work`, which keeps Claude Code's per-project CLI transcripts valid
 * no matter which directory is mounted.
 *
 * Active selection and the metadata list live in `workspaces.json` next to the
 * extra directories. Nothing here is user-provided except the display name, so
 * ids are generated and validated before ever becoming a path.
 */
class WorkspaceStore(
    private val defaultDir: File,
    private val rootDir: File,
) {
    constructor(context: Context) : this(Paths.workspace(context), Paths.workspacesRoot(context))

    data class State(
        val activeId: String = DEFAULT_ID,
        /** Custom workspaces only; the default is prepended by [all]. */
        val spaces: List<WorkspaceInfo> = emptyList(),
    ) {
        fun all(): List<WorkspaceInfo> = listOf(DEFAULT) + spaces

        fun active(): WorkspaceInfo = all().firstOrNull { it.id == activeId } ?: DEFAULT
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val lock = Any()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val indexFile: File get() = File(rootDir, INDEX_NAME)

    init {
        load()
    }

    fun load() {
        synchronized(lock) {
            val index = runCatching {
                indexFile.takeIf { it.isFile }
                    ?.readText()
                    ?.let { json.decodeFromString(WorkspaceIndex.serializer(), it) }
            }.getOrNull() ?: WorkspaceIndex()
            // A hand-edited or half-written index must not produce paths outside rootDir.
            val spaces = index.spaces
                .filter { isValidId(it.id) && it.id != DEFAULT_ID }
                .distinctBy { it.id }
            val active = index.activeId
                .takeIf { it == DEFAULT_ID || spaces.any { space -> space.id == it } }
                ?: DEFAULT_ID
            _state.value = State(active, spaces)
        }
    }

    fun all(): List<WorkspaceInfo> = _state.value.all()

    fun activeId(): String = _state.value.activeId

    fun active(): WorkspaceInfo = _state.value.active()

    /** Directory behind [id]; unknown ids fall back to the default workspace. */
    fun dir(id: String?): File {
        val custom = _state.value.spaces.firstOrNull { it.id == id }
        val target = custom?.let { File(rootDir, it.id) } ?: defaultDir
        target.mkdirs()
        return target
    }

    fun activeDir(): File = dir(activeId())

    /** Creates the directory and its metadata entry; does not switch to it. */
    fun create(rawName: String): WorkspaceInfo {
        val id = "ws-${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(6)}"
        val info = WorkspaceInfo(id = id, name = normalizeName(rawName))
        File(rootDir, id).mkdirs()
        synchronized(lock) {
            _state.value = _state.value.copy(spaces = _state.value.spaces + info)
            persistLocked()
        }
        return info
    }

    fun rename(id: String, rawName: String): Boolean = synchronized(lock) {
        if (id == DEFAULT_ID) return false
        val current = _state.value.spaces.firstOrNull { it.id == id } ?: return false
        val clean = normalizeName(rawName)
        if (clean == current.name) return true
        _state.value = _state.value.copy(
            spaces = _state.value.spaces.map { if (it.id == id) it.copy(name = clean) else it },
        )
        persistLocked()
        true
    }

    /** Deletes the extra workspace and everything inside it; the default is refused. */
    fun remove(id: String): Boolean = synchronized(lock) {
        if (id == DEFAULT_ID) return false
        val current = _state.value.spaces.firstOrNull { it.id == id } ?: return false
        if (!isValidId(current.id)) return false
        File(rootDir, current.id).deleteRecursively()
        _state.value = _state.value.copy(
            activeId = if (_state.value.activeId == id) DEFAULT_ID else _state.value.activeId,
            spaces = _state.value.spaces.filterNot { it.id == id },
        )
        persistLocked()
        true
    }

    fun select(id: String): Boolean = synchronized(lock) {
        val exists = id == DEFAULT_ID || _state.value.spaces.any { it.id == id }
        if (!exists) return false
        _state.value = _state.value.copy(activeId = id)
        persistLocked()
        true
    }

    /** Forgets every entry without touching the default workspace's files. */
    fun reset() {
        synchronized(lock) {
            _state.value = State()
            runCatching { indexFile.delete() }
        }
    }

    /**
     * Adds custom workspaces restored from a backup without touching what already
     * exists (docs/BACKUP.md §3): existing ids keep the local name, [activeId] never
     * changes, and nothing is removed. Returns how many entries were added.
     */
    fun mergeRestored(spaces: List<WorkspaceInfo>): Int = synchronized(lock) {
        val known = _state.value.spaces.mapTo(mutableSetOf()) { it.id }
        val additions = spaces
            .asSequence()
            .filter { isValidId(it.id) && it.id != DEFAULT_ID && it.id !in known }
            .distinctBy { it.id }
            .map { it.copy(name = normalizeName(it.name)) }
            .toList()
        if (additions.isEmpty()) return 0
        additions.forEach { File(rootDir, it.id).mkdirs() }
        _state.value = _state.value.copy(spaces = _state.value.spaces + additions)
        persistLocked()
        additions.size
    }

    /** Used by the engine's "delete everything" uninstall. */
    fun deleteAll() {
        synchronized(lock) {
            _state.value.spaces.forEach { space ->
                if (isValidId(space.id)) runCatching { File(rootDir, space.id).deleteRecursively() }
            }
            _state.value = State()
            runCatching { defaultDir.deleteRecursively() }
            runCatching { indexFile.delete() }
        }
    }

    fun storageBytes(): Long = all().sumOf { info ->
        runCatching { dirSize(dir(info.id)) }.getOrDefault(0L)
    }

    private fun persistLocked() {
        runCatching {
            rootDir.mkdirs()
            val index = WorkspaceIndex(_state.value.activeId, _state.value.spaces)
            val tmp = File(rootDir, "$INDEX_NAME.tmp")
            tmp.writeText(json.encodeToString(WorkspaceIndex.serializer(), index))
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
        }
    }

    private fun dirSize(dir: File): Long = dir.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "默认工作区"

        val DEFAULT = WorkspaceInfo(DEFAULT_ID, DEFAULT_NAME, 0)

        private const val INDEX_NAME = "workspaces.json"
        private const val MAX_NAME = 24

        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        /** Display names are free text, but stay one short line. */
        fun normalizeName(raw: String): String {
            val cleaned = raw
                .filterNot { it.isISOControl() }
                .trim()
            if (cleaned.isEmpty()) return DEFAULT_NAME
            return cleaned.take(MAX_NAME)
        }
    }
}

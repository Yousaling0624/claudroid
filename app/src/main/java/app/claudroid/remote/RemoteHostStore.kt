package app.claudroid.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists paired hosts as one small JSON file. Tokens are already sealed by the
 * caller; this store never sees or logs plaintext. A corrupt file falls back to an
 * empty list instead of locking the user out of the app.
 */
class RemoteHostStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _state = MutableStateFlow(RemoteHostsFile())
    val state: StateFlow<RemoteHostsFile> = _state.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var loaded = false

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                file.takeIf { it.isFile }
                    ?.readText()
                    ?.let { json.decodeFromString(RemoteHostsFile.serializer(), it) }
            }.getOrNull()
        } ?: RemoteHostsFile()
        val hosts = parsed.hosts
            .filter { it.id.isNotBlank() && it.baseUrl.isNotBlank() }
            .distinctBy { it.id }
        val defaultId = parsed.defaultHostId.takeIf { id -> hosts.any { it.id == id } } ?: ""
        _state.value = RemoteHostsFile(hosts = hosts, defaultHostId = defaultId)
        loaded = true
    }

    fun find(id: String): RemoteHostProfile? = _state.value.hosts.firstOrNull { it.id == id }

    suspend fun upsert(profile: RemoteHostProfile) = mutate { current ->
        val rest = current.hosts.filterNot { it.id == profile.id }
        current.copy(hosts = rest + profile)
    }

    suspend fun update(id: String, transform: (RemoteHostProfile) -> RemoteHostProfile) = mutate { current ->
        current.copy(hosts = current.hosts.map { if (it.id == id) transform(it) else it })
    }

    suspend fun remove(id: String) = mutate { current ->
        val hosts = current.hosts.filterNot { it.id == id }
        current.copy(
            hosts = hosts,
            defaultHostId = current.defaultHostId.takeIf { it != id && hosts.any { h -> h.id == it } }
                ?: hosts.firstOrNull()?.id.orEmpty(),
        )
    }

    suspend fun setDefault(id: String?) = mutate { current ->
        current.copy(defaultHostId = id?.takeIf { candidate -> current.hosts.any { it.id == candidate } }.orEmpty())
    }

    private suspend fun mutate(block: (RemoteHostsFile) -> RemoteHostsFile) = mutex.withLock {
        loadLocked()
        val next = block(_state.value)
        withContext(Dispatchers.IO) { write(next) }
        _state.value = next
    }

    private fun write(value: RemoteHostsFile) {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        val tmp = File(parent, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(RemoteHostsFile.serializer(), value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

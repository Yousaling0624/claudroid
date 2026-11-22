package app.claudroid.data

import android.content.Context
import app.claudroid.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Transcripts live as one JSON file per session. A chat app of this size does not
 * need a database, and a plain file keeps backup / export trivial.
 */
class SessionStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    /**
     * Sessions created from the UI but not written yet. They carry the chosen remote
     * host (`hostId`/`remoteCwd`) until the first turn persists them, without showing
     * empty conversations in the drawer.
     */
    private val drafts = mutableMapOf<String, ChatSession>()

    private val dir: File get() = Paths.sessions(context)

    suspend fun load() = withContext(Dispatchers.IO) {
        val list = dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { json.decodeFromString(ChatSession.serializer(), f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
        _sessions.value = list
    }

    fun find(id: String): ChatSession? =
        synchronized(drafts) { drafts[id] } ?: _sessions.value.firstOrNull { it.id == id }

    suspend fun upsert(session: ChatSession) = withContext(Dispatchers.IO) {
        synchronized(drafts) { drafts.remove(session.id) }
        val stamped = session.copy(updatedAt = System.currentTimeMillis())
        val rest = _sessions.value.filterNot { it.id == stamped.id }
        _sessions.value = (listOf(stamped) + rest).sortedByDescending { it.updatedAt }
        val file = File(dir, "${stamped.id}.json")
        val tmp = File(dir, "${stamped.id}.json.tmp")
        tmp.writeText(json.encodeToString(ChatSession.serializer(), stamped))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(drafts) { drafts.remove(id) }
        _sessions.value = _sessions.value.filterNot { it.id == id }
        File(dir, "$id.json").delete()
    }

    suspend fun rename(id: String, title: String) {
        find(id)?.let { upsert(it.copy(title = title)) }
    }

    /**
     * Empty sessions are not written to disk; [drafts] keeps their host binding until
     * `ChatController.persist()` runs on the first turn.
     */
    fun newSession(model: String?, hostId: String? = null, remoteCwd: String? = null): ChatSession {
        val session = ChatSession(model = model, hostId = hostId, remoteCwd = remoteCwd)
        synchronized(drafts) { drafts[session.id] = session }
        return session
    }
}

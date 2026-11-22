package app.claudroid.remote

import android.os.Build
import app.claudroid.engine.PermissionDecision
import app.claudroid.engine.TurnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns paired hosts, their per-host SSE connection and the one remote turn.
 *
 * A stream is opened lazily when a turn starts and kept only while that host has an
 * active turn. If it drops, the manager reconnects with exponential backoff from the
 * last seen event sequence; the daemon replays the gap. Commands themselves are plain
 * POSTs, so a reconnect never repeats a turn.
 */
class RemoteHostManager(
    stateFile: File,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val cipher: SecretCipher = AndroidSecretBox(),
) {

    private val store = RemoteHostStore(stateFile)
    private val _state = MutableStateFlow(RemoteHostsState())
    val state: StateFlow<RemoteHostsState> = _state.asStateFlow()

    private val lock = Any()
    private val runtime = mutableMapOf<String, RemoteRuntime>()
    private val activeTurns = mutableMapOf<String, Int>()

    private val streamLock = Any()
    private val streams = mutableMapOf<String, RemoteClient.EventStream>()
    private val streamJobs = mutableMapOf<String, Job>()
    private val pollJobs = mutableMapOf<String, Job>()

    /** Hosts where SSE never made progress (quick tunnels buffer it); poll from now on. */
    private val pollEnabled = ConcurrentHashMap.newKeySet<String>()
    private val lastSeq = ConcurrentHashMap<String, Long>()
    private val seqLock = Any()

    private val collectors = CopyOnWriteArrayList<TurnCollector>()
    private val activeTurnIds = ConcurrentHashMap<String, String>() // hostId -> running turnId
    private val turnHosts = ConcurrentHashMap<String, String>() // turnId -> hostId
    private val permissionTurns = ConcurrentHashMap<String, String>() // requestId -> turnId

    init {
        scope.launch { store.state.collect { publish() } }
        scope.launch { runCatching { load() } }
    }

    suspend fun load() {
        store.load()
        publish()
    }

    val hosts: List<RemoteHostProfile> get() = state.value.hosts

    suspend fun host(id: String): RemoteHostProfile? {
        load()
        return store.find(id)
    }

    private suspend fun requireHost(id: String): RemoteHostProfile =
        host(id) ?: throw RemoteException("主机不存在，请重新配对")

    suspend fun setDefault(id: String?) {
        store.setDefault(id)
        publish()
    }

    /** Pays the pairing code once, seals the returned token and remembers the host. */
    suspend fun pair(baseUrl: String, code: String, name: String): RemoteHostProfile = withContext(Dispatchers.IO) {
        load()
        val cleanBase = RemoteClient.normalizeBaseUrl(baseUrl)
        val cleanCode = code.trim()
        if (cleanCode.length != 8) throw RemoteException("配对码应为 8 位")
        val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .joinToString(" ")
            .ifBlank { "Android" }
        val response = RemoteClient.pair(cleanBase, cleanCode, deviceName)
        val now = System.currentTimeMillis()
        val existing = store.state.value.hosts.firstOrNull { it.baseUrl == cleanBase }
        val label = name.trim().ifBlank {
            existing?.name ?: response.host.hostName.ifBlank { cleanBase }
        }.take(40)
        val profile = RemoteHostProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = label,
            baseUrl = cleanBase,
            tokenSealed = cipher.seal(response.token),
            accessClientId = existing?.accessClientId.orEmpty(),
            accessSecretSealed = existing?.accessSecretSealed.orEmpty(),
            hostName = response.host.hostName,
            platform = response.host.platform,
            protocol = response.host.protocol,
            createdAt = existing?.createdAt ?: now,
            lastSeenAt = now,
            defaultCwd = response.host.defaultCwd,
            workspaceRoots = response.host.workspaceRoots,
            tunnel = response.host.tunnel,
            // Re-pairing the same URL must not reset the host's own mode. A new host
            // starts in Auto: the daemon's classifier reviews sensitive calls, so the
            // phone is not asked to confirm every tool invocation.
            permissionMode = existing?.permissionMode ?: RemotePermissionModes.AUTO,
            allowBypassPermissions = response.host.allowBypassPermissions,
        )
        store.upsert(profile)
        if (store.state.value.defaultHostId.isBlank()) store.setDefault(profile.id)
        setRuntime(profile.id, RemoteRuntime(RemotePhase.ONLINE, null, response.host))
        publish()
        profile
    }

    suspend fun updateHost(
        id: String,
        name: String?,
        baseUrl: String?,
        accessClientId: String?,
        accessSecret: String?,
        permissionMode: String? = null,
    ): RemoteHostProfile = withContext(Dispatchers.IO) {
        load()
        val current = store.find(id) ?: throw RemoteException("主机不存在")
        val cleanMode = permissionMode?.let {
            it.trim().takeIf { mode -> mode in RemotePermissionModes.ALLOWED }
                ?: throw RemoteException("不支持的权限模式")
        }
        val cleanBase = baseUrl?.takeIf { it.isNotBlank() }
            ?.let { RemoteClient.normalizeBaseUrl(it) }
            ?: current.baseUrl
        val sealedSecret = when {
            accessSecret == null -> current.accessSecretSealed
            accessSecret.isBlank() -> ""
            else -> cipher.seal(accessSecret)
        }
        val updated = current.copy(
            name = name?.trim()?.takeIf { it.isNotBlank() }?.take(40) ?: current.name,
            baseUrl = cleanBase,
            accessClientId = accessClientId?.trim() ?: current.accessClientId,
            accessSecretSealed = sealedSecret,
            permissionMode = cleanMode ?: current.permissionMode,
        )
        store.upsert(updated)
        updated
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        load()
        val profile = store.find(id)
        val turnId = activeTurnIds[id]
        if (profile != null && turnId != null) {
            runCatching { clientFor(profile).cancelTurn(turnId) }
        }
        failCollectors(id, "主机已移除，本轮已中断。")
        synchronized(streamLock) {
            streams.remove(id)?.close()
            streamJobs.remove(id)?.cancel()
            pollJobs.remove(id)?.cancel()
        }
        pollEnabled.remove(id)
        synchronized(seqLock) { lastSeq.remove(id) }
        store.remove(id)
        synchronized(lock) {
            runtime.remove(id)
            activeTurns.remove(id)
        }
        publish()
    }

    suspend fun test(id: String): RemoteHealth = withContext(Dispatchers.IO) {
        load()
        val profile = requireHost(id)
        setRuntime(id, RemoteRuntime(RemotePhase.CONNECTING, null, null))
        try {
            val health = clientFor(profile).health()
            rememberHealth(profile, health)
            health
        } catch (t: Throwable) {
            setRuntime(id, RemoteRuntime(RemotePhase.ERROR, RemoteClient.friendlyNetworkError(t), null))
            throw t
        }
    }

    suspend fun tunnel(id: String, action: String? = null): RemoteTunnelStatus = withContext(Dispatchers.IO) {
        load()
        val profile = requireHost(id)
        val client = clientFor(profile)
        val status = if (action == null) client.tunnel() else client.tunnelAction(action)
        store.update(id) { it.copy(tunnel = status) }
        updateRuntimeTunnel(id, status)
        status
    }

    suspend fun listFiles(id: String, path: String): List<RemoteFileEntry> = withContext(Dispatchers.IO) {
        load()
        clientFor(requireHost(id)).listFiles(path)
    }

    suspend fun download(id: String, remotePath: String): File = withContext(Dispatchers.IO) {
        load()
        val profile = requireHost(id)
        val name = remotePath.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "remote-file"
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_").take(80)
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val target = File(dir, "remote-${System.currentTimeMillis()}-$safe")
        clientFor(profile).download(remotePath, target)
        target
    }

    suspend fun upload(id: String, remotePath: String, source: File) = withContext(Dispatchers.IO) {
        load()
        clientFor(requireHost(id)).upload(remotePath, source)
    }

    /**
     * Runs one turn on [hostId] and returns the daemon's exit code. [onEvent] receives
     * turn-scoped events only; host/tunnel broadcasts stay in the manager.
     */
    suspend fun runTurn(
        hostId: String,
        cwd: String,
        request: TurnRequest,
        isCancelled: () -> Boolean,
        onEvent: (RemoteEvent) -> Unit,
        onFailed: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        load()
        val profile = requireHost(hostId)
        val client = clientFor(profile)
        val health = try {
            client.health()
        } catch (t: Throwable) {
            setRuntime(hostId, RemoteRuntime(RemotePhase.ERROR, RemoteClient.friendlyNetworkError(t), null))
            throw t
        }
        if (health.protocol != 0 && health.protocol != PROTOCOL_VERSION) {
            throw RemoteException("主机协议版本不兼容（主机 v${health.protocol}，App v$PROTOCOL_VERSION）")
        }
        if (request.permissionMode == RemotePermissionModes.BYPASS && !health.allowBypassPermissions) {
            throw RemoteException(
                "主机没有允许「完全放行」模式，请在设置 → 远程主机 → 这台主机 → 执行里改用其他权限模式，" +
                    "或在主机配置中开启 allowBypassPermissions。",
            )
        }
        rememberHealth(profile, health)

        if (isCancelled()) return@withContext -2
        val collector = TurnCollector(hostId, onEvent, onFailed)
        collectors.add(collector)
        addActive(hostId)
        ensureStream(hostId)
        if (hostId in pollEnabled) ensurePolling(hostId)
        try {
            val snapshot = client.startTurn(
                RemoteTurnBody(
                    cwd = cwd,
                    prompt = request.prompt,
                    resumeSessionId = request.resumeSessionId,
                    permissionMode = request.permissionMode,
                    model = request.model,
                )
            )
            collector.assign(snapshot.turnId)
            turnHosts[snapshot.turnId] = hostId
            activeTurnIds[hostId] = snapshot.turnId
            if (health.eventPoll) watchSseProgress(hostId, collector)
            watchTurnStart(hostId, collector)
            if (isCancelled()) {
                runCatching { client.cancelTurn(snapshot.turnId) }
                return@withContext -2
            }
            val code = withTimeoutOrNull(TURN_TIMEOUT_MS) { collector.done.await() }
            if (code == null) {
                onFailed("等待主机响应超时，已请求停止本轮。")
                runCatching { client.cancelTurn(snapshot.turnId) }
                -1
            } else {
                code
            }
        } catch (e: RemoteException) {
            onFailed(e.message ?: "无法在主机上启动任务。")
            -1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFailed(RemoteClient.friendlyNetworkError(e))
            -1
        } finally {
            collectors.remove(collector)
            val turnId = collector.turnId
            if (turnId != null) {
                activeTurnIds.remove(hostId, turnId)
                turnHosts.remove(turnId)
                purgeTurnPermissions(turnId)
            }
            decActive(hostId)
            if (!hasActive(hostId)) stopStream(hostId)
        }
    }

    /** Best-effort stop used by [RemoteTurnBackend.cancel]; idempotent on the daemon. */
    fun cancelCurrentTurn(hostId: String) {
        val turnId = activeTurnIds[hostId] ?: return
        val profile = store.find(hostId) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { clientFor(profile).cancelTurn(turnId) }
        }
    }

    suspend fun respondPermission(requestId: String, decision: PermissionDecision) = withContext(Dispatchers.IO) {
        load()
        val turnId = permissionTurns.remove(requestId)
            ?: throw RemoteException("确认请求已过期或已处理")
        val hostId = turnHosts[turnId] ?: throw RemoteException("任务已结束")
        val profile = store.find(hostId) ?: throw RemoteException("主机不存在")
        clientFor(profile).respondPermission(
            turnId,
            RemotePermissionBody(
                requestId = requestId,
                behavior = if (decision.allow) "allow" else "deny",
                message = decision.message,
                always = if (decision.allow) decision.always else null,
            ),
        )
    }

    // ------------------------------------------------------------------ streams

    private fun ensureStream(hostId: String) {
        synchronized(streamLock) {
            if (streamJobs[hostId]?.isActive == true) return
            val job = scope.launch(Dispatchers.IO) { streamLoop(hostId) }
            streamJobs[hostId] = job
            job.invokeOnCompletion {
                synchronized(streamLock) {
                    if (streamJobs[hostId] === job) streamJobs.remove(hostId)
                }
            }
        }
    }

    private suspend fun streamLoop(hostId: String) {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive && hasActive(hostId)) {
            val profile = host(hostId) ?: break
            var stream: RemoteClient.EventStream? = null
            try {
                val client = clientFor(profile)
                val health = client.health()
                rememberHealth(profile, health)
                val since = lastSeq[hostId] ?: 0
                stream = client.openEvents(since)
                if (stream.latestSeq > 0 && stream.latestSeq < since) {
                    // The daemon restarted; its sequence numbers began again, so the
                    // stored high-water mark would filter every future event.
                    resetSeq(hostId)
                    stream.close()
                    continue
                }
                synchronized(streamLock) { streams[hostId] = stream }
                backoff = 1_000L
                stream.read { event -> dispatch(hostId, event) }
                if (stream.isClosed.not()) {
                    setRuntime(
                        hostId,
                        RemoteRuntime(RemotePhase.ERROR, "与主机的连接已断开，正在重连…", null),
                    )
                }
            } catch (e: RemoteException) {
                if (e.status == 401) {
                    setRuntime(hostId, RemoteRuntime(RemotePhase.ERROR, "配对已失效，请重新配对", null))
                    failCollectors(hostId, "主机配对已失效，请在「设置 → 远程主机」重新配对。")
                    break
                }
                if (stream?.isClosed != true) {
                    setRuntime(hostId, RemoteRuntime(RemotePhase.ERROR, RemoteClient.friendlyNetworkError(e), null))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (stream?.isClosed != true) {
                    setRuntime(hostId, RemoteRuntime(RemotePhase.ERROR, RemoteClient.friendlyNetworkError(e), null))
                }
            } finally {
                synchronized(streamLock) {
                    if (streams[hostId] === stream) streams.remove(hostId)
                }
                stream?.close()
            }
            if (!hasActive(hostId)) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private fun stopStream(hostId: String) {
        synchronized(streamLock) {
            streams.remove(hostId)?.close()
            streamJobs.remove(hostId)?.cancel()
        }
    }

    // ------------------------------------------------------- polling fallback

    /**
     * Cloudflare Quick Tunnels answer the SSE request with 200 and then buffer the
     * whole body, so a turn would sit at "准备中…" until the 30-minute cap. Once a
     * stream shows no progress, this host switches to the bounded JSON batch endpoint,
     * which traverses the tunnel normally. SSE keeps running; sequence numbers dedupe
     * the overlap if it ever starts delivering.
     */
    private fun activatePolling(hostId: String) {
        pollEnabled.add(hostId)
        ensurePolling(hostId)
    }

    private fun ensurePolling(hostId: String) {
        synchronized(streamLock) {
            if (pollJobs[hostId]?.isActive == true) return
            val job = scope.launch(Dispatchers.IO) { pollLoop(hostId) }
            pollJobs[hostId] = job
            job.invokeOnCompletion {
                synchronized(streamLock) {
                    if (pollJobs[hostId] === job) pollJobs.remove(hostId)
                }
            }
        }
    }

    private suspend fun pollLoop(hostId: String) {
        while (currentCoroutineContext().isActive && hasActive(hostId)) {
            val profile = host(hostId) ?: break
            try {
                val since = lastSeq[hostId] ?: 0
                val batch = clientFor(profile).eventsSince(since)
                if (batch.latestSeq > 0 && batch.latestSeq < since) {
                    // Daemon restarted while the app kept its high-water mark.
                    resetSeq(hostId)
                    continue
                }
                batch.events.forEach { dispatch(hostId, it) }
                if (batch.events.isEmpty()) delay(POLL_IDLE_DELAY_MS)
            } catch (e: RemoteException) {
                if (e.status == 401) {
                    setRuntime(hostId, RemoteRuntime(RemotePhase.ERROR, "配对已失效，请重新配对", null))
                    failCollectors(hostId, "主机配对已失效，请在「设置 → 远程主机」重新配对。")
                    break
                }
                if (e.status == 404) {
                    // Daemon predates the batch endpoint; stay on SSE only.
                    pollEnabled.remove(hostId)
                    break
                }
                delay(POLL_RETRY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                delay(POLL_RETRY_MS)
            }
        }
    }

    /**
     * The daemon publishes `turn started` before answering the POST, so a working SSE
     * delivers something within a few seconds. A later silence triggers the fallback
     * too: long tool runs are quiet, but polling then is harmless.
     */
    private fun watchSseProgress(hostId: String, collector: TurnCollector) {
        scope.launch {
            delay(SSE_FIRST_EVENT_GRACE_MS)
            if (!collectors.contains(collector)) return@launch
            if (!collector.sawEvent()) {
                activatePolling(hostId)
                return@launch
            }
            while (collectors.contains(collector)) {
                delay(SSE_STALL_CHECK_MS)
                if (!collectors.contains(collector)) break
                if (collector.millisSinceLastEvent() > SSE_STALL_MS) {
                    activatePolling(hostId)
                    break
                }
            }
        }
    }

    /**
     * The daemon publishes `turn started` before answering the POST, so a healthy
     * transport always reacts quickly. A host still on an old daemon (no polling) over
     * a Quick Tunnel would otherwise spin for the full 30-minute cap: fail with an
     * actionable message and stop the orphaned turn on the host.
     */
    private fun watchTurnStart(hostId: String, collector: TurnCollector) {
        scope.launch {
            delay(NO_EVENT_FAIL_MS)
            if (!collectors.contains(collector) || collector.sawEvent()) return@launch
            val turnId = activeTurnIds[hostId]
            val profile = host(hostId)
            if (turnId != null && profile != null) {
                runCatching { clientFor(profile).cancelTurn(turnId) }
            }
            collector.fail(
                "主机没有返回任何事件。请把主机上的 claudroid-bridge 升级到最新版" +
                    "（Quick Tunnel 需要事件轮询），并检查隧道/网络后重试。",
            )
        }
    }

    private fun resetSeq(hostId: String) = synchronized(seqLock) {
        lastSeq.remove(hostId)
    }

    private fun dispatch(hostId: String, event: RemoteEvent) {
        // SSE and the polling fallback can overlap. Delivering under the sequence lock
        // keeps order and drops the duplicate the other transport already applied.
        synchronized(seqLock) {
            if (event.seq > 0) {
                val previous = lastSeq[hostId] ?: 0
                if (event.seq <= previous) return
                lastSeq[hostId] = event.seq
            }
            deliver(hostId, event)
        }
    }

    private fun deliver(hostId: String, event: RemoteEvent) {
        when (event) {
            is RemoteEvent.Cli, is RemoteEvent.Turn -> {
                collectors.forEach { it.accept(event) }
                if (event is RemoteEvent.Turn && event.phase != "started") {
                    purgeTurnPermissions(event.turnId)
                }
            }
            is RemoteEvent.Permission -> {
                permissionTurns[event.requestId] = event.turnId
                collectors.forEach { it.accept(event) }
            }
            is RemoteEvent.Host -> {
                setRuntime(hostId, RemoteRuntime(RemotePhase.ONLINE, null, event.host))
                scope.launch(Dispatchers.IO) {
                    store.update(hostId) { applyHealth(it, event.host) }
                    publish()
                }
            }
            is RemoteEvent.Tunnel -> {
                updateRuntimeTunnel(hostId, event.tunnel)
                scope.launch(Dispatchers.IO) {
                    store.update(hostId) { it.copy(tunnel = event.tunnel) }
                    publish()
                }
            }
        }
    }

    private fun failCollectors(hostId: String, message: String) {
        collectors.filter { it.hostId == hostId }.forEach { it.fail(message) }
    }

    private fun purgeTurnPermissions(turnId: String) {
        permissionTurns.keys.filter { permissionTurns[it] == turnId }.forEach { permissionTurns.remove(it) }
    }

    // ------------------------------------------------------------- runtime/state

    private fun publish() = synchronized(lock) { publishLocked() }

    private fun publishLocked() {
        val file = store.state.value
        _state.value = RemoteHostsState(
            hosts = file.hosts,
            defaultHostId = file.defaultHostId.takeIf { it.isNotBlank() },
            runtime = runtime.toMap(),
        )
    }

    private fun setRuntime(id: String, value: RemoteRuntime) = synchronized(lock) {
        runtime[id] = value
        publishLocked()
    }

    private fun updateRuntimeTunnel(id: String, tunnel: RemoteTunnelStatus) = synchronized(lock) {
        val current = runtime[id] ?: RemoteRuntime()
        runtime[id] = current.copy(
            phase = if (current.phase == RemotePhase.OFFLINE) RemotePhase.ONLINE else current.phase,
            health = current.health?.copy(tunnel = tunnel),
        )
        publishLocked()
    }

    private fun addActive(hostId: String) = synchronized(lock) {
        activeTurns[hostId] = (activeTurns[hostId] ?: 0) + 1
    }

    private fun decActive(hostId: String) = synchronized(lock) {
        val next = (activeTurns[hostId] ?: 0) - 1
        if (next <= 0) activeTurns.remove(hostId) else activeTurns[hostId] = next
    }

    private fun hasActive(hostId: String): Boolean = synchronized(lock) {
        (activeTurns[hostId] ?: 0) > 0
    }

    private suspend fun rememberHealth(profile: RemoteHostProfile, health: RemoteHealth) {
        store.update(profile.id) { applyHealth(it, health) }
        setRuntime(profile.id, RemoteRuntime(RemotePhase.ONLINE, null, health))
    }

    private fun applyHealth(profile: RemoteHostProfile, health: RemoteHealth): RemoteHostProfile =
        profile.copy(
            hostName = health.hostName,
            platform = health.platform,
            protocol = health.protocol,
            defaultCwd = health.defaultCwd,
            workspaceRoots = health.workspaceRoots,
            tunnel = health.tunnel,
            allowBypassPermissions = health.allowBypassPermissions,
            lastSeenAt = System.currentTimeMillis(),
        )

    private fun clientFor(profile: RemoteHostProfile): RemoteClient {
        val token = cipher.open(profile.tokenSealed)
            ?: throw RemoteException("主机凭据已失效，请重新配对", 401)
        val accessSecret = profile.accessSecretSealed
            .takeIf { it.isNotBlank() }
            ?.let { cipher.open(it) }
            .orEmpty()
        return RemoteClient(profile.baseUrl, token, profile.accessClientId, accessSecret)
    }

    /**
     * Buffers events that arrive between `POST /v1/turns` and its response (the daemon
     * publishes `started` before replying), then filters strictly by turn id. Without
     * the buffer the first token could be dropped; without the filter a replay of an
     * older turn would leak into the new one.
     */
    private class TurnCollector(
        val hostId: String,
        private val onEvent: (RemoteEvent) -> Unit,
        private val onFailed: (String) -> Unit,
    ) {
        val done = CompletableDeferred<Int>()
        @Volatile var turnId: String? = null
            private set
        private val buffered = ArrayDeque<RemoteEvent>()
        private var finished = false
        private var firstEventAt = 0L
        private var lastEventAt = System.currentTimeMillis()

        /** True once any event for this turn arrived, whatever the transport. */
        @Synchronized
        fun sawEvent(): Boolean = firstEventAt > 0

        @Synchronized
        fun millisSinceLastEvent(): Long = System.currentTimeMillis() - lastEventAt

        private fun noteEvent() {
            val now = System.currentTimeMillis()
            if (firstEventAt == 0L) firstEventAt = now
            lastEventAt = now
        }

        @Synchronized
        fun accept(event: RemoteEvent) {
            if (finished) return
            val target = turnId
            if (target == null) {
                if (buffered.size >= 2000) buffered.removeFirst()
                buffered.addLast(event)
            } else {
                handle(event, target)
            }
        }

        @Synchronized
        fun assign(id: String) {
            if (turnId != null) return
            turnId = id
            while (buffered.isNotEmpty()) {
                handle(buffered.removeFirst(), id)
            }
        }

        @Synchronized
        fun fail(message: String) {
            if (finished) return
            finished = true
            onFailed(message)
            done.complete(-1)
        }

        private fun handle(event: RemoteEvent, target: String) {
            when (event) {
                is RemoteEvent.Cli -> if (event.turnId == target) {
                    noteEvent()
                    onEvent(event)
                }
                is RemoteEvent.Permission -> if (event.turnId == target) {
                    noteEvent()
                    onEvent(event)
                }
                is RemoteEvent.Turn -> if (event.turnId == target) {
                    noteEvent()
                    if (event.phase == "done" || event.phase == "error") {
                        onEvent(event)
                        if (!finished) {
                            finished = true
                            done.complete(event.exitCode ?: if (event.phase == "error") -1 else 0)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        /** Last-resort cap; the daemon normally settles long before this. */
        private const val TURN_TIMEOUT_MS = 30 * 60_000L

        /** How long a working SSE may stay silent before polling takes over. */
        private const val SSE_FIRST_EVENT_GRACE_MS = 3_000L
        private const val SSE_STALL_CHECK_MS = 5_000L
        private const val SSE_STALL_MS = 20_000L

        /** No event at all after this long means the transport is not working at all. */
        private const val NO_EVENT_FAIL_MS = 25_000L

        private const val POLL_IDLE_DELAY_MS = 250L
        private const val POLL_RETRY_MS = 1_000L
    }
}

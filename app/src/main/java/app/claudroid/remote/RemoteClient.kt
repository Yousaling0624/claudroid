package app.claudroid.remote

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * REST + SSE client for `claudroid-bridge`.
 *
 * Deliberately built on [HttpURLConnection]: the app has no HTTP/WebSocket runtime
 * dependency, and SSE is a long-lived GET that Cloudflare Tunnel passes through.
 * The token only ever travels in the Authorization header (never a URL or a log).
 */
class RemoteClient(
    baseUrl: String,
    private val token: String? = null,
    accessClientId: String? = null,
    accessSecret: String? = null,
) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)
    private val accessClientId: String? = accessClientId?.takeIf { it.isNotBlank() }
    private val accessSecret: String? = accessSecret?.takeIf { it.isNotBlank() }

    fun health(): RemoteHealth = get("/v1/health")

    fun startTurn(body: RemoteTurnBody): RemoteTurnSnapshot {
        val payload = RemoteJson.json.encodeToString(RemoteTurnBody.serializer(), body)
        return post("/v1/turns", payload)
    }

    fun cancelTurn(turnId: String): RemoteTurnSnapshot {
        val id = URLEncoder.encode(turnId, "UTF-8")
        return post("/v1/turns/$id/cancel", "")
    }

    fun respondPermission(turnId: String, body: RemotePermissionBody) {
        val id = URLEncoder.encode(turnId, "UTF-8")
        val payload = RemoteJson.json.encodeToString(RemotePermissionBody.serializer(), body)
        post<RemoteErrorBody>("/v1/turns/$id/permission", payload)
    }

    fun turnSnapshot(turnId: String): RemoteTurnSnapshot {
        val id = URLEncoder.encode(turnId, "UTF-8")
        return get("/v1/turns/$id")
    }

    fun listFiles(path: String): List<RemoteFileEntry> =
        RemoteJson.json
            .decodeFromString(RemoteFileList.serializer(), getRaw(fsUrl("list", path)))
            .entries

    fun stat(path: String): RemoteFileEntry =
        RemoteJson.json.decodeFromString(RemoteFileEntry.serializer(), getRaw(fsUrl("stat", path)))

    /**
     * Bounded JSON alternative to SSE. Cloudflare Quick Tunnels buffer
     * `text/event-stream`, so the app falls back to this while a turn is running.
     */
    fun eventsSince(since: Long, timeoutMs: Int = 15_000): RemoteEventBatch =
        get("/v1/events/batch?since=${since.coerceAtLeast(0)}&timeout=$timeoutMs")

    fun download(path: String, target: File) {
        val conn = open(fsUrl("download", path), "GET")
        conn.readTimeout = 300_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RemoteException(errorMessage(conn, code), code)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            conn.inputStream.use { input ->
                tmp.outputStream().buffered(256 * 1024).use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } finally {
            conn.disconnect()
        }
    }

    fun upload(path: String, source: File) {
        val conn = open(fsUrl("upload", path), "PUT")
        conn.readTimeout = 300_000
        try {
            conn.doOutput = true
            // The daemon enforces its configured limit; fixed-length streaming avoids
            // buffering a large attachment in memory.
            conn.setFixedLengthStreamingMode(source.length())
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw RemoteException(errorMessage(conn, code), code)
        } finally {
            conn.disconnect()
        }
    }

    fun mkdir(path: String) {
        post<RemoteErrorBody>("/v1/fs/mkdir", jsonObject("path" to path))
    }

    fun rename(from: String, to: String) {
        post<RemoteErrorBody>("/v1/fs/rename", jsonObject("from" to from, "to" to to))
    }

    fun delete(path: String, recursive: Boolean = false) {
        val suffix = if (recursive) "&recursive=1" else ""
        val conn = open("/v1/fs?path=${encode(path)}$suffix", "DELETE")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RemoteException(errorMessage(conn, code), code)
        } finally {
            conn.disconnect()
        }
    }

    fun tunnel(): RemoteTunnelStatus = get("/v1/tunnel")

    fun tunnelAction(action: String): RemoteTunnelStatus =
        post("/v1/tunnel", jsonObject("action" to action))

    /** Opens the SSE stream. The caller owns [EventStream] and must close it. */
    fun openEvents(since: Long): EventStream {
        val conn = open("/v1/events?since=$since", "GET")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-cache")
        // The daemon sends a comment heartbeat every 15 s; a minute without one means
        // the path is gone (proxy, sleep, tunnel restart) and the manager reconnects.
        conn.readTimeout = 60_000
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
        if (code != 200) {
            val message = errorMessage(conn, code)
            conn.disconnect()
            throw RemoteException(message, code)
        }
        val latestSeq = conn.getHeaderField("X-Event-Seq")?.toLongOrNull() ?: 0
        return EventStream(conn, latestSeq)
    }

    inner class EventStream internal constructor(
        private val conn: HttpURLConnection,
        /** Sequence head of the daemon when the stream opened; 0 on older daemons. */
        val latestSeq: Long,
    ) : Closeable {
        @Volatile private var closed = false

        val isClosed: Boolean get() = closed

        /** Blocks and dispatches each complete frame until the stream ends. */
        fun read(onEvent: (RemoteEvent) -> Unit) {
            val parser = SseParser()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!closed) {
                    val line = reader.readLine() ?: break
                    for (frame in parser.feed(line + "\n")) {
                        if (frame.data.isBlank()) continue
                        val event = runCatching {
                            RemoteJson.json.decodeFromString(RemoteEvent.serializer(), frame.data)
                        }.getOrNull()
                        if (event != null) onEvent(event)
                    }
                }
            }
        }

        override fun close() {
            closed = true
            runCatching { conn.disconnect() }
        }
    }

    private fun fsUrl(op: String, path: String): String =
        "/v1/fs?op=$op&path=${encode(path)}"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun getRaw(path: String): String {
        val conn = open(path, "GET")
        return execute(conn, null)
    }

    private inline fun <reified T> get(path: String): T =
        RemoteJson.json.decodeFromString(getRaw(path))

    private inline fun <reified T> post(path: String, payload: String): T {
        val conn = open(path, "POST")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val text = execute(conn, payload.toByteArray(Charsets.UTF_8))
        return if (text.isBlank()) {
            // Some endpoints answer {ok:true}; callers that ask for a scalar type use
            // the concrete model above. This branch only guards malformed servers.
            throw RemoteException("主机返回了空响应")
        } else {
            RemoteJson.json.decodeFromString(text)
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 45_000
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (accessClientId != null && accessSecret != null) {
            conn.setRequestProperty("CF-Access-Client-Id", accessClientId)
            conn.setRequestProperty("CF-Access-Client-Secret", accessSecret)
        }
        return conn
    }

    private fun execute(conn: HttpURLConnection, body: ByteArray?): String {
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw RemoteException(errorMessage(conn, code), code)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: RemoteException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw RemoteException("连接主机超时，请检查网络或隧道状态", null, e)
        } catch (e: UnknownHostException) {
            throw RemoteException("无法解析主机地址，请检查地址是否正确", null, e)
        } catch (e: SSLException) {
            throw RemoteException("TLS 握手失败，请检查地址与证书", null, e)
        } catch (e: IOException) {
            throw RemoteException(friendlyNetworkError(e), null, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(conn: HttpURLConnection, code: Int): String {
        val raw = runCatching {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                buildString {
                    var budget = 64 * 1024
                    while (budget > 0) {
                        val line = reader.readLine() ?: break
                        append(line)
                        budget -= line.length
                    }
                }
            }
        }.getOrNull().orEmpty()
        val parsed = runCatching {
            RemoteJson.json.decodeFromString(RemoteErrorBody.serializer(), raw).error
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() }
            ?: when (code) {
                401 -> "未授权：配对可能已失效，请重新配对"
                403 -> "主机拒绝了该操作"
                404 -> "主机上不存在该接口或文件"
                409 -> "主机状态冲突，请稍后重试"
                413 -> "文件或请求过大"
                else -> "主机返回错误（HTTP $code）"
            }
    }

    private fun jsonObject(vararg pairs: Pair<String, String>): String = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }.toString()

    companion object {
        /**
         * Accepts `host:port`, `http://host:port` and a reverse-proxy prefix. Only
         * http/https are allowed; a query or fragment would silently break every
         * relative API path.
         */
        fun normalizeBaseUrl(raw: String): String {
            var value = raw.trim()
            if (value.isEmpty()) throw RemoteException("请填写主机地址")
            if (!value.contains("://")) value = "http://$value"
            val url = try {
                URL(value)
            } catch (e: Exception) {
                throw RemoteException("主机地址格式不正确", null, e)
            }
            if (url.protocol != "http" && url.protocol != "https") {
                throw RemoteException("主机地址只支持 http:// 或 https://")
            }
            if (url.host.isNullOrBlank()) throw RemoteException("主机地址缺少域名或 IP")
            if (url.query != null || url.ref != null) {
                throw RemoteException("主机地址不能带查询参数或锚点")
            }
            return value.trimEnd('/')
        }

        fun friendlyNetworkError(e: Throwable): String = when (e) {
            is RemoteException -> e.message ?: "主机返回错误"
            is SocketTimeoutException -> "连接主机超时，请检查网络或隧道状态"
            is UnknownHostException -> "无法解析主机地址，请检查地址是否正确"
            is SSLException -> "TLS 握手失败，请检查地址与证书"
            is IOException -> e.message?.takeIf { it.isNotBlank() }?.let { "网络错误：$it" } ?: "网络错误"
            else -> e.message?.takeIf { it.isNotBlank() } ?: "未知错误"
        }

        fun pair(baseUrl: String, code: String, deviceName: String): RemotePairResponse {
            val client = RemoteClient(baseUrl)
            val payload = RemoteJson.json.encodeToString(
                RemotePairRequest.serializer(),
                RemotePairRequest(code.trim().uppercase(), deviceName),
            )
            val conn = client.open("/v1/pair", "POST")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val text = client.execute(conn, payload.toByteArray(Charsets.UTF_8))
            return RemoteJson.json.decodeFromString(RemotePairResponse.serializer(), text)
        }
    }
}

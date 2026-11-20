package app.claudroid.backup

import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

/** WebDAV target from docs/BACKUP.md §5.1. The URL is the complete backup file path. */
object WebDavTarget {

    suspend fun test(config: WebDavConfig): String {
        val response = BackupHttp.request("HEAD", config.url.trim(), headers(config), secrets = secrets(config))
        val label = label(config)
        return when (response.code) {
            200, 204, 405 -> "连接正常（$label）"
            404 -> "连接正常，云端还没有备份（$label）"
            401, 403 -> "WebDAV 认证失败（HTTP ${response.code}）"
            else -> throw IllegalStateException(response.errorText())
        }
    }

    suspend fun upload(config: WebDavConfig, file: File, onProgress: (Long, Long) -> Unit) {
        val url = config.url.trim()
        // Collections are created best effort: some servers allow PUT to create the
        // file, and a failed MKCOL must not hide the more useful PUT error.
        parentCollections(url).forEach { parent ->
            runCatching { mkcol(parent, authHeader(config)) }
        }
        val response = BackupHttp.uploadFile(
            url = url,
            headers = headers(config) + ("Content-Type" to "application/zip"),
            file = file,
            secrets = secrets(config),
            onProgress = onProgress,
        )
        if (response.code !in setOf(200, 201, 204)) throw IllegalStateException(response.errorText())
    }

    suspend fun download(
        config: WebDavConfig,
        dest: File,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val response = BackupHttp.download(
            url = config.url.trim(),
            headers = headers(config),
            dest = dest,
            maxBytes = maxBytes,
            secrets = secrets(config),
            onProgress = onProgress,
        )
        if (response.code == 404) throw IllegalStateException("云端还没有备份，请先上传一次")
        if (response.code != 200) throw IllegalStateException(response.errorText())
    }

    /**
     * Every ancestor collection, shallowest first, excluding the origin itself.
     * `https://dav.example.com/a/b/file.zip` → `/a`, `/a/b`.
     */
    fun parentCollections(rawUrl: String): List<String> {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme ?: return emptyList()
        val authority = uri.rawAuthority ?: return emptyList()
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.size <= 1) return emptyList()
        val base = "$scheme://$authority"
        val result = mutableListOf<String>()
        var path = ""
        for (index in 0 until segments.size - 1) {
            path += "/${segments[index]}"
            result += base + path
        }
        return result
    }

    fun label(config: WebDavConfig): String = "WebDAV · ${host(config.url)}"

    fun host(rawUrl: String): String =
        runCatching { URI(rawUrl.trim()).host }.getOrNull().orEmpty().ifBlank { rawUrl.trim() }

    fun authHeader(config: WebDavConfig): String? {
        if (config.username.isBlank()) return null
        val raw = "${config.username}:${config.password}".toByteArray(StandardCharsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    /**
     * Sends one `MKCOL` over a raw socket.
     *
     * Android's `HttpURLConnection` validates the method against the standard set and
     * throws `ProtocolException("Invalid HTTP method: MKCOL")`, so the usual request
     * helper cannot be used. A minimal HTTP/1.1 exchange is enough: the server only
     * answers the status line, and the collection either exists (201/405) or the later
     * PUT reports the real problem. Failures return null and stay non-fatal.
     */
    internal fun mkcol(url: String, authorization: String?, timeoutMs: Int = BackupHttp.CONNECT_TIMEOUT_MS): Int? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port >= 0) uri.port else defaultPort
        val target = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val hostHeader = if (uri.port >= 0 && uri.port != defaultPort) "$host:$port" else host
        val request = buildString {
            append("MKCOL ").append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("User-Agent: Claudroid/1.0 (Android)\r\n")
            if (!authorization.isNullOrBlank()) append("Authorization: ").append(authorization).append("\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val socket = if (scheme == "https") tls(raw, host, port) else raw
            socket.use { s ->
                // Do not close the output stream: closing a socket stream closes the
                // whole socket and the status line could no longer be read.
                s.getOutputStream().apply {
                    write(request.toByteArray(StandardCharsets.ISO_8859_1))
                    flush()
                }
                val status = s.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1).readLine()
                    ?: return null
                return status.substringAfter(' ', "").substringBefore(' ').toIntOrNull()
            }
        } catch (e: IOException) {
            return null
        } finally {
            runCatching { raw.close() }
        }
    }

    private fun tls(raw: Socket, host: String, port: Int): Socket {
        val ssl = HttpsURLConnection.getDefaultSSLSocketFactory()
            .createSocket(raw, host, port, true) as SSLSocket
        // Same hostname verification as HttpsURLConnection; without this a
        // mis-issued certificate for another host would still be accepted.
        val params = ssl.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.startHandshake()
        return ssl
    }

    private fun headers(config: WebDavConfig): Map<String, String> =
        authHeader(config)?.let { mapOf("Authorization" to it) } ?: emptyMap()

    private fun secrets(config: WebDavConfig): List<String> =
        listOfNotNull(config.password, authHeader(config)).filter { it.isNotBlank() }
}

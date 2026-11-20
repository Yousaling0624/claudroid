package app.claudroid.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal HTTP layer for the three backup targets.
 *
 * Every response is untrusted input: bodies are capped, controls flattened, and
 * credentials are erased before an error message is built. Uploads use fixed-length
 * streaming (the `Content-Length` is part of the contract) and check cancellation
 * every 256 KiB, so `backup.cancel` stops a large upload promptly.
 */
object BackupHttp {

    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 120_000
    const val CHUNK = 256 * 1024

    private const val MAX_SMALL_BODY = 256 * 1024
    private const val MAX_DETAIL = 2_000
    private const val UA = "Claudroid/1.0 (Android)"

    private val AUTH_ECHO = Regex("(?i)\\b(bearer|basic|AWS4-HMAC-SHA256)\\s+[^\\s,;]+")
    private val NAMED_SECRET = Regex(
        "(?i)\\b(access[_-]?key(?:[_-]?id)?|secret[_-]?key|password|token)\\s*[=:]\\s*[^\\s&,;\"'<>]+",
    )

    data class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299

        fun errorText(): String = if (body.isBlank()) "HTTP $code" else "HTTP $code: $body"
    }

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        secrets: List<String> = emptyList(),
        maxBody: Int = MAX_SMALL_BODY,
    ): Response {
        val conn = open(method, url, headers)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            return Response(code, responseBody(conn, code, secrets, maxBody))
        } finally {
            conn.disconnect()
        }
    }

    /** Streams [file] with a fixed Content-Length; cancellation is checked per chunk. */
    suspend fun uploadFile(
        url: String,
        headers: Map<String, String>,
        file: File,
        secrets: List<String> = emptyList(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Response {
        val length = file.length()
        val conn = open("PUT", url, headers)
        try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(length)
            conn.outputStream.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(CHUNK)
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        written += n
                        onProgress(written, length)
                    }
                }
            }
            val code = conn.responseCode
            return Response(code, responseBody(conn, code, secrets, MAX_SMALL_BODY))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams a body whose exact size is known up front. [writeBody] must write exactly
     * [contentLength] bytes; GitHub's PUT JSON uses this to base64 the archive without
     * materialising a second copy in memory.
     */
    suspend fun upload(
        url: String,
        headers: Map<String, String>,
        contentLength: Long,
        secrets: List<String> = emptyList(),
        writeBody: suspend (OutputStream) -> Unit,
    ): Response {
        val conn = open("PUT", url, headers)
        try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(contentLength)
            conn.outputStream.use { output -> writeBody(output) }
            val code = conn.responseCode
            return Response(code, responseBody(conn, code, secrets, MAX_SMALL_BODY))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Writes a 200 response to [dest]. Non-200 bodies are returned for the caller's
     * 404/403 handling; a failed or oversized transfer always deletes [dest].
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        dest: File,
        maxBytes: Long,
        secrets: List<String> = emptyList(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Response {
        val conn = open("GET", url, headers)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return Response(code, responseBody(conn, code, secrets, MAX_SMALL_BODY))
            }
            val declared = conn.contentLengthLong
            if (declared > maxBytes) {
                throw IllegalStateException("下载内容超过上限（${bytesLabel(maxBytes)}）")
            }
            dest.parentFile?.mkdirs()
            dest.delete()
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(dest).buffered(CHUNK).use { output ->
                        val buffer = ByteArray(CHUNK)
                        var read = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            read += n
                            if (read > maxBytes) {
                                throw IllegalStateException("下载内容超过上限（${bytesLabel(maxBytes)}）")
                            }
                            output.write(buffer, 0, n)
                            onProgress(read, if (declared > 0) declared else -1L)
                        }
                    }
                }
            } catch (t: Throwable) {
                dest.delete()
                throw t
            }
            return Response(code, "")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Removes anything that looks like a credential from an upstream error body.
     * Servers occasionally echo request headers or query parameters; those must never
     * reach the status event or the log.
     */
    fun scrub(text: String, secrets: List<String> = emptyList()): String {
        var clean = text
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
        secrets
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }
            .forEach { secret -> clean = clean.replace(secret, "***") }
        clean = AUTH_ECHO.replace(clean) { match -> "${match.groupValues[1]} ***" }
        clean = NAMED_SECRET.replace(clean) { match -> "${match.groupValues[1]}=***" }
        return clean.trim().take(MAX_DETAIL)
    }

    private fun open(method: String, url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private fun responseBody(
        conn: HttpURLConnection,
        code: Int,
        secrets: List<String>,
        maxBody: Int,
    ): String {
        val stream = try {
            if (code in 200..299) conn.inputStream else conn.errorStream
        } catch (e: IOException) {
            null
        }
        val text = readLimited(stream, maxBody)
        return scrub(text, secrets)
    }

    private fun readLimited(stream: InputStream?, maxBytes: Int): String {
        if (stream == null) return ""
        val out = java.io.ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (out.size() < maxBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun bytesLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GiB"
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MiB"
        else -> "$bytes 字节"
    }
}

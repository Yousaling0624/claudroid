package app.claudroid.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

object HttpUtil {

    private const val UA = "Claudroid/1.0 (Android)"
    private const val MAX_ATTEMPTS = 3
    private const val MAX_RESPONSE_CHARS = 512_000
    private const val MAX_ERROR_CHARS = 4_096

    /**
     * A dead registry host must not eat the whole install: DNS poisoning / no IPv6
     * route typically hangs until the connect timeout, and each source is tried once.
     * 10 s is short enough to fall through, long enough for a slow mobile handshake.
     */
    private const val OCI_CONNECT_TIMEOUT_MS = 10_000

    /** Blob bodies are large; a slow but live link still gets two minutes per read. */
    private const val OCI_READ_TIMEOUT_MS = 120_000

    /** Some gateways echo request headers back in an error body; never let a key leak. */
    private val bearerEcho = Regex("""Bearer\s+\S+""", RegexOption.IGNORE_CASE)

    fun getText(url: String): String = getText(url, emptyMap())

    /** [direct] bypasses the device-wide HTTP proxy, required for loopback/LAN endpoints. */
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        direct: Boolean = false,
        connectTimeoutMs: Int = 20_000,
    ): String {
        val target = URL(url)
        val connection = if (direct) target.openConnection(Proxy.NO_PROXY) else target.openConnection()
        val conn = (connection as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = 60_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code for $url")
            // Cap the body: every registry answer is untrusted input.
            return readLimited(conn.inputStream, MAX_RESPONSE_CHARS)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POSTs a JSON payload and returns the 2xx response body.
     *
     * [direct] bypasses the device-wide HTTP proxy for loopback/LAN endpoints. Response
     * bodies are capped because every upstream answer is untrusted input; error bodies
     * are additionally truncated and flattened before going into an exception message.
     * The URL's query is stripped from that message — a few gateways accept the key as a
     * query parameter, and no exception text may carry credentials.
     */
    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
        direct: Boolean = false,
    ): String {
        val target = URL(url)
        val connection = if (direct) target.openConnection(Proxy.NO_PROXY) else target.openConnection()
        val conn = (connection as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val echoedSecrets = headers
                    .filterKeys { it.equals("Authorization", true) || it.equals("x-api-key", true) }
                    .values
                    .filter { it.isNotBlank() }
                val detail = runCatching { readLimited(conn.errorStream, MAX_ERROR_CHARS) }
                    .getOrDefault("")
                    .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                    .let { bearerEcho.replace(it, "Bearer ***") }
                    .let { text -> echoedSecrets.fold(text) { acc, secret -> acc.replace(secret, "***") } }
                    .trim()
                    .take(500)
                val safeUrl = url.substringBefore('?')
                error(if (detail.isEmpty()) "HTTP $code for $safeUrl" else "HTTP $code for $safeUrl: $detail")
            }
            return readLimited(conn.inputStream, MAX_RESPONSE_CHARS)
        } finally {
            conn.disconnect()
        }
    }

    /** Reads at most [maxChars] characters, so a misbehaving endpoint cannot exhaust memory. */
    private fun readLimited(stream: InputStream?, maxChars: Int): String {
        if (stream == null) return ""
        val out = StringBuilder()
        stream.bufferedReader().use { reader ->
            val buf = CharArray(4_096)
            while (out.length < maxChars) {
                val n = reader.read(buf, 0, minOf(buf.size, maxChars - out.length))
                if (n <= 0) break
                out.append(buf, 0, n)
            }
        }
        return out.toString()
    }

    /**
     * Streams [url] into [dest] with resume support and retries.
     *
     * The CLI artifact is ~150 MB; on a mobile connection a single-shot download fails
     * often enough that "重新下载" would become the app's main feature. Each attempt
     * continues from the existing byte count via `Range`, and the returned digest is
     * computed over the finished file, so a resumed transfer is verified exactly like a
     * fresh one.
     */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                downloadWithResume(url, dest, onProgress)
                return sha512Base64(dest)
            } catch (t: Throwable) {
                lastError = t
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(1_200L * attempt)
                }
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    private fun downloadWithResume(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val already = if (dest.isFile) dest.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        try {
            val code = conn.responseCode
            val appending = already > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (code !in 200..299) error("HTTP $code for $url")

            val startAt = if (appending) already else 0L
            if (!appending && dest.exists()) dest.delete()
            val contentLength = conn.contentLengthLong
            val total = when {
                contentLength <= 0L -> -1L
                appending -> startAt + contentLength
                else -> contentLength
            }
            var read = startAt
            FileOutputStream(dest, appending).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            // A short read with a known content-length means the connection died mid-stream;
            // surface it so the retry loop can resume instead of returning a truncated file.
            if (total > 0 && read < total) {
                error("传输中断（$read/$total 字节）")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads one OCI layer blob and returns its lowercase SHA-256 hex plus the source
     * that served it (the name is safe to log; the token never is).
     *
     * [sources] are tried strictly in order, once each, with a short connect timeout:
     * the official Docker Hub pair first, then a mirror. On some networks
     * `auth.docker.io` resolves to a poisoned address and hangs, so retrying the same
     * dead host three times would just delay the fallback. A source only counts as
     * successful when the finished file hashes to the pinned digest; otherwise the file
     * is deleted and the next source is tried.
     *
     * Docker Hub does not serve blobs directly: the authorized request answers 307 with a
     * short-lived signed URL on CloudFront/S3. The `Authorization` header must *not* be
     * forwarded to that URL — S3 rejects a request carrying two auth schemes. We therefore
     * disable automatic redirects, read `Location`, and re-fetch it anonymously.
     */
    fun downloadOciBlob(
        repository: String,
        digest: String,
        dest: File,
        sources: List<OciSource> = Registry.OCI_SOURCES,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): OciBlob {
        val expected = digestHex(digest)
        require(expected.isNotEmpty()) { "rootfs 摘要无效：$digest" }
        val verified = firstVerifiedSource(sources, expected, dest) { source ->
            val token = ociToken(source, repository)
            fetchOciBlob(source, repository, digest, token, dest, onProgress)
        }
        return OciBlob(verified.sha256, verified.source.name, dest.length())
    }

    data class OciBlob(val sha256: String, val source: String, val bytes: Long)

    internal data class VerifiedBlob(val sha256: String, val source: OciSource)

    /**
     * Calls [fetch] for each source until the resulting [dest] hashes to [expectedSha256].
     *
     * Pure orchestration (no network): the fetcher is injected, which is what makes the
     * ordered-fallback behaviour unit-testable. [dest] is deleted after every failed or
     * mismatched attempt so a truncated download can never survive as a cache.
     */
    internal fun firstVerifiedSource(
        sources: List<OciSource>,
        expectedSha256: String,
        dest: File,
        fetch: (OciSource) -> Unit,
    ): VerifiedBlob {
        require(sources.isNotEmpty()) { "没有可用的 rootfs 来源" }
        var lastError: Throwable? = null
        for (source in sources) {
            try {
                fetch(source)
                val got = sha256Hex(dest)
                if (got == expectedSha256) return VerifiedBlob(got, source)
                lastError = IllegalStateException("${source.name} 返回的 rootfs 与固定摘要不符")
            } catch (t: Throwable) {
                lastError = t
            }
            runCatching { if (dest.exists()) dest.delete() }
        }
        throw lastError ?: IllegalStateException("rootfs 下载失败")
    }

    private fun ociToken(source: OciSource, repository: String): String {
        val body = getText(source.tokenUrl(repository), connectTimeoutMs = OCI_CONNECT_TIMEOUT_MS)
        val obj = Json.parseToJsonElement(body).jsonObject
        val token = obj["token"]?.jsonPrimitive?.content
            ?: obj["access_token"]?.jsonPrimitive?.content
        check(!token.isNullOrBlank()) { "${source.name} 未返回拉取令牌" }
        return token
    }

    private class OciStatus(val code: Int, url: String) : Exception("HTTP $code for $url")

    private fun fetchOciBlob(
        source: OciSource,
        repository: String,
        digest: String,
        token: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val blobUrl = source.blobUrl(repository, digest)
        var conn = (URL(blobUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = OCI_CONNECT_TIMEOUT_MS
            readTimeout = OCI_READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            var code = conn.responseCode
            if (code == 401) throw OciStatus(401, blobUrl)
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: error("registry 返回 $code 但缺少 Location")
                conn.disconnect()
                // Signed storage URL: no Authorization (S3 rejects double auth).
                conn = (URL(location).openConnection() as HttpURLConnection).apply {
                    connectTimeout = OCI_CONNECT_TIMEOUT_MS
                    readTimeout = OCI_READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                }
                code = conn.responseCode
            }
            if (code !in 200..299) throw OciStatus(code, blobUrl)

            if (dest.exists()) dest.delete()
            val total = conn.contentLengthLong
            var read = 0L
            FileOutputStream(dest).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            if (read <= 0L) error("rootfs 下载为空")
            if (total > 0 && read < total) error("传输中断（$read/$total 字节）")
        } finally {
            conn.disconnect()
        }
    }

    /** SHA-256 of an existing file, lowercase hex — the OCI content-address format. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** `sha256:abcd…` → `abcd…`; blank for anything else (defensive against bad JSON). */
    fun digestHex(digest: String): String {
        val hex = digest.substringAfter("sha256:", "")
        return if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            hex.lowercase()
        } else {
            ""
        }
    }

    /** SHA-512 of an existing file, base64 encoded — used to validate a cached download. */
    fun sha512Base64(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    /**
     * npm publishes `sha512-<base64>`; compare against what we computed.
     *
     * Fail-closed: a missing or blank integrity means the registry gave us nothing to
     * verify against, so the download is refused. Returning `true` here would let an
     * unverified (or truncated/poisoned) CLI binary into the jail.
     */
    fun matchesIntegrity(computedBase64Sha512: String, integrity: String?): Boolean {
        if (integrity.isNullOrBlank()) return false
        val expected = integrity.substringAfter("sha512-", "")
        if (expected.isBlank()) return false
        return expected.equals(computedBase64Sha512, ignoreCase = true)
    }
}

package app.claudroid.engine

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

object HttpUtil {

    private const val UA = "Claudroid/1.0 (Android)"
    private const val MAX_ATTEMPTS = 3

    fun getText(url: String): String = getText(url, emptyMap())

    /** [direct] bypasses the device-wide HTTP proxy, required for loopback/LAN endpoints. */
    fun getText(url: String, headers: Map<String, String>, direct: Boolean = false): String {
        val target = URL(url)
        val connection = if (direct) target.openConnection(Proxy.NO_PROXY) else target.openConnection()
        val conn = (connection as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
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

    /** npm publishes `sha512-<base64>`; compare against what we computed. */
    fun matchesIntegrity(computedBase64Sha512: String, integrity: String?): Boolean {
        if (integrity.isNullOrBlank()) return true
        val expected = integrity.substringAfter("sha512-", "")
        return expected.equals(computedBase64Sha512, ignoreCase = true)
    }
}

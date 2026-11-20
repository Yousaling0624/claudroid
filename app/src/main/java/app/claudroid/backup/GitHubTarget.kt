package app.claudroid.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * GitHub repository target from docs/BACKUP.md §5.3, using the Contents API.
 *
 * GitHub caps a file at 100 MB and base64 expands by 4/3, so the 95 MB guard is
 * enforced before a single byte is uploaded. The PUT body is streamed: encoding a
 * 95 MB archive would otherwise need a second ~127 MB string in memory.
 */
object GitHubTarget {

    const val API = "https://api.github.com"
    const val DEFAULT_BRANCH = "main"
    const val DEFAULT_PATH = "claudroid-backup.zip"
    const val MAX_ARCHIVE_BYTES = 95L * 1024 * 1024

    /**
     * Contents responses for files up to 1 MB embed base64 content; larger files omit
     * it. 2 MiB covers the embedded case without ever buffering a whole archive.
     */
    private const val MAX_JSON_BODY = 2 * 1024 * 1024

    /** Divisible by 3 so each full buffer encodes without padding. */
    private const val CHUNK = 255 * 1024

    private val MESSAGE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val SHA = Regex("\"sha\"\\s*:\\s*\"([0-9a-fA-F]{40,64})\"")
    private val JSON = Json { ignoreUnknownKeys = true }

    data class ContentsResponse(
        val sha: String?,
        val content: String?,
        val size: Long?,
        val encoding: String?,
    )

    fun label(config: GithubConfig): String = "GitHub · ${config.repo.trim()}"

    fun branch(config: GithubConfig): String = config.branch.trim().ifBlank { DEFAULT_BRANCH }

    /** `path` is repo-relative; a blank value falls back to the documented default. */
    fun filePath(config: GithubConfig): String = config.path.trim().trim('/').ifBlank { DEFAULT_PATH }

    fun contentsUrl(config: GithubConfig, withRef: Boolean): String {
        val base = "$API/repos/${repoPath(config.repo)}/contents/${encodePath(filePath(config))}"
        return if (withRef) "$base?ref=${encodePath(branch(config))}" else base
    }

    internal fun repoPath(repo: String): String {
        val parts = repo.trim().split('/')
        require(parts.size == 2 && parts.all { it.isNotBlank() }) { "GitHub 仓库格式应为 owner/name" }
        return parts.joinToString("/") { SigV4.encodeSegment(it) }
    }

    /** Percent-encodes each segment but keeps `/` separators, as the API expects. */
    fun encodePath(raw: String): String =
        raw.split('/').joinToString("/") { SigV4.encodeSegment(it) }

    /**
     * Decodes one Contents API answer. Anything that is not a JSON object (a proxy
     * error page, a directory listing) yields null so callers can fall back safely.
     */
    fun parseContentsResponse(body: String): ContentsResponse? {
        val obj = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun text(key: String): String? = runCatching { obj[key]?.jsonPrimitive?.content }.getOrNull()
        val size = runCatching { obj["size"]?.jsonPrimitive?.longOrNull }.getOrNull()
        return ContentsResponse(
            sha = text("sha")?.takeIf { it.isNotBlank() },
            content = text("content"),
            size = size,
            encoding = text("encoding"),
        )
    }

    /**
     * The upload only needs the blob sha. A body truncated by the size cap can no
     * longer be parsed as JSON, but GitHub puts `sha` before the bulky `content`.
     */
    fun shaFromBody(body: String): String? =
        parseContentsResponse(body)?.sha ?: SHA.find(body)?.groupValues?.get(1)

    fun base64Length(bytes: Long): Long = if (bytes <= 0) 0 else 4 * ((bytes + 2) / 3)

    fun putBodyPrefix(message: String): String =
        "{\"message\":" + jsonString(message) + ",\"content\":\""

    fun putBodySuffix(branch: String, sha: String?): String {
        val shaField = if (sha.isNullOrBlank()) "" else ",\"sha\":" + jsonString(sha)
        // Leading quote closes the "content" string opened by putBodyPrefix.
        return "\",\"branch\":" + jsonString(branch) + shaField + "}"
    }

    fun putBody(message: String, base64: String, branch: String, sha: String?): String =
        putBodyPrefix(message) + base64 + putBodySuffix(branch, sha)

    fun message(at: LocalDateTime = LocalDateTime.now()): String =
        "Claudroid backup " + MESSAGE_TIME.format(at)

    fun requireArchiveSize(bytes: Long) {
        if (bytes > MAX_ARCHIVE_BYTES) {
            throw IllegalStateException("归档超过 95 MB，GitHub 仓库放不下，请改用 WebDAV 或 S3")
        }
    }

    suspend fun test(config: GithubConfig): String {
        val response = BackupHttp.request(
            method = "GET",
            url = "$API/repos/${repoPath(config.repo)}",
            headers = headers(config),
            secrets = secrets(config),
        )
        return when (response.code) {
            200 -> "连接正常（${label(config)}）"
            401 -> "GitHub Token 无效（HTTP 401）"
            404 -> "仓库不存在或 Token 无权访问（HTTP 404）"
            else -> throw IllegalStateException(response.errorText())
        }
    }

    suspend fun upload(
        config: GithubConfig,
        archive: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val length = archive.length()
        requireArchiveSize(length)
        val branch = branch(config)
        val sha = existingSha(config)
        val prefix = putBodyPrefix(message()).toByteArray(StandardCharsets.UTF_8)
        val suffix = putBodySuffix(branch, sha).toByteArray(StandardCharsets.UTF_8)
        val contentLength = prefix.size.toLong() + base64Length(length) + suffix.size
        val response = BackupHttp.upload(
            url = contentsUrl(config, withRef = false),
            headers = headers(config) + ("Content-Type" to "application/json"),
            contentLength = contentLength,
            secrets = secrets(config),
        ) { output ->
            output.write(prefix)
            writeBase64(archive, output, onProgress)
            output.write(suffix)
        }
        if (response.code !in 200..299) throw IllegalStateException(response.errorText())
    }

    suspend fun download(
        config: GithubConfig,
        dest: File,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val response = BackupHttp.request(
            method = "GET",
            url = contentsUrl(config, withRef = true),
            headers = headers(config),
            secrets = secrets(config),
            maxBody = MAX_JSON_BODY,
        )
        when (response.code) {
            200 -> Unit
            401 -> throw IllegalStateException("GitHub Token 无效（HTTP 401）")
            404 -> throw IllegalStateException("云端还没有备份，请先上传一次")
            else -> throw IllegalStateException(response.errorText())
        }
        val parsed = parseContentsResponse(response.body)
        val content = parsed?.content
        if (content.isNullOrBlank()) {
            // GitHub omits `content` for files between 1 MB and 100 MB; the raw
            // media type is the documented way to fetch those.
            downloadRaw(config, dest, maxBytes, onProgress)
            return
        }
        val declared = parsed.size?.takeIf { it > 0 } ?: (content.length.toLong() / 4 * 3)
        if (declared > maxBytes) throw IllegalStateException("下载内容超过上限（${bytesLabel(maxBytes)}）")
        dest.parentFile?.mkdirs()
        dest.delete()
        try {
            val encoded = content.toByteArray(StandardCharsets.US_ASCII)
            Base64.getMimeDecoder().wrap(ByteArrayInputStream(encoded)).use { input ->
                FileOutputStream(dest).buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
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
                        onProgress(read, declared)
                    }
                }
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
    }

    private suspend fun downloadRaw(
        config: GithubConfig,
        dest: File,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val response = BackupHttp.download(
            url = contentsUrl(config, withRef = true),
            headers = headers(config) + ("Accept" to "application/vnd.github.raw"),
            dest = dest,
            maxBytes = maxBytes,
            secrets = secrets(config),
            onProgress = onProgress,
        )
        if (response.code == 401) throw IllegalStateException("GitHub Token 无效（HTTP 401）")
        if (response.code == 404) throw IllegalStateException("云端还没有备份，请先上传一次")
        if (response.code != 200) throw IllegalStateException(response.errorText())
    }

    private suspend fun existingSha(config: GithubConfig): String? {
        val response = BackupHttp.request(
            method = "GET",
            url = contentsUrl(config, withRef = true),
            headers = headers(config),
            secrets = secrets(config),
            maxBody = MAX_JSON_BODY,
        )
        return when (response.code) {
            200 -> shaFromBody(response.body)
            401 -> throw IllegalStateException("GitHub Token 无效（HTTP 401）")
            404 -> null
            else -> throw IllegalStateException(response.errorText())
        }
    }

    /** Streams base64 with no line breaks; [onProgress] counts archive bytes. */
    private suspend fun writeBase64(file: File, out: OutputStream, onProgress: (Long, Long) -> Unit) {
        val total = file.length()
        val encoder = Base64.getEncoder()
        val buffer = ByteArray(CHUNK)
        var carry = ByteArray(0)
        var written = 0L
        file.inputStream().buffered(CHUNK).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n <= 0) break
                val data = if (carry.isEmpty()) buffer.copyOf(n) else carry + buffer.copyOf(n)
                val full = data.size - data.size % 3
                if (full > 0) out.write(encoder.encode(data.copyOfRange(0, full)))
                carry = if (full == data.size) ByteArray(0) else data.copyOfRange(full, data.size)
                written += n
                onProgress(written, total)
            }
        }
        if (carry.isNotEmpty()) out.write(encoder.encode(carry))
    }

    private fun headers(config: GithubConfig): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${config.token.trim()}",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    private fun secrets(config: GithubConfig): List<String> =
        listOf(config.token.trim()).filter { it.isNotBlank() }

    private fun jsonString(value: String): String = JsonPrimitive(value).toString()

    private fun bytesLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GiB"
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MiB"
        else -> "$bytes 字节"
    }
}

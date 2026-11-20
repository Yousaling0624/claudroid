package app.claudroid.backup

import java.io.File
import java.net.URI
import java.net.URL
import java.time.Instant

/** S3 / S3-compatible target from docs/BACKUP.md §5.2. */
object S3Target {

    private const val FILE_NAME = "claudroid-backup.zip"

    data class Location(val url: URL, val key: String)

    fun effectiveEndpoint(config: S3Config): String {
        val raw = config.endpoint.trim()
        if (raw.isEmpty()) return "https://s3.${config.region.trim()}.amazonaws.com"
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        return withScheme.trimEnd('/')
    }

    /** `prefix` trimmed of surrounding slashes; blank means only the fixed file name. */
    fun objectKey(config: S3Config): String {
        val prefix = config.prefix.trim().trim('/')
        return if (prefix.isEmpty()) FILE_NAME else "$prefix/$FILE_NAME"
    }

    fun location(config: S3Config): Location {
        val key = objectKey(config)
        val endpoint = runCatching { URI(effectiveEndpoint(config)) }.getOrNull()
            ?: throw IllegalStateException("S3 Endpoint 格式无效")
        val scheme = endpoint.scheme?.lowercase() ?: throw IllegalStateException("S3 Endpoint 格式无效")
        val host = endpoint.host ?: throw IllegalStateException("S3 Endpoint 格式无效")
        val port = endpoint.port
        val basePath = endpoint.rawPath.orEmpty().trimEnd('/')
        val encodedKey = SigV4.encodePath(key)
        val bucket = config.bucket.trim()

        val authority: String
        val path: String
        if (config.pathStyle) {
            authority = if (port >= 0) "$host:$port" else host
            path = if (basePath.isEmpty()) "/$bucket/$encodedKey" else "$basePath/$bucket/$encodedKey"
        } else {
            // Virtual-host style puts the bucket in the DNS name; MinIO/other
            // path-style-only services usually keep the default (pathStyle=true).
            val virtualHost = "$bucket.$host"
            authority = if (port >= 0) "$virtualHost:$port" else virtualHost
            path = if (basePath.isEmpty()) "/$encodedKey" else "$basePath/$encodedKey"
        }
        val url = runCatching { URL("$scheme://$authority$path") }
            .getOrElse { throw IllegalStateException("S3 Endpoint 格式无效") }
        return Location(url, key)
    }

    suspend fun test(config: S3Config): String {
        val location = location(config)
        val response = BackupHttp.request(
            method = "HEAD",
            url = location.url.toString(),
            headers = signedHeaders("HEAD", location.url, SigV4.EMPTY_SHA256, config),
            secrets = secrets(config),
        )
        return when (response.code) {
            200 -> "连接正常（${label(config)}）"
            404 -> "连接正常，云端还没有备份（${label(config)}）"
            403 -> "S3 权限或密钥无效（HTTP 403）"
            else -> throw IllegalStateException(response.errorText())
        }
    }

    suspend fun upload(
        config: S3Config,
        file: File,
        sha256Hex: String,
        onProgress: (Long, Long) -> Unit,
    ) {
        val location = location(config)
        val response = BackupHttp.uploadFile(
            url = location.url.toString(),
            headers = signedHeaders("PUT", location.url, sha256Hex, config) +
                ("Content-Type" to "application/zip"),
            file = file,
            secrets = secrets(config),
            onProgress = onProgress,
        )
        if (response.code !in 200..299) throw IllegalStateException(response.errorText())
    }

    suspend fun download(
        config: S3Config,
        dest: File,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val location = location(config)
        val response = BackupHttp.download(
            url = location.url.toString(),
            headers = signedHeaders("GET", location.url, SigV4.EMPTY_SHA256, config),
            dest = dest,
            maxBytes = maxBytes,
            secrets = secrets(config),
            onProgress = onProgress,
        )
        if (response.code == 404) throw IllegalStateException("云端还没有备份，请先上传一次")
        if (response.code != 200) throw IllegalStateException(response.errorText())
    }

    fun label(config: S3Config): String = "S3 · ${config.bucket.trim()}"

    /** The exact signed set from BACKUP.md §5.2: host;x-amz-content-sha256;x-amz-date. */
    internal fun signedHeaders(
        method: String,
        url: URL,
        payloadHash: String,
        config: S3Config,
        at: Instant = Instant.now(),
    ): Map<String, String> {
        val signed = SigV4.sign(
            method = method,
            url = url,
            payloadHash = payloadHash,
            accessKey = config.accessKey.trim(),
            secretKey = config.secretKey,
            region = config.region.trim(),
            at = at,
        )
        return mapOf(
            "x-amz-date" to signed.amzDate,
            "x-amz-content-sha256" to payloadHash,
            "Authorization" to signed.authorization,
        )
    }

    private fun secrets(config: S3Config): List<String> =
        listOf(config.secretKey, config.accessKey).filter { it.isNotBlank() }
}

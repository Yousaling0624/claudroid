package app.claudroid.backup

import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 (header-based) for S3 and S3-compatible services.
 *
 * Only the pieces S3 needs are implemented: canonical URI/query formatting, the
 * `host;x-amz-content-sha256;x-amz-date` signed-header set, and the four-step signing
 * key derivation. Keeping it dependency-free means the exact same code runs in unit
 * tests and on the device.
 */
object SigV4 {

    const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    private const val HEX = "0123456789ABCDEF"

    data class Signed(
        val authorization: String,
        val amzDate: String,
        val payloadHash: String,
        val signedHeaders: List<String>,
        val canonicalRequest: String,
        val stringToSign: String,
    )

    /**
     * Signs one request. [extraHeaders] are additional signed headers (lowercase names);
     * production S3 passes none, so the resulting set is exactly the three headers in
     * docs/BACKUP.md §5.2. The parameter exists so official AWS vectors that sign other
     * headers can be reproduced byte-for-byte in tests.
     */
    fun sign(
        method: String,
        url: URL,
        payloadHash: String,
        accessKey: String,
        secretKey: String,
        region: String,
        at: Instant = Instant.now(),
        service: String = "s3",
        canonicalQuery: String = "",
        extraHeaders: Map<String, String> = emptyMap(),
        includePayloadHeader: Boolean = true,
    ): Signed {
        val amzDate = AMZ_DATE.format(at)
        val date = DATE.format(at)
        val host = hostHeader(url)
        val headers = sortedMapOf<String, String>()
        headers["host"] = host
        headers["x-amz-date"] = amzDate
        if (includePayloadHeader) headers["x-amz-content-sha256"] = payloadHash
        extraHeaders.forEach { (name, value) -> headers[name.lowercase(Locale.US)] = collapseSpaces(value) }

        val signedHeaderNames = headers.keys.toList()
        val canonicalHeaders = buildString {
            signedHeaderNames.forEach { name -> append(name).append(':').append(headers.getValue(name)).append('\n') }
        }
        val signedHeaders = signedHeaderNames.joinToString(";")
        val canonicalUri = canonicalUri(url.toURI().rawPath)
        val canonicalRequest = buildString {
            append(method.uppercase(Locale.US)).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        val scope = "$date/$region/$service/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(amzDate).append('\n')
            append(scope).append('\n')
            append(sha256Hex(canonicalRequest))
        }
        val signingKey = signingKey(secretKey, date, region, service)
        val signature = hex(hmacSha256(signingKey, stringToSign))
        val authorization = "$ALGORITHM Credential=$accessKey/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        return Signed(
            authorization = authorization,
            amzDate = amzDate,
            payloadHash = payloadHash,
            signedHeaders = signedHeaderNames,
            canonicalRequest = canonicalRequest,
            stringToSign = stringToSign,
        )
    }

    /** `HMAC("AWS4"+secret, date) → region → service → aws4_request`. */
    fun signingKey(secretKey: String, date: String, region: String, service: String): ByteArray =
        hmacSha256(
            hmacSha256(
                hmacSha256(
                    hmacSha256(("AWS4$secretKey").toByteArray(StandardCharsets.UTF_8), date),
                    region,
                ),
                service,
            ),
            "aws4_request",
        )

    fun signingKeyHex(secretKey: String, date: String, region: String, service: String): String =
        hex(signingKey(secretKey, date, region, service))

    /**
     * RFC 3986 encoding of every path segment (AWS "UriEncode"): unreserved characters
     * stay verbatim, everything else becomes uppercase `%XX`, and `/` is kept only as
     * the segment separator.
     */
    fun canonicalUri(rawPath: String?): String {
        val path = rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return path.split('/').joinToString("/") { encodeSegment(percentDecode(it)) }
    }

    fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    fun encodeSegment(value: String): String = buildString {
        value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val b = byte.toInt() and 0xff
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                append(c)
            } else {
                append('%').append(HEX[b ushr 4]).append(HEX[b and 0x0f])
            }
        }
    }

    /** Decodes `%XX` so [canonicalUri] can re-encode with exactly the AWS alphabet. */
    fun percentDecode(value: String): String {
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val high = value[i + 1].digitToIntOrNull(16)
                val low = value[i + 2].digitToIntOrNull(16)
                if (high != null && low != null) {
                    bytes.write((high shl 4) or low)
                    i += 3
                    continue
                }
            }
            val chunk = value.substring(i, i + 1).toByteArray(StandardCharsets.UTF_8)
            bytes.write(chunk, 0, chunk.size)
            i++
        }
        return bytes.toString(StandardCharsets.UTF_8.name())
    }

    /** Host header, including a non-default port (required for localhost/MinIO). */
    fun hostHeader(url: URL): String {
        val host = url.host ?: throw IllegalArgumentException("地址缺少主机名")
        val port = url.port
        return if (port == -1 || port == url.defaultPort) host else "$host:$port"
    }

    fun sha256Hex(value: String): String = hex(sha256(value.toByteArray(StandardCharsets.UTF_8)))

    fun sha256(value: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(value)

    fun hmacSha256(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val b = byte.toInt() and 0xff
            append(HEX[b ushr 4]).append(HEX[b and 0x0f])
        }
    }.lowercase(Locale.US)

    private fun collapseSpaces(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")
}

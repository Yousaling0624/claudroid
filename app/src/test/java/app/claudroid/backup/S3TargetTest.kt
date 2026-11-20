package app.claudroid.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.time.Instant

/** S3 URL assembly and the signed header set from docs/BACKUP.md §5.2. */
class S3TargetTest {

    private fun config(
        endpoint: String = "",
        region: String = "us-east-1",
        bucket: String = "my-bucket",
        prefix: String = "claudroid",
        pathStyle: Boolean = true,
        accessKey: String = "test-access-key",
        secretKey: String = "secret",
    ) = S3Config(
        endpoint = endpoint,
        region = region,
        bucket = bucket,
        accessKey = accessKey,
        secretKey = secretKey,
        prefix = prefix,
        pathStyle = pathStyle,
    )

    @Test
    fun `blank endpoint becomes the regional amazonaws host`() {
        assertEquals("https://s3.eu-west-1.amazonaws.com", S3Target.effectiveEndpoint(config(region = "eu-west-1")))
        assertEquals(
            "https://s3.eu-west-1.amazonaws.com/my-bucket/claudroid/claudroid-backup.zip",
            S3Target.location(config(region = "eu-west-1")).url.toString(),
        )
    }

    @Test
    fun `path style puts the bucket in the path`() {
        val location = S3Target.location(
            config(endpoint = "http://127.0.0.1:9000", bucket = "b", prefix = "p"),
        )
        assertEquals("http://127.0.0.1:9000/b/p/claudroid-backup.zip", location.url.toString())
        assertEquals("p/claudroid-backup.zip", location.key)
    }

    @Test
    fun `virtual host style puts the bucket in the host and keeps the port`() {
        val location = S3Target.location(
            config(endpoint = "http://minio.local:9000", bucket = "b", prefix = "p", pathStyle = false),
        )
        assertEquals("http://b.minio.local:9000/p/claudroid-backup.zip", location.url.toString())
    }

    @Test
    fun `endpoint base path is preserved`() {
        assertEquals(
            "https://minio.example.com/s3/b/p/claudroid-backup.zip",
            S3Target.location(config(endpoint = "https://minio.example.com/s3", bucket = "b", prefix = "p"))
                .url.toString(),
        )
        assertEquals(
            "https://b.minio.example.com/s3/p/claudroid-backup.zip",
            S3Target.location(
                config(
                    endpoint = "https://minio.example.com/s3",
                    bucket = "b",
                    prefix = "p",
                    pathStyle = false,
                ),
            ).url.toString(),
        )
    }

    @Test
    fun `object key trims prefix slashes and falls back to the fixed name`() {
        assertEquals("claudroid/claudroid-backup.zip", S3Target.objectKey(config()))
        assertEquals("p/claudroid-backup.zip", S3Target.objectKey(config(prefix = " /p/ ")))
        assertEquals("claudroid-backup.zip", S3Target.objectKey(config(prefix = "///")))
    }

    @Test
    fun `key segments are percent encoded in the signed url`() {
        val location = S3Target.location(
            config(endpoint = "https://s3.amazonaws.com", bucket = "b", prefix = "a b/目录"),
        )
        assertEquals("a b/目录/claudroid-backup.zip", location.key)
        assertTrue(location.url.toString().endsWith("/b/a%20b/%E7%9B%AE%E5%BD%95/claudroid-backup.zip"))
    }

    @Test
    fun `signed headers carry the payload hash and the documented order`() {
        val headers = S3Target.signedHeaders(
            method = "PUT",
            url = URL("https://s3.us-east-1.amazonaws.com/b/p/claudroid-backup.zip"),
            payloadHash = "a".repeat(64),
            config = config(),
            at = Instant.parse("2015-08-30T12:36:00Z"),
        )
        assertEquals("20150830T123600Z", headers["x-amz-date"])
        assertEquals("a".repeat(64), headers["x-amz-content-sha256"])
        val authorization = headers.getValue("Authorization")
        assertTrue(authorization.startsWith("${SigV4.ALGORITHM} Credential=test-access-key"))
        assertTrue(authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
        assertTrue(authorization.contains("Signature="))
    }

    @Test
    fun `empty hash is used for GET and HEAD`() {
        assertEquals(
            SigV4.EMPTY_SHA256,
            S3Target.signedHeaders(
                method = "GET",
                url = URL("https://s3.us-east-1.amazonaws.com/b/p.zip"),
                payloadHash = SigV4.EMPTY_SHA256,
                config = config(),
                at = Instant.parse("2015-08-30T12:36:00Z"),
            )["x-amz-content-sha256"],
        )
    }

    @Test
    fun `label names the bucket only`() {
        assertEquals("S3 · my-bucket", S3Target.label(config(bucket = " my-bucket ")))
    }
}

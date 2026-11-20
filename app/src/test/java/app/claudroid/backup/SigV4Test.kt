package app.claudroid.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.time.Instant

/** AWS Signature Version 4 details from docs/BACKUP.md §5.2 and the official vectors. */
class SigV4Test {

    @Test
    fun `signing key matches the official AWS example vector`() {
        assertEquals(
            "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9",
            SigV4.signingKeyHex("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20150830", "us-east-1", "iam"),
        )
    }

    @Test
    fun `canonical uri encodes per segment and keeps slashes only as separators`() {
        assertEquals("/", SigV4.canonicalUri(null))
        assertEquals("/", SigV4.canonicalUri(""))
        assertEquals("/~x/-_.", SigV4.canonicalUri("/~x/-_."))
        assertEquals("/a%20b/%C3%BC", SigV4.canonicalUri("/a b/ü"))
        // Already encoded input is normalized to the AWS alphabet, not double-encoded.
        assertEquals("/a%20b/%C3%BC", SigV4.canonicalUri("/a%20b/%C3%BC"))
        // An encoded slash belongs to the segment and must stay encoded; lowercase
        // escapes are normalized to uppercase.
        assertEquals("/a%2Fb", SigV4.canonicalUri("/a%2Fb"))
        assertEquals("/a%2Fb", SigV4.canonicalUri("/a%2fb"))
    }

    @Test
    fun `uri encode keeps only unreserved characters`() {
        assertEquals("a%20b%2F%E7%9B%AE%E5%BD%95", SigV4.encodeSegment("a b/目录"))
        assertEquals("a-b_c.d~e", SigV4.encodeSegment("a-b_c.d~e"))
        assertEquals("A%2BB%3D%3D", SigV4.encodeSegment("A+B=="))
        assertEquals("a/b/c", SigV4.encodePath("a/b/c"))
        assertEquals("a%20b/c", SigV4.encodePath("a b/c"))
    }

    @Test
    fun `host header keeps non default ports`() {
        assertEquals("example.com", SigV4.hostHeader(URL("https://example.com/x")))
        assertEquals("example.com", SigV4.hostHeader(URL("https://example.com:443/x")))
        assertEquals("example.com:8443", SigV4.hostHeader(URL("https://example.com:8443/x")))
        assertEquals("127.0.0.1:9000", SigV4.hostHeader(URL("http://127.0.0.1:9000/b")))
    }

    @Test
    fun `signed request uses the documented header set and scope`() {
        val signed = SigV4.sign(
            method = "GET",
            url = URL("https://example.amazonaws.com/"),
            payloadHash = SigV4.EMPTY_SHA256,
            accessKey = "test-access-key",
            secretKey = "secret",
            region = "us-east-1",
            at = Instant.parse("2015-08-30T12:36:00Z"),
        )

        assertEquals(listOf("host", "x-amz-content-sha256", "x-amz-date"), signed.signedHeaders)
        assertEquals("20150830T123600Z", signed.amzDate)
        assertTrue(
            signed.authorization.startsWith("${SigV4.ALGORITHM} Credential=test-access-key/"),
        )
        assertTrue(
            signed.authorization.contains(
                "/20150830/us-east-1/s3/aws4_request, " +
                    "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=",
            ),
        )
        assertTrue(signed.canonicalRequest.startsWith("GET\n/\n\nhost:example.amazonaws.com\n"))
        assertTrue(signed.stringToSign.startsWith("${SigV4.ALGORITHM}\n20150830T123600Z\n"))
    }

    @Test
    fun `empty payload hash is the sha256 of zero bytes`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SigV4.EMPTY_SHA256)
        assertEquals(SigV4.EMPTY_SHA256, SigV4.sha256Hex(""))
    }
}

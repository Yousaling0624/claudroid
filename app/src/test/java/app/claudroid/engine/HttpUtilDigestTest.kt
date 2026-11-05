package app.claudroid.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class HttpUtilDigestTest {

    private val dir = Files.createTempDirectory("claudroid-digest").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `sha256 hex matches the oci content address format`() {
        val file = File(dir, "abc.bin").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HttpUtil.sha256Hex(file),
        )
    }

    @Test
    fun `sha512 base64 matches the npm integrity payload`() {
        val file = File(dir, "abc.bin").apply { writeText("abc") }
        val hex = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
        val expected = Base64.getEncoder().encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        assertEquals(expected, HttpUtil.sha512Base64(file))
    }

    @Test
    fun `digestHex accepts only a well formed sha256`() {
        val hex = "A".repeat(64)
        assertEquals("a".repeat(64), HttpUtil.digestHex("sha256:$hex"))
        assertEquals("a".repeat(64), HttpUtil.digestHex("sha256:${"a".repeat(64)}"))
        assertEquals("", HttpUtil.digestHex(""))
        assertEquals("", HttpUtil.digestHex("sha256:short"))
        assertEquals("", HttpUtil.digestHex("sha256:${"z".repeat(64)}"))
        assertEquals("", HttpUtil.digestHex("md5:${"a".repeat(64)}"))
    }

    @Test
    fun `integrity comparison ignores the algorithm prefix and case`() {
        val computed = "AbC+/="
        assertTrue(HttpUtil.matchesIntegrity(computed, "sha512-$computed"))
        assertTrue(HttpUtil.matchesIntegrity(computed, "sha512-abc+/="))
        assertFalse(HttpUtil.matchesIntegrity(computed, "sha512-other"))
    }

    /**
     * Fail-closed: a registry response without `dist.integrity` (proxy rewrite, truncated
     * JSON) gives us nothing to verify the ~150 MB CLI binary against. Accepting it would
     * let an unverified ELF into the jail, so the comparison must refuse.
     */
    @Test
    fun `missing or blank integrity is refused`() {
        val computed = "AbC+/="
        assertFalse(HttpUtil.matchesIntegrity(computed, null))
        assertFalse(HttpUtil.matchesIntegrity(computed, ""))
        assertFalse(HttpUtil.matchesIntegrity(computed, "   "))
        assertFalse(HttpUtil.matchesIntegrity(computed, "sha512-"))
        assertFalse(HttpUtil.matchesIntegrity(computed, "sha512-   "))
        // An empty digest must not match an absent one either.
        assertFalse(HttpUtil.matchesIntegrity("", null))
        assertFalse(HttpUtil.matchesIntegrity("", "sha512-"))
    }
}

package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Optional integration check against a real npm artifact. Enable with
 *
 *   CLAUDROID_TARBALL=/path/to/claude-code-linux-arm64-musl-<ver>.tgz ./gradlew testDebugUnitTest
 *
 * (download the tarball first via tools/fetch-cli.sh). Verifies that our own
 * tar reader and integrity check work on the 150 MB shipping artifact, not just fixtures.
 */
class RealTarballTest {

    @Test
    fun `extracts the shipping claude binary from the real npm tarball`() {
        val path = System.getenv("CLAUDROID_TARBALL")
        assumeTrue("CLAUDROID_TARBALL not set - skipping integration check", path != null)
        val archive = File(path!!)
        assumeTrue("tarball missing: $path", archive.isFile)

        val out = File.createTempFile("claude-real", ".bin")
        try {
            assertTrue("extractEntry(package/claude) failed", TarGz.extractEntry(archive, "package/claude", out))
            assertTrue("binary is suspiciously small: ${out.length()}", out.length() > 50L * 1024 * 1024)

            val magic = ByteArray(4)
            out.inputStream().use { it.read(magic) }
            assertEquals(0x7f, magic[0].toInt() and 0xFF)
            assertEquals('E'.code, magic[1].toInt() and 0xFF)
            assertEquals('L'.code, magic[2].toInt() and 0xFF)
            assertEquals('F'.code, magic[3].toInt() and 0xFF)
            println("real tarball ok: package/claude = ${out.length()} bytes, ELF header present")
        } finally {
            out.delete()
        }
    }

    @Test
    fun `sha512 of the real tarball matches the registry integrity field`() {
        val path = System.getenv("CLAUDROID_TARBALL")
        assumeTrue("CLAUDROID_TARBALL not set - skipping integration check", path != null)
        val archive = File(path!!)
        assumeTrue("tarball missing: $path", archive.isFile)

        val digest = sha512Base64(archive)
        val expected = System.getenv("CLAUDROID_INTEGRITY")
        assumeTrue("CLAUDROID_INTEGRITY not set - skipping", expected != null)
        assertTrue("integrity mismatch", HttpUtil.matchesIntegrity(digest, expected))
        println("sha512 verified against registry integrity: ${expected!!.take(24)}...")
    }

    private fun sha512Base64(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-512")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return java.util.Base64.getEncoder().encodeToString(digest.digest())
    }
}

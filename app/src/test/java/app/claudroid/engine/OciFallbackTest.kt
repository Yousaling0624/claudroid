package app.claudroid.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The rootfs pull tries Docker Hub first and a mirror second, once each. On networks
 * where `auth.docker.io` is poisoned the first attempt hangs, so the exact ordering and
 * the "delete partial bytes before moving on" behaviour are what make the fallback work.
 */
class OciFallbackTest {

    private val dir = Files.createTempDirectory("claudroid-oci").toFile()
    private val dest = File(dir, "debian.tar.gz")

    private val good = ByteArray(4096) { (it % 251).toByte() }
    private val expected = run {
        val probe = File(dir, "expected.bin")
        probe.writeBytes(good)
        HttpUtil.sha256Hex(probe)
    }

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val official = Registry.OCI_SOURCES[0]
    private val mirror = Registry.OCI_SOURCES[1]

    @Test
    fun `the official source wins and the mirror is not touched`() {
        val calls = mutableListOf<String>()

        val result = HttpUtil.firstVerifiedSource(Registry.OCI_SOURCES, expected, dest) { source ->
            calls += source.name
            dest.writeBytes(good)
        }

        assertEquals(listOf(official.name), calls)
        assertEquals(official, result.source)
        assertEquals(expected, result.sha256)
        assertTrue(dest.readBytes().contentEquals(good))
    }

    @Test
    fun `a dead official source falls through to the mirror once`() {
        val calls = mutableListOf<String>()

        val result = HttpUtil.firstVerifiedSource(Registry.OCI_SOURCES, expected, dest) { source ->
            calls += source.name
            if (source == official) {
                // A poisoned DNS answer leaves a partial body behind when it times out.
                dest.writeBytes(ByteArray(11))
                throw IOException("connect timed out")
            }
            dest.writeBytes(good)
        }

        assertEquals(listOf(official.name, mirror.name), calls)
        assertEquals(mirror, result.source)
        assertTrue(dest.readBytes().contentEquals(good))
    }

    @Test
    fun `a source serving different bytes is rejected and replaced`() {
        val calls = mutableListOf<String>()

        val result = HttpUtil.firstVerifiedSource(Registry.OCI_SOURCES, expected, dest) { source ->
            calls += source.name
            if (source == official) {
                dest.writeBytes("tampered".toByteArray())
            } else {
                dest.writeBytes(good)
            }
        }

        assertEquals(listOf(official.name, mirror.name), calls)
        assertEquals(mirror, result.source)
        assertTrue(dest.readBytes().contentEquals(good))
    }

    @Test
    fun `when every source fails no partial file survives`() {
        val calls = mutableListOf<String>()

        try {
            HttpUtil.firstVerifiedSource(Registry.OCI_SOURCES, expected, dest) { source ->
                calls += source.name
                dest.writeBytes(ByteArray(7))
                throw IOException("unreachable: ${source.name}")
            }
            fail("expected both sources to fail")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains(mirror.name))
        }

        assertEquals(listOf(official.name, mirror.name), calls)
        assertFalse(dest.exists())
    }
}

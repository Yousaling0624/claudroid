package app.claudroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the cache retention contract for `share.image` (docs/BRIDGE_CONTRACT.md §6.4).
 * The Android-facing intent plumbing is deliberately not tested here; the rules that
 * could silently let `cacheDir/share/` grow forever are all in [ShareFiles].
 */
class ShareFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `new image files are timestamped pngs in the given directory`() {
        val dir = temp.newFolder("share")

        val file = ShareFiles.newImageFile(dir, 1_700_000_000_000)

        assertEquals("claudroid-1700000000000.png", file.name)
        assertEquals(dir, file.parentFile)
    }

    @Test
    fun `prune deletes share images older than the age limit`() {
        val dir = temp.newFolder("share")
        val now = System.currentTimeMillis()
        val expired = File(dir, "claudroid-1.png").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - 25L * 60 * 60 * 1000)
        }
        val fresh = File(dir, "claudroid-2.png").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(now - 60L * 60 * 1000)
        }

        ShareFiles.prune(dir, now)

        assertFalse(expired.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `prune keeps only the newest files`() {
        val dir = temp.newFolder("share")
        val now = System.currentTimeMillis()
        (1..12).forEach { i ->
            File(dir, "claudroid-$i.png").apply {
                writeBytes(byteArrayOf(i.toByte()))
                // 1 is the newest, 12 the oldest; all are inside the age window.
                setLastModified(now - i * 60_000L)
            }
        }

        ShareFiles.prune(dir, now, keep = 10)

        val names = dir.list().orEmpty().toSet()
        assertEquals(10, names.size)
        (1..10).forEach { assertTrue("claudroid-$it.png", names.contains("claudroid-$it.png")) }
        (11..12).forEach { assertFalse("claudroid-$it.png", names.contains("claudroid-$it.png")) }
    }

    @Test
    fun `prune ignores files that are not share images`() {
        val dir = temp.newFolder("share")
        val now = System.currentTimeMillis()
        val others = listOf("other.png", "claudroid-1.jpg", "notes.txt").map { name ->
            File(dir, name).apply {
                writeBytes(byteArrayOf(1))
                setLastModified(now - 48L * 60 * 60 * 1000)
            }
        }
        val share = File(dir, "claudroid-1.png").apply { writeBytes(byteArrayOf(2)) }

        ShareFiles.prune(dir, now, keep = 0)

        others.forEach { assertTrue(it.name, it.exists()) }
        assertFalse(share.exists())
    }

    @Test
    fun `prune on a missing directory is a no-op`() {
        ShareFiles.prune(File(temp.root, "missing"), now = System.currentTimeMillis())
    }

    @Test
    fun `limits match the bridge contract`() {
        assertEquals(24 * 1024 * 1024, ShareFiles.MAX_IMAGE_BYTES)
        assertEquals(200_000, ShareFiles.MAX_TEXT_CHARS)
    }
}

package app.claudroid.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

/**
 * Pins the cache retention contract for `attachments.capture`
 * (docs/BRIDGE_CONTRACT.md §6.4). The camera intent itself needs a device; the
 * rules that could silently let `cacheDir/capture/` grow are all in [CaptureFiles].
 */
class CaptureFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `new photo files are timestamped jpgs in the given directory`() {
        val dir = temp.newFolder("capture")

        val file = CaptureFiles.newPhotoFile(
            dir,
            1_700_000_000_000,
            TimeZone.getTimeZone("UTC"),
        )

        assertEquals("IMG_20231114_221320.jpg", file.name)
        assertEquals(dir, file.parentFile)
    }

    @Test
    fun `prune deletes photos older than the age limit`() {
        val dir = temp.newFolder("capture")
        val now = System.currentTimeMillis()
        val expired = File(dir, "IMG_1.jpg").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - 25L * 60 * 60 * 1000)
        }
        val fresh = File(dir, "IMG_2.jpg").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(now - 60L * 60 * 1000)
        }

        CaptureFiles.prune(dir, now)

        assertFalse(expired.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `prune keeps only the newest photos`() {
        val dir = temp.newFolder("capture")
        val now = System.currentTimeMillis()
        (1..12).forEach { i ->
            File(dir, "IMG_$i.jpg").apply {
                writeBytes(byteArrayOf(i.toByte()))
                // 1 is the newest, 12 the oldest; all are inside the age window.
                setLastModified(now - i * 60_000L)
            }
        }

        CaptureFiles.prune(dir, now, keep = 10)

        val names = dir.list().orEmpty().toSet()
        assertEquals(10, names.size)
        (1..10).forEach { assertTrue("IMG_$it.jpg", names.contains("IMG_$it.jpg")) }
        (11..12).forEach { assertFalse("IMG_$it.jpg", names.contains("IMG_$it.jpg")) }
    }

    @Test
    fun `prune ignores files that are not camera photos`() {
        val dir = temp.newFolder("capture")
        val now = System.currentTimeMillis()
        val others = listOf("photo.jpg", "IMG_1.png", "notes.txt").map { name ->
            File(dir, name).apply {
                writeBytes(byteArrayOf(1))
                setLastModified(now - 48L * 60 * 60 * 1000)
            }
        }
        val shot = File(dir, "IMG_1.jpg").apply { writeBytes(byteArrayOf(2)) }

        CaptureFiles.prune(dir, now, keep = 0)

        others.forEach { assertTrue(it.name, it.exists()) }
        assertFalse(shot.exists())
    }

    @Test
    fun `prune on a missing directory is a no-op`() {
        CaptureFiles.prune(File(temp.root, "missing"), now = System.currentTimeMillis())
    }
}

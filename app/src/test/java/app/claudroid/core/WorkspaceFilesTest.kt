package app.claudroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `directories come first then files newest first`() {
        val dir = temp.newFolder("workspace")
        File(dir, "zeta").mkdirs()
        File(dir, "alpha").mkdirs()
        val older = File(dir, "older.md").apply { writeText("# old") }
        val newer = File(dir, "newer.txt").apply { writeText("new") }
        older.setLastModified(1_000)
        newer.setLastModified(9_000)

        val names = WorkspaceFiles.list(dir).map { it.name }
        assertEquals(listOf("alpha", "zeta", "newer.txt", "older.md"), names)
    }

    @Test
    fun `classifies extensions case-insensitively`() {
        assertEquals(FileKind.IMAGE, WorkspaceFiles.kindOf(File("photo.JPG")))
        assertEquals(FileKind.IMAGE, WorkspaceFiles.kindOf(File("shot.webp")))
        assertEquals(FileKind.MARKDOWN, WorkspaceFiles.kindOf(File("report.MD")))
        assertEquals(FileKind.HTML, WorkspaceFiles.kindOf(File("page.html")))
        assertEquals(FileKind.HTML, WorkspaceFiles.kindOf(File("page.HTM")))
        assertEquals(FileKind.TEXT, WorkspaceFiles.kindOf(File("script.sh")))
        assertEquals(FileKind.TEXT, WorkspaceFiles.kindOf(File("Makefile")))
        assertEquals(FileKind.ARCHIVE, WorkspaceFiles.kindOf(File("logs.zip")))
        assertEquals(FileKind.OTHER, WorkspaceFiles.kindOf(File("model.bin")))
    }

    @Test
    fun `reads utf8 text and reports truncation`() {
        val file = File(temp.root, "note.md").apply { writeText("你好，工作区\n第二行") }
        val preview = WorkspaceFiles.readPreview(file)!!
        assertEquals("你好，工作区\n第二行", preview.text)
        assertFalse(preview.truncated)

        val limited = WorkspaceFiles.readPreview(file, maxBytes = 4)!!
        assertTrue(limited.truncated)
        assertEquals(4L, limited.bytesRead)
    }

    @Test
    fun `binary files are refused rather than rendered as garbage`() {
        val file = File(temp.root, "blob.bin").apply {
            writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x00, 0x01, 0x02))
        }
        assertNull(WorkspaceFiles.readPreview(file))
        assertNull(WorkspaceFiles.readPreview(File(temp.root, "missing.txt")))
    }

    @Test
    fun `path containment rejects traversal`() {
        val root = temp.newFolder("root")
        val inside = File(root, "sub/file.txt").apply { parentFile?.mkdirs(); writeText("ok") }
        assertTrue(WorkspaceFiles.isInside(root, inside))
        assertTrue(WorkspaceFiles.isInside(root, root))
        assertFalse(WorkspaceFiles.isInside(root, File(root, "../escape.txt")))
    }

    @Test
    fun `mime types and sizes are human readable`() {
        val png = WorkspaceEntry(File("a.png"), "a.png", FileKind.IMAGE, 0, 0)
        assertEquals("image/png", WorkspaceFiles.mimeOf(png))
        val markdown = WorkspaceEntry(File("a.md"), "a.md", FileKind.MARKDOWN, 0, 0)
        assertEquals("text/markdown", WorkspaceFiles.mimeOf(markdown))
        assertEquals("512 B", WorkspaceFiles.humanSize(512))
        assertEquals("1.5 KB", WorkspaceFiles.humanSize(1536))
        assertEquals("2.0 MB", WorkspaceFiles.humanSize(2L * 1024 * 1024))
    }
}

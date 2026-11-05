package app.claudroid.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPOutputStream

/**
 * The Debian rootfs is unpacked with [TarGz.extractAll]. Its symlink/hardlink handling is
 * what makes usrmerge (`/bin -> usr/bin`) and `/etc/alternatives` work, so it is covered
 * here separately from the single-entry npm tarball tests.
 */
class TarGzExtractAllTest {

    private val dir = Files.createTempDirectory("claudroid-tar").toFile()
    private val archive = File(dir, "rootfs.tar.gz")
    private val dest = File(dir, "jail")

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `extracts directories, files and reports progress`() {
        val payload = ByteArray(1500) { (it % 251).toByte() }
        writeTarGz(
            archive,
            listOf(
                TarItem(name = "etc/", type = '5', mode = "0000755"),
                TarItem(name = "etc/debian_version", data = "13.0\n".toByteArray()),
                TarItem(name = "usr/bin/tool", data = payload, mode = "0000755"),
            ),
        )
        val progress = mutableListOf<Long>()

        val count = TarGz.extractAll(archive, dest) { read, _ -> progress += read }

        assertEquals(3, count)
        assertEquals("13.0\n", File(dest, "etc/debian_version").readText())
        assertTrue(payload.contentEquals(File(dest, "usr/bin/tool").readBytes()))
        assertEquals(listOf(5L, 5L + 1500L), progress)
    }

    @Test
    fun `hardlinks resolve after the target even when the link comes first`() {
        writeTarGz(
            archive,
            listOf(
                TarItem(name = "bin/tool", type = '1', link = "usr/bin/tool"),
                TarItem(name = "usr/bin/tool", data = "#!/bin/sh\necho hi\n".toByteArray(), mode = "0000755"),
            ),
        )

        TarGz.extractAll(archive, dest)

        val link = File(dest, "bin/tool")
        assertTrue(link.isFile)
        assertEquals("#!/bin/sh\necho hi\n", link.readText())
    }

    @Test
    fun `symlinks keep their target verbatim`() {
        // Windows needs Developer Mode / SeCreateSymbolicLinkPrivilege. The extraction
        // logic is platform-independent; skip only when the platform cannot create links.
        val probe = File(dir, "probe")
        val canSymlink = runCatching {
            Files.createSymbolicLink(probe.toPath(), Paths.get("target"))
        }.isSuccess
        Assume.assumeTrue("platform cannot create symlinks", canSymlink)

        writeTarGz(
            archive,
            listOf(
                TarItem(name = "etc/alternatives/awk", type = '2', link = "/usr/bin/mawk"),
                TarItem(name = "usr/bin/mawk", data = "binary".toByteArray(), mode = "0000755"),
            ),
        )

        TarGz.extractAll(archive, dest)

        val link = File(dest, "etc/alternatives/awk")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(
            Paths.get("/usr/bin/mawk").toString(),
            Files.readSymbolicLink(link.toPath()).toString(),
        )
    }

    @Test
    fun `a dot dot entry cannot escape the extraction root`() {
        val escaped = "escape-${System.nanoTime()}.txt"
        writeTarGz(archive, listOf(TarItem(name = "../$escaped", data = "boom".toByteArray())))

        try {
            TarGz.extractAll(archive, dest)
            fail("expected the traversal entry to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("unsafe tar"))
        }
        assertFalse(File(dir.parentFile, escaped).exists())
    }

    /**
     * usrmerge makes `bin` a symlink to `usr/bin`. `File.isDirectory` follows symlinks,
     * so replacing `bin` with a plain file via `deleteRecursively` would have deleted
     * `usr/bin`'s contents — i.e. every binary in the rootfs — instead of the link.
     */
    @Test
    fun `replacing a symlink with a file unlinks only the link`() {
        assumeSymlinks()
        dest.mkdirs()
        File(dest, "usr/bin").mkdirs()
        File(dest, "usr/bin/tool").writeText("keep me")
        Files.createSymbolicLink(File(dest, "bin").toPath(), Paths.get("usr/bin"))

        writeTarGz(archive, listOf(TarItem(name = "bin", data = "not a link".toByteArray())))

        TarGz.extractAll(archive, dest)

        assertFalse(Files.isSymbolicLink(File(dest, "bin").toPath()))
        assertEquals("not a link", File(dest, "bin").readText())
        assertEquals("keep me", File(dest, "usr/bin/tool").readText())
    }

    /** Same hazard for a symlink replacing a symlink: the old target must stay intact. */
    @Test
    fun `replacing a symlink with another symlink unlinks only the old link`() {
        assumeSymlinks()
        dest.mkdirs()
        File(dest, "usr/bin").mkdirs()
        File(dest, "usr/bin/tool").writeText("keep me")
        Files.createSymbolicLink(File(dest, "bin").toPath(), Paths.get("usr/bin"))

        writeTarGz(archive, listOf(TarItem(name = "bin", type = '2', link = "usr/local/bin")))

        TarGz.extractAll(archive, dest)

        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()))
        // Paths.get normalizes the separator: the target is wrapped in File(...) by
        // extractAll, which on Windows turns `/` into `\`.
        assertEquals(
            Paths.get("usr/local/bin").toString(),
            Files.readSymbolicLink(File(dest, "bin").toPath()).toString(),
        )
        assertEquals("keep me", File(dest, "usr/bin/tool").readText())
    }

    /**
     * A tarball whose stream ends before the size its header declared is truncated (a
     * broken download). The old code silently kept the short file, and the CLI later died
     * with SIGBUS far from the cause.
     */
    @Test
    fun `a stream that ends inside an entry is reported as truncated`() {
        GZIPOutputStream(FileOutputStream(archive)).use { gz ->
            val header = ByteArray(512)
            put(header, 0, "usr/bin/tool")
            put(header, 100, "0000755")
            put(header, 124, java.lang.Long.toOctalString(1000L).padStart(11, '0'))
            header[156] = '0'.code.toByte()
            put(header, 257, "ustar")
            put(header, 263, "00")
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var sum = 0
            header.forEach { sum += (it.toInt() and 0xFF) }
            put(header, 148, java.lang.Long.toOctalString(sum.toLong()).padStart(6, '0'))
            header[154] = 0
            header[155] = ' '.code.toByte()
            gz.write(header)
            gz.write(ByteArray(300)) // header says 1000 bytes, stream ends here
        }

        try {
            TarGz.extractAll(archive, dest)
            fail("expected the truncated stream to be rejected")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("tar 流被截断"))
        }
    }

    private fun assumeSymlinks() {
        // Windows needs Developer Mode / SeCreateSymbolicLinkPrivilege.
        val probe = File(dir, "probe-${System.nanoTime()}")
        val canSymlink = runCatching {
            Files.createSymbolicLink(probe.toPath(), Paths.get("target"))
        }.isSuccess
        Assume.assumeTrue("platform cannot create symlinks", canSymlink)
    }

    private data class TarItem(
        val name: String,
        val type: Char = '0',
        val data: ByteArray = ByteArray(0),
        val link: String = "",
        val mode: String = "0000644",
    )

    /** Minimal ustar writer, enough to exercise extractAll. */
    private fun writeTarGz(out: File, items: List<TarItem>) {
        GZIPOutputStream(FileOutputStream(out)).use { gz ->
            items.forEach { item ->
                val header = ByteArray(512)
                put(header, 0, item.name)
                put(header, 100, item.mode)
                put(header, 108, "0000000")
                put(header, 116, "0000000")
                put(header, 124, java.lang.Long.toOctalString(item.data.size.toLong()).padStart(11, '0'))
                put(header, 136, "00000000000")
                header[156] = item.type.code.toByte()
                if (item.link.isNotEmpty()) put(header, 157, item.link)
                put(header, 257, "ustar")
                header[262] = 0
                put(header, 263, "00")
                for (i in 148 until 156) header[i] = ' '.code.toByte()
                var sum = 0
                header.forEach { sum += (it.toInt() and 0xFF) }
                put(header, 148, java.lang.Long.toOctalString(sum.toLong()).padStart(6, '0'))
                header[154] = 0
                header[155] = ' '.code.toByte()
                gz.write(header)
                gz.write(item.data)
                val pad = (512 - item.data.size % 512) % 512
                if (pad > 0) gz.write(ByteArray(pad))
            }
            gz.write(ByteArray(1024))
        }
    }

    private fun put(buf: ByteArray, offset: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, buf, offset, minOf(bytes.size, buf.size - offset))
    }
}

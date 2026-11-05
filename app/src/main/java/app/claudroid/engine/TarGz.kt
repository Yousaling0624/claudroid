package app.claudroid.engine

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream

/**
 * Just enough tar to pull entries out of npm tarballs / Debian rootfs layers (ustar,
 * including the PAX and GNU long-name extensions used by Docker images). Avoiding a
 * dependency here keeps the APK small and the behaviour predictable.
 */
object TarGz {

    data class Entry(val name: String, val size: Long, val type: Char)

    /** Extracts [entryPath] from a .tar.gz into [dest]; returns false when absent. */
    fun extractEntry(archive: File, entryPath: String, dest: File): Boolean {
        GZIPInputStream(archive.inputStream().buffered(1 shl 20)).use { input ->
            while (true) {
                val header = input.readExactly(512) ?: return false
                if (header.all { it == 0.toByte() }) return false
                val fullName = entryName(header)
                val size = readOctal(header, 124, 12)
                val type = header[156].toInt().toChar()

                if (fullName == entryPath && (type == '0' || type == '\u0000')) {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().buffered(256 * 1024).use { out ->
                        copyExactly(input, out, size)
                    }
                    return true
                }
                skipEntry(input, size)
            }
        }
    }

    /** Lists regular-file entries without extracting them (APKs contain symlinks too). */
    fun entries(archive: File): List<Entry> {
        val result = mutableListOf<Entry>()
        GZIPInputStream(archive.inputStream().buffered(1 shl 20)).use { input ->
            while (true) {
                val header = input.readExactly(512) ?: break
                if (header.all { it == 0.toByte() }) break
                val size = readOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                result += Entry(entryName(header), size, type)
                skipEntry(input, size)
            }
        }
        return result
    }

    /**
     * Extracts every regular file for which [select] returns a relative destination.
     * Used to pull a single file out of the official CLI npm tarball (`package/claude`).
     */
    fun extractMatching(archive: File, destRoot: File, select: (Entry) -> String?): List<File> {
        val written = mutableListOf<File>()
        val rootPrefix = destRoot.canonicalPath + File.separator
        GZIPInputStream(archive.inputStream().buffered(1 shl 20)).use { input ->
            while (true) {
                val header = input.readExactly(512) ?: break
                if (header.all { it == 0.toByte() }) break
                val size = readOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val entry = Entry(entryName(header), size, type)
                val relative = if (type == '0' || type == '\u0000') select(entry) else null
                if (relative == null) {
                    skipEntry(input, size)
                    continue
                }
                val dest = File(destRoot, relative)
                require(dest.canonicalPath.startsWith(rootPrefix)) { "unsafe tar entry: ${entry.name}" }
                dest.parentFile?.mkdirs()
                dest.outputStream().buffered(256 * 1024).use { out -> copyExactly(input, out, size) }
                written += dest
            }
        }
        return written
    }

    /**
     * Extracts a whole archive (the Debian rootfs) into [destRoot].
     *
     * Handles directories (mode applied last, so a 0555 parent cannot block its children),
     * regular files, hardlinks (Deferred: the target may appear later in the stream) and
     * symlinks. Absolute symlinks are kept verbatim — the rootfs relies on them, e.g.
     * `/etc/alternatives/awk -> /usr/bin/mawk` and usrmerge's `/bin -> usr/bin`.
     *
     * Every destination is checked against the canonical extraction root, so a malicious
     * archive cannot escape via `..` or a symlinked parent. Special bits (setuid) are
     * applied with `chmod` afterwards because java.nio cannot express them.
     *
     * Returns the number of entries written.
     */
    fun extractAll(
        archive: File,
        destRoot: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Int {
        destRoot.mkdirs()
        val root = destRoot.canonicalFile
        val rootPrefix = root.path + File.separator
        val dirModes = mutableListOf<Pair<File, Int>>()
        val pendingLinks = mutableListOf<Pair<File, String>>()
        var pendingPath: String? = null
        var pendingLink: String? = null
        var count = 0
        var written = 0L

        fun inside(file: File): Boolean {
            val path = runCatching { file.canonicalPath }.getOrElse { return false }
            return path == root.path || path.startsWith(rootPrefix)
        }

        fun resolve(relative: String, kind: String): File {
            val dest = File(root, relative)
            // Check the parent, not the final component: an existing symlink at `dest` is
            // about to be replaced, but a symlinked parent must never escape the root.
            val parent = dest.parentFile?.canonicalPath.orEmpty()
            require(parent == root.path || parent.startsWith(rootPrefix)) { "unsafe tar $kind: $relative" }
            return dest
        }

        fun resolveSource(relative: String): File {
            val source = File(root, relative)
            require(inside(source)) { "unsafe tar hardlink: $relative" }
            return source
        }

        GZIPInputStream(archive.inputStream().buffered(1 shl 20)).use { input ->
            while (true) {
                val header = input.readExactly(512) ?: break
                if (header.all { it == 0.toByte() }) break
                val size = readOctal(header, 124, 12)
                val mode = readOctal(header, 100, 8).toInt()
                val type = header[156].toInt().toChar()
                var name = entryName(header)
                var link = readString(header, 157, 100)

                when (type) {
                    // PAX extended header: applies to the next real entry.
                    'x', 'X' -> {
                        val pax = readPax(input, size)
                        pax["path"]?.let { pendingPath = it }
                        pax["linkpath"]?.let { pendingLink = it }
                        continue
                    }
                    // Global PAX header: nothing in it matters to us.
                    'g' -> {
                        skipEntry(input, size)
                        continue
                    }
                    // GNU long name / long link name.
                    'L' -> {
                        pendingPath = readNamePayload(input, size)
                        continue
                    }
                    'K' -> {
                        pendingLink = readNamePayload(input, size)
                        continue
                    }
                }

                val fullName = (pendingPath ?: name).removePrefix("./")
                val fullLink = (pendingLink ?: link).removePrefix("./")
                pendingPath = null
                pendingLink = null

                val relative = fullName.trimEnd('/')
                if (relative.isEmpty()) {
                    skipEntry(input, size)
                    continue
                }

                when (type) {
                    '5' -> {
                        val dest = resolve(relative, "directory")
                        dest.mkdirs()
                        dirModes += dest to mode
                        skipEntry(input, size)
                        count++
                    }
                    '0', '\u0000', '7' -> {
                        val dest = resolve(relative, "file")
                        dest.parentFile?.mkdirs()
                        if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
                        FileOutputStream(dest).buffered(256 * 1024).use { out ->
                            copyExactly(input, out, size)
                        }
                        applyMode(dest, mode)
                        written += size
                        count++
                        onProgress(written, -1L)
                    }
                    '2' -> {
                        val dest = resolve(relative, "symlink")
                        dest.parentFile?.mkdirs()
                        if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
                        Files.createSymbolicLink(dest.toPath(), File(fullLink).toPath())
                        skipEntry(input, size)
                        count++
                    }
                    '1' -> {
                        // Target may come later in the stream; resolve after the loop.
                        pendingLinks += resolve(relative, "hardlink") to fullLink
                        skipEntry(input, size)
                    }
                    else -> skipEntry(input, size)
                }
            }
        }

        pendingLinks.forEach { (dest, link) ->
            val source = resolveSource(link)
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            runCatching {
                Files.createLink(dest.toPath(), source.toPath())
            }.onFailure {
                // Fallback for filesystems that refuse hardlinks (or an unusual ordering).
                if (source.isFile) {
                    Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    throw it
                }
            }
        }

        // Deepest first: chmod 0555 on a parent would otherwise block the children.
        dirModes.sortedByDescending { it.first.path.length }.forEach { (dir, mode) ->
            runCatching { applyMode(dir, mode) }
        }
        return count
    }

    private fun readPax(input: InputStream, size: Long): Map<String, String> {
        if (size <= 0 || size > 1 shl 20) {
            skipEntry(input, size)
            return emptyMap()
        }
        val bytes = input.readExactly(size.toInt()) ?: ByteArray(0)
        skipExactly(input, ((512 - (size % 512)) % 512))
        val text = String(bytes, StandardCharsets.UTF_8)
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < text.length) {
            val space = text.indexOf(' ', i)
            if (space < 0) break
            val len = text.substring(i, space).toIntOrNull() ?: break
            if (len <= 0 || i + len > text.length) break
            val record = text.substring(space + 1, i + len).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0) result[record.substring(0, eq)] = record.substring(eq + 1)
            i += len
        }
        return result
    }

    private fun readNamePayload(input: InputStream, size: Long): String? {
        if (size <= 0 || size > 1 shl 16) {
            skipEntry(input, size)
            return null
        }
        val bytes = input.readExactly(size.toInt()) ?: return null
        skipExactly(input, ((512 - (size % 512)) % 512))
        return String(bytes, StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
    }

    /**
     * Applies the tar mode. Android's java.nio has no API for setuid/setgid/sticky, so
     * those bits go through /system/bin/chmod; a failure here is not fatal (the bits only
     * matter for a handful of Debian tools, and root mode copies the tree with `cp -a`).
     */
    private fun applyMode(file: File, mode: Int) {
        if (mode == 0) return
        runCatching {
            val perms = PosixFilePermissions.fromString(
                buildString {
                    val bits = listOf(0x100 to 'r', 0x80 to 'w', 0x40 to 'x', 0x20 to 'r',
                        0x10 to 'w', 0x8 to 'x', 0x4 to 'r', 0x2 to 'w', 0x1 to 'x')
                    bits.forEach { (mask, c) -> append(if (mode and mask != 0) c else '-') }
                }
            )
            Files.setPosixFilePermissions(file.toPath(), perms)
        }
        // Kotlin has no octal literals: 07000 = 0xE00, 07777 = 0xFFF.
        val special = mode and 0xE00
        if (special != 0) {
            runCatching {
                ProcessBuilder("/system/bin/chmod", Integer.toOctalString(mode and 0xFFF), file.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }
    }

    private fun entryName(header: ByteArray): String {
        val name = readString(header, 0, 100)
        val prefix = readString(header, 345, 155)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    /** Reads a whole text entry (used for APKINDEX). */
    fun readTextEntry(archive: File, entryPath: String): String? {
        GZIPInputStream(archive.inputStream().buffered(1 shl 20)).use { input ->
            while (true) {
                val header = input.readExactly(512) ?: return null
                if (header.all { it == 0.toByte() }) return null
                val name = readString(header, 0, 100)
                val size = readOctal(header, 124, 12)
                if (name == entryPath) {
                    val bytes = ByteArray(size.toInt())
                    var off = 0
                    while (off < bytes.size) {
                        val n = input.read(bytes, off, bytes.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    return String(bytes, 0, off)
                }
                skipEntry(input, size)
            }
        }
    }

    private fun readString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off)
    }

    private fun readOctal(buf: ByteArray, off: Int, len: Int): Long {
        var value = 0L
        for (i in off until off + len) {
            val c = buf[i].toInt().toChar()
            if (c == ' ' || c == 0.toChar()) {
                if (value > 0) break else continue
            }
            if (c < '0' || c > '7') continue
            value = value * 8 + (c - '0')
        }
        return value
    }

    private fun InputStream.readExactly(n: Int): ByteArray? {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(out, off, n - off)
            if (r <= 0) return if (off == 0) null else out.copyOf(off)
            off += r
        }
        return out
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(256 * 1024)
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        // entries are padded to 512 bytes
        val pad = ((512 - (size % 512)) % 512).toInt()
        skipExactly(input, pad.toLong())
    }

    /** Entries are padded to a 512-byte boundary; skipping the data must skip that padding too. */
    private fun skipEntry(input: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512)
        skipExactly(input, size + pad)
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n <= 0) return
            remaining -= n
        }
    }
}

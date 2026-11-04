package app.claudroid.engine

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Just enough tar to pull one entry out of an npm tarball (ustar, no sparse files).
 * Avoiding a dependency here keeps the APK small and the behaviour predictable.
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
     * Used to pull bash + its Alpine shared libraries out of .apk packages.
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

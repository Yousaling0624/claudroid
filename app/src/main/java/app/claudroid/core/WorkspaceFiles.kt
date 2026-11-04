package app.claudroid.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Everything the workspace browser needs that does not touch Android APIs, so the
 * ordering / classification / preview rules can be pinned by unit tests.
 *
 * The workspace is plain files under filesDir, written by the CLI inside the jail
 * (mapped to /work). Nothing here writes: preview is read-only by design.
 */
enum class FileKind { DIRECTORY, IMAGE, MARKDOWN, HTML, TEXT, ARCHIVE, OTHER }

data class WorkspaceEntry(
    val file: File,
    val name: String,
    val kind: FileKind,
    val size: Long,
    val modifiedAt: Long,
) {
    val isDirectory: Boolean get() = kind == FileKind.DIRECTORY
}

data class TextPreview(
    val text: String,
    /** The file was longer than the preview budget; the UI must say so. */
    val truncated: Boolean,
    val bytesRead: Long,
)

object WorkspaceFiles {

    /** 512 KiB is enough for logs/markdown and still instant on a phone. */
    const val PREVIEW_LIMIT = 512 * 1024
    private const val SNIFF_BYTES = 8 * 1024

    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val archiveExtensions = setOf("zip", "apk", "jar", "tar", "gz", "tgz", "7z", "rar", "xz", "bz2")
    private val textExtensions = setOf(
        "txt", "log", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "conf", "properties",
        "csv", "tsv", "md", "markdown", "css", "scss", "js", "mjs", "ts", "jsx", "tsx",
        "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "sh", "bash", "zsh",
        "sql", "gradle", "dockerfile", "gitignore", "env", "svg",
    )

    private val mimeByExtension = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "zip" to "application/zip",
        "apk" to "application/vnd.android.package-archive",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
    )

    /**
     * Directories first (alphabetical), then files newest-first. The agent tends to
     * produce a file and immediately ask you to look at it, so recency beats A-Z.
     */
    fun list(dir: File): List<WorkspaceEntry> {
        val entries = dir.listFiles().orEmpty().mapNotNull { file ->
            runCatching {
                WorkspaceEntry(
                    file = file,
                    name = file.name,
                    kind = kindOf(file),
                    size = if (file.isDirectory) 0L else file.length(),
                    modifiedAt = file.lastModified(),
                )
            }.getOrNull()
        }
        val (directories, files) = entries.partition { it.isDirectory }
        return directories.sortedBy { it.name.lowercase(Locale.ROOT) } +
            files.sortedWith(
                compareByDescending<WorkspaceEntry> { it.modifiedAt }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }

    fun kindOf(file: File): FileKind {
        if (file.isDirectory) return FileKind.DIRECTORY
        val name = file.name.lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "")
        return when {
            extension in imageExtensions -> FileKind.IMAGE
            extension in archiveExtensions -> FileKind.ARCHIVE
            extension == "md" || extension == "markdown" -> FileKind.MARKDOWN
            extension == "html" || extension == "htm" -> FileKind.HTML
            extension in textExtensions -> FileKind.TEXT
            // Extensionless shell scripts and files such as `Makefile` are common
            // artifacts; let the sniffer decide instead of refusing to preview them.
            extension.isEmpty() -> FileKind.TEXT
            else -> FileKind.OTHER
        }
    }

    fun mimeOf(entry: WorkspaceEntry): String {
        if (entry.isDirectory) return "inode/directory"
        val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return mimeByExtension[extension]
            ?: when (entry.kind) {
                FileKind.MARKDOWN, FileKind.TEXT, FileKind.HTML -> "text/plain"
                else -> "application/octet-stream"
            }
    }

    /**
     * Reads a text preview, or null when the content looks binary (a NUL byte in the
     * first 8 KiB). Invalid UTF-8 is replaced rather than throwing: a half-written
     * JSON from an interrupted turn should still be readable.
     */
    fun readPreview(file: File, maxBytes: Int = PREVIEW_LIMIT): TextPreview? {
        if (!file.isFile) return null
        val headLength = minOf(file.length(), SNIFF_BYTES.toLong()).toInt()
        if (headLength > 0) {
            val head = ByteArray(headLength)
            val read = file.inputStream().use { input ->
                var offset = 0
                while (offset < headLength) {
                    val n = input.read(head, offset, headLength - offset)
                    if (n <= 0) break
                    offset += n
                }
                offset
            }
            if ((0 until read).any { head[it] == 0.toByte() }) return null
        }

        val length = minOf(file.length(), maxBytes.toLong()).toInt()
        val bytes = ByteArray(length)
        val read = file.inputStream().use { input ->
            var offset = 0
            while (offset < length) {
                val n = input.read(bytes, offset, length - offset)
                if (n <= 0) break
                offset += n
            }
            offset
        }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes, 0, read))
            .toString()
        return TextPreview(
            text = text,
            truncated = file.length() > length,
            bytesRead = read.toLong(),
        )
    }

    /** Relative path with forward slashes, which is also what the CLI sees under /work. */
    fun relativePath(root: File, file: File): String =
        runCatching {
            file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
        }.getOrElse { file.name }

    /** True only when [target] resolves inside [root]; guards `..` navigation. */
    fun isInside(root: File, target: File): Boolean = runCatching {
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        canonicalTarget == canonicalRoot || canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
    }.getOrDefault(false)

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 10L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}

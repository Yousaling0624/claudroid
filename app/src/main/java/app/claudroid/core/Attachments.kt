package app.claudroid.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Files the user picks inside the composer are copied into the workspace, because the
 * CLI runs inside a chroot where `content://` URIs mean nothing. The message then
 * references them by relative path (`@attachments/foo.zip`), which Claude Code resolves
 * against its working directory.
 */
object Attachments {

    private const val MAX_BYTES = 512L * 1024 * 1024

    fun import(context: Context, uri: Uri): Result<File> = runCatching {
        val name = displayName(context, uri).sanitize()
        val dir = File(Paths.workspace(context), "attachments").apply { mkdirs() }
        val target = uniqueTarget(dir, name)
        val input = context.contentResolver.openInputStream(uri)
            ?: error("无法读取该文件")
        input.use { src ->
            target.outputStream().use { out ->
                var copied = 0L
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    copied += n
                    if (copied > MAX_BYTES) error("文件超过 512MB 上限")
                    out.write(buf, 0, n)
                }
            }
        }
        target
    }

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getString(idx)
                    if (!value.isNullOrBlank()) return value
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Path relative to the workspace, as the CLI should see it. */
    fun relativePath(context: Context, file: File): String {
        val root = Paths.workspace(context).absolutePath
        return file.absolutePath.removePrefix(root).trimStart('/')
    }

    private fun uniqueTarget(dir: File, name: String): File {
        val base = File(dir, name)
        if (!base.exists()) return base
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val suffix = ext.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
        return File(dir, "$stem-${UUID.randomUUID().toString().take(6)}$suffix")
    }

    private fun String.sanitize(): String {
        val cleaned = replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_").trim('_')
        return cleaned.ifBlank { "file" }.take(80)
    }
}

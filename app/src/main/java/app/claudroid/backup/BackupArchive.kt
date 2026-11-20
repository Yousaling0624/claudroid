package app.claudroid.backup

import app.claudroid.core.WorkspaceStore
import app.claudroid.data.AppSettings
import app.claudroid.data.ChatSession
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Malformed or unsafe archive; the message is safe to show to the user. */
class BackupFormatException(message: String) : Exception(message)

/**
 * Builds and applies the ZIP described by docs/BACKUP.md §2–§3.
 *
 * All roots are injected as [File]s, so this class is plain JVM code and is fully
 * covered by unit tests. Restoring is a merge: an existing file is never deleted and
 * is only replaced when it is a session whose archived `updatedAt` is newer.
 */
class BackupArchive(
    private val sessionsDir: File,
    private val defaultWorkspaceDir: File,
    private val workspacesRootDir: File,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // ---------------------------------------------------------------- writing

    /**
     * Writes [out] and returns its manifest. Entries are written in a fixed order and
     * `manifest.json` last, so an interrupted upload never leaves a manifest claiming
     * files the ZIP does not contain.
     */
    fun write(
        out: File,
        settings: AppSettings,
        index: BackupWorkspaceIndex,
        appVersion: String,
        versionCode: Int,
        cancelCheck: () -> Unit = {},
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): BackupManifest {
        val spaces = index.spaces
            .filter { WorkspaceStore.isValidId(it.id) && it.id != WorkspaceStore.DEFAULT_ID }
            .distinctBy { it.id }
        var skipped = 0

        val workspaceFiles = mutableListOf<ArchiveFile>()
        collect(defaultWorkspaceDir, "workspaces/${WorkspaceStore.DEFAULT_ID}", workspaceFiles) { skipped++ }
        spaces.forEach { space ->
            collect(File(workspacesRootDir, space.id), "workspaces/${space.id}", workspaceFiles) { skipped++ }
        }
        workspaceFiles.sortBy { it.name }

        val sessionFiles = sessionFiles()
        val payloadBytes = workspaceFiles.sumOf { it.size } + sessionFiles.sumOf { it.file.length() }

        val manifest = BackupManifest(
            schema = SCHEMA,
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            versionCode = versionCode,
            sessions = sessionFiles.size,
            // The default workspace always exists; spaces are the custom ones.
            workspaces = 1 + spaces.size,
            files = workspaceFiles.size,
            bytes = payloadBytes,
            skipped = skipped,
        )

        out.parentFile?.mkdirs()
        out.delete()
        var done = 0L
        ZipOutputStream(out.outputStream().buffered(256 * 1024)).use { zip ->
            cancelCheck()
            writeBytes(
                zip,
                SETTINGS_ENTRY,
                BackupWire.settingsJson(sanitize(settings)).toByteArray(StandardCharsets.UTF_8),
            )
            writeBytes(
                zip,
                WORKSPACE_INDEX_ENTRY,
                BackupWire.workspaceIndexJson(
                    BackupWorkspaceIndex(
                        activeId = index.activeId.takeIf { it == WorkspaceStore.DEFAULT_ID || spaces.any { s -> s.id == it } }
                            ?: WorkspaceStore.DEFAULT_ID,
                        spaces = spaces,
                    ),
                ).toByteArray(StandardCharsets.UTF_8),
            )
            workspaceFiles.forEach { file ->
                cancelCheck()
                zip.putNextEntry(ZipEntry(file.name))
                val start = done
                val copied = file.file.inputStream().use { input ->
                    copyStream(input, zip, cancelCheck) { fileDone ->
                        onProgress(start + fileDone, payloadBytes)
                    }
                }
                done = start + copied
                zip.closeEntry()
            }
            sessionFiles.forEach { entry ->
                cancelCheck()
                zip.putNextEntry(ZipEntry(entry.name))
                val start = done
                val copied = entry.file.inputStream().use { input ->
                    copyStream(input, zip, cancelCheck) { fileDone ->
                        onProgress(start + fileDone, payloadBytes)
                    }
                }
                done = start + copied
                zip.closeEntry()
            }
            // Last: a partial archive has no manifest and is rejected on restore.
            writeBytes(zip, MANIFEST_ENTRY, BackupWire.manifestJson(manifest).toByteArray(StandardCharsets.UTF_8))
        }
        onProgress(done, done)
        return manifest
    }

    private data class ArchiveFile(val file: File, val name: String, val size: Long)

    private data class SessionFile(val file: File, val name: String)

    /**
     * Lists regular files under [root]. `Files.walk` does not follow symlinks, so a
     * link is seen as a non-regular entry and skipped (and counted) instead of being
     * followed out of the workspace.
     */
    private fun collect(root: File, prefix: String, out: MutableList<ArchiveFile>, onSkipped: () -> Unit) {
        if (!root.isDirectory) return
        val rootPath = root.toPath()
        Files.walk(rootPath).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == rootPath) continue
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    onSkipped()
                    continue
                }
                val relative = rootPath.relativize(path).toString().replace(File.separatorChar, '/')
                // A literal backslash cannot round-trip through the restore validator.
                if (!isSafeRelativeName(relative)) {
                    onSkipped()
                    continue
                }
                out += ArchiveFile(path.toFile(), "$prefix/$relative", Files.size(path))
            }
        }
    }

    private fun sessionFiles(): List<SessionFile> = sessionsDir.listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".json") && !it.isDirectory }
        .mapNotNull { file ->
            val id = file.name.removeSuffix(".json")
            if (!isValidSessionId(id)) return@mapNotNull null
            if (Files.isSymbolicLink(file.toPath())) return@mapNotNull null
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
            SessionFile(file, "$SESSIONS_PREFIX$id.json")
        }
        .sortedBy { it.name }

    private fun writeBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun copyStream(
        input: InputStream,
        out: OutputStream,
        cancelCheck: () -> Unit,
        onChunk: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        while (true) {
            cancelCheck()
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
            copied += n
            onChunk(copied)
        }
        return copied
    }

    // --------------------------------------------------------------- restore

    fun readManifest(zipFile: File): BackupManifest = ZipFile(zipFile).use { zip ->
        validateEntries(zip)
        manifestFrom(zip)
    }

    /**
     * Merges the archive into the three local roots. The limits are enforced twice:
     * once on the central-directory sizes before anything is written, and again on the
     * bytes actually decompressed, so a forged header cannot bypass the 4 GiB cap.
     */
    fun restore(
        zipFile: File,
        restoreSettings: Boolean,
        localSettings: AppSettings,
        cancelCheck: () -> Unit = {},
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): RestoreOutcome = ZipFile(zipFile).use { zip ->
        val totalBytes = validateEntries(zip)
        val manifest = manifestFrom(zip)

        val archivedSettings = if (restoreSettings) {
            zip.getEntry(SETTINGS_ENTRY)?.let { entry ->
                runCatching {
                    json.decodeFromString(AppSettings.serializer(), readEntryText(zip, entry, MAX_METADATA_BYTES))
                }.getOrNull()
            }
        } else {
            null
        }

        // A malformed index only costs the workspace metadata; files can still merge.
        val restoredSpaces = zip.getEntry(WORKSPACE_INDEX_ENTRY)?.let { entry ->
            runCatching {
                json.decodeFromString(BackupWorkspaceIndex.serializer(), readEntryText(zip, entry, MAX_METADATA_BYTES))
            }.getOrNull()
        }?.spaces.orEmpty()
            .filter { WorkspaceStore.isValidId(it.id) && it.id != WorkspaceStore.DEFAULT_ID }
            .distinctBy { it.id }

        var sessionsAdded = 0
        var sessionsSkipped = 0
        var filesAdded = 0
        var filesSkipped = 0
        var extracted = 0L

        fun accounted(count: Long) {
            extracted += count
            if (extracted > MAX_TOTAL_BYTES) {
                throw BackupFormatException("备份解压内容超过 4 GiB，已中止")
            }
        }

        zip.entries().asSequence().forEach { entry ->
            cancelCheck()
            if (entry.isDirectory) return@forEach
            val name = entry.name
            when {
                name == MANIFEST_ENTRY || name == SETTINGS_ENTRY || name == WORKSPACE_INDEX_ENTRY ->
                    Unit
                name.startsWith(SESSIONS_PREFIX) -> {
                    val id = name.removePrefix(SESSIONS_PREFIX).removeSuffix(".json")
                    val bytes = readSessionBytes(zip, entry, ::accounted) { cancelCheck() }
                    val session = bytes?.let {
                        runCatching {
                            json.decodeFromString(ChatSession.serializer(), it.toString(StandardCharsets.UTF_8))
                        }.getOrNull()
                    }
                    // A file whose internal id disagrees with its name would land under
                    // the wrong id and confuse the session list; skip it as corrupt.
                    if (session == null || session.id != id) {
                        sessionsSkipped++
                        return@forEach
                    }
                    if (restoreSession(id, session, bytes)) sessionsAdded++ else sessionsSkipped++
                }
                name.startsWith(WORKSPACES_PREFIX) -> {
                    val rest = name.removePrefix(WORKSPACES_PREFIX)
                    val slash = rest.indexOf('/')
                    if (slash <= 0 || slash == rest.length - 1) return@forEach
                    val id = rest.substring(0, slash)
                    val relative = rest.substring(slash + 1)
                    val root = if (id == WorkspaceStore.DEFAULT_ID) {
                        defaultWorkspaceDir
                    } else {
                        File(workspacesRootDir, id)
                    }
                    if (restoreWorkspaceFile(zip, entry, root, relative, ::accounted, cancelCheck)) {
                        filesAdded++
                    } else {
                        filesSkipped++
                    }
                }
                else -> Unit // Forward-compatible: ignore unknown roots.
            }
            onProgress(extracted, totalBytes)
        }

        val appliedSettings = archivedSettings?.copy(
            // Secrets never travel in the archive; keep the local ones when applying.
            // networkProxy is in the same class: it may embed proxy credentials.
            apiKey = localSettings.apiKey,
            extraEnv = localSettings.extraEnv,
            networkProxy = localSettings.networkProxy,
        )

        RestoreOutcome(
            sessionsAdded = sessionsAdded,
            sessionsSkipped = sessionsSkipped,
            filesAdded = filesAdded,
            filesSkipped = filesSkipped,
            createdAt = manifest.createdAt,
            restoredSpaces = restoredSpaces,
            settings = appliedSettings,
        )
    }

    private fun manifestFrom(zip: ZipFile): BackupManifest {
        val entry = zip.getEntry(MANIFEST_ENTRY)
            ?: throw BackupFormatException("不是有效的 Claudroid 备份：缺少 manifest.json")
        val manifest = BackupWire.decodeManifest(readEntryText(zip, entry, MAX_METADATA_BYTES))
            ?: throw BackupFormatException("备份清单格式无效或缺少字段")
        if (manifest.schema != SCHEMA) throw BackupFormatException("备份版本不兼容")
        return manifest
    }

    /** Central-directory pre-check; runs before any file is touched. */
    private fun validateEntries(zip: ZipFile): Long {
        checkLimits(zip.size(), 0)
        var declared = 0L
        zip.entries().asSequence().forEach { entry ->
            if (entry.isDirectory) return@forEach
            val name = entry.name
            if (!isSafeEntryName(name)) {
                throw BackupFormatException("备份包含不安全的路径，已中止")
            }
            when {
                // Root metadata is not a workspace file even though index.json shares
                // the prefix; without this case every written archive is rejected.
                name == MANIFEST_ENTRY || name == SETTINGS_ENTRY || name == WORKSPACE_INDEX_ENTRY -> Unit
                name.startsWith(SESSIONS_PREFIX) -> {
                    val file = name.removePrefix(SESSIONS_PREFIX)
                    val id = file.removeSuffix(".json")
                    if (!file.endsWith(".json") || file.contains('/') || !isValidSessionId(id)) {
                        throw BackupFormatException("备份包含不安全的会话路径，已中止")
                    }
                }
                name.startsWith(WORKSPACES_PREFIX) -> {
                    val rest = name.removePrefix(WORKSPACES_PREFIX)
                    val slash = rest.indexOf('/')
                    if (slash <= 0 || slash == rest.length - 1) {
                        throw BackupFormatException("备份包含不安全的工作区路径，已中止")
                    }
                    val id = rest.substring(0, slash)
                    if (id != WorkspaceStore.DEFAULT_ID && !WorkspaceStore.isValidId(id)) {
                        throw BackupFormatException("备份包含不安全的工作区路径，已中止")
                    }
                    val relative = rest.substring(slash + 1)
                    if (!isSafeRelativeName(relative)) {
                        throw BackupFormatException("备份包含不安全的工作区路径，已中止")
                    }
                }
            }
            declared += entry.size.coerceAtLeast(0L)
            if (declared > MAX_TOTAL_BYTES) {
                throw BackupFormatException("备份解压内容超过 4 GiB，已中止")
            }
        }
        return declared
    }

    private fun restoreSession(id: String, session: ChatSession, bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        val target = File(sessionsDir, "$id.json")
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val local = runCatching {
                json.decodeFromString(ChatSession.serializer(), target.readText())
            }.getOrNull()
            // An unreadable local file is left alone: the contract only permits
            // overwriting when we can prove the archive is newer.
            if (local == null || session.updatedAt <= local.updatedAt) return false
        }
        writeAtomically(target, bytes, replace = true)
        return true
    }

    private fun restoreWorkspaceFile(
        zip: ZipFile,
        entry: ZipEntry,
        root: File,
        relative: String,
        account: (Long) -> Unit,
        cancelCheck: () -> Unit,
    ): Boolean {
        val target = safeTarget(root, relative)
            ?: throw BackupFormatException("备份包含不安全的工作区路径，已中止")
        // Existence is checked without following links, so an existing symlink is
        // never replaced (and its target is never written through).
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val parent = target.parentFile ?: throw BackupFormatException("备份包含不安全的工作区路径，已中止")
        parent.mkdirs()
        // Re-check after mkdirs: a symlinked ancestor would resolve outside the root.
        if (!isInside(root, parent)) {
            throw BackupFormatException("备份包含不安全的工作区路径，已中止")
        }
        val temp = File.createTempFile(".restore-", ".part", parent)
        try {
            zip.getInputStream(entry).use { input ->
                temp.outputStream().buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        cancelCheck()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        account(n.toLong())
                        output.write(buffer, 0, n)
                    }
                }
            }
            move(temp, target, replace = false)
            return true
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Reads a session entry, or null when it is too large to be a real transcript. */
    private fun readSessionBytes(
        zip: ZipFile,
        entry: ZipEntry,
        account: (Long) -> Unit,
        cancelCheck: () -> Unit,
    ): ByteArray? {
        if (entry.size > MAX_SESSION_BYTES) return null
        val out = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                cancelCheck()
                val n = input.read(buffer)
                if (n <= 0) break
                account(n.toLong())
                if (out.size().toLong() + n > MAX_SESSION_BYTES) return null
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun readEntryText(zip: ZipFile, entry: ZipEntry, maxBytes: Long): String {
        if (entry.size > maxBytes) throw BackupFormatException("备份中的元数据过大，已拒绝恢复")
        val out = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                if (out.size().toLong() + n > maxBytes) {
                    throw BackupFormatException("备份中的元数据过大，已拒绝恢复")
                }
                out.write(buffer, 0, n)
            }
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun writeAtomically(target: File, bytes: ByteArray, replace: Boolean) {
        target.parentFile?.mkdirs()
        val temp = File.createTempFile(".restore-", ".part", target.parentFile)
        try {
            temp.writeBytes(bytes)
            move(temp, target, replace)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun move(from: File, to: File, replace: Boolean) {
        val options: Array<CopyOption> = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(from.toPath(), to.toPath(), *options)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(
                from.toPath(),
                to.toPath(),
                *(if (replace) arrayOf<CopyOption>(StandardCopyOption.REPLACE_EXISTING) else emptyArray()),
            )
        }
    }

    /**
     * Resolves [relative] under [root] and proves the canonical parent is still inside
     * it. The final component may be a symlink only because callers check existence
     * with NOFOLLOW and never write through it.
     */
    private fun safeTarget(root: File, relative: String): File? {
        if (!isSafeRelativeName(relative)) return null
        val target = File(root, relative)
        if (!isInside(root, target.parentFile ?: root)) return null
        return target
    }

    private fun isInside(root: File, file: File): Boolean {
        val rootPath = runCatching { root.canonicalPath }.getOrElse { return false }
        val path = runCatching { file.canonicalPath }.getOrElse { return false }
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    companion object {
        const val SCHEMA = 1
        const val MANIFEST_ENTRY = "manifest.json"
        const val SETTINGS_ENTRY = "settings.json"
        const val WORKSPACE_INDEX_ENTRY = "workspaces/index.json"
        const val SESSIONS_PREFIX = "sessions/"
        const val WORKSPACES_PREFIX = "workspaces/"

        const val MAX_ENTRIES = 200_000
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

        private const val MAX_METADATA_BYTES = 1L * 1024 * 1024
        private const val MAX_SESSION_BYTES = 64L * 1024 * 1024
        private const val MAX_ENTRY_NAME = 4096

        private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidSessionId(id: String): Boolean = SESSION_ID.matches(id)

        /** Secrets must never enter an archive (BACKUP.md §1). */
        fun sanitize(settings: AppSettings): AppSettings =
            settings.copy(apiKey = "", extraEnv = "", networkProxy = "")

        /**
         * ZIP names must be relative and stay inside their root. Backslashes are
         * rejected even though Linux treats them as normal characters: an archive
         * written on Windows would otherwise smuggle a second path separator.
         */
        fun isSafeEntryName(name: String): Boolean {
            if (name.isEmpty() || name.length > MAX_ENTRY_NAME) return false
            if (name.startsWith("/")) return false
            if ('\\' in name || '\u0000' in name) return false
            return name.split('/').none { it == ".." }
        }

        private fun isSafeRelativeName(relative: String): Boolean =
            relative.isNotEmpty() &&
                !relative.startsWith("/") &&
                '\\' !in relative &&
                '\u0000' !in relative &&
                relative.split('/').none { it == ".." || it.isEmpty() }

        /** Exposed for the "entry count / total size" contract tests. */
        fun checkLimits(entryCount: Int, totalBytes: Long) {
            if (entryCount > MAX_ENTRIES) {
                throw BackupFormatException("备份条目过多（超过 $MAX_ENTRIES 个），已拒绝恢复")
            }
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw BackupFormatException("备份解压内容超过 4 GiB，已中止")
            }
        }
    }
}

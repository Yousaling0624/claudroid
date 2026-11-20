package app.claudroid.backup

import app.claudroid.core.WorkspaceInfo
import app.claudroid.data.AppSettings
import app.claudroid.data.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Pins docs/BACKUP.md §2–§3 and §7: the archive layout, the merge rules, the
 * extraction limits and the Zip Slip defences.
 */
class BackupArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sourceSessions get() = File(temp.root, "source/sessions")
    private val sourceDefault get() = File(temp.root, "source/workspace")
    private val sourceSpaces get() = File(temp.root, "source/workspaces")

    private val destSessions get() = File(temp.root, "dest/sessions")
    private val destDefault get() = File(temp.root, "dest/workspace")
    private val destSpaces get() = File(temp.root, "dest/workspaces")

    private val cache get() = File(temp.root, "cache")

    private lateinit var archive: BackupArchive

    @Before
    fun setUp() {
        listOf(sourceSessions, sourceDefault, sourceSpaces, destSessions, destDefault, destSpaces, cache)
            .forEach { it.mkdirs() }
        archive = BackupArchive(sourceSessions, sourceDefault, sourceSpaces)
    }

    // ------------------------------------------------------------------ write

    @Test
    fun `round trips sessions, both workspace kinds and sanitized settings`() {
        writeSession(sourceSessions, session("s1", 100, "one"))
        writeSession(sourceSessions, session("s2", 200, "two"))
        File(sourceDefault, "a.txt").writeText("alpha")
        File(sourceDefault, "dir").mkdirs()
        File(sourceDefault, "dir/b.txt").writeText("beta")
        File(sourceSpaces, "ws-1").mkdirs()
        File(sourceSpaces, "ws-1/c.txt").writeText("gamma")

        val index = BackupWorkspaceIndex(
            activeId = "ws-1",
            spaces = listOf(WorkspaceInfo("ws-1", "项目", 42)),
        )
        val settings = AppSettings(apiKey = "local-secret", extraEnv = "TOKEN=1", model = "backup-model")
        val zip = File(cache, "backup.zip")
        val manifest = archive.write(zip, settings, index, appVersion = "9.9.9", versionCode = 42)

        assertEquals(BackupArchive.SCHEMA, manifest.schema)
        assertEquals(2, manifest.sessions)
        assertEquals(2, manifest.workspaces)
        assertEquals(3, manifest.files)
        assertEquals(0, manifest.skipped)
        assertTrue(manifest.bytes > 0)
        assertTrue(manifest.createdAt > 0)
        assertEquals("9.9.9", manifest.appVersion)
        assertEquals(42, manifest.versionCode)

        // Secrets never enter the archive: read the stored settings.json verbatim.
        ZipFile(zip).use { z ->
            val stored = z.getInputStream(z.getEntry(BackupArchive.SETTINGS_ENTRY))
                .bufferedReader().use { it.readText() }
            assertFalse(stored.contains("local-secret"))
            assertFalse(stored.contains("TOKEN=1"))
            assertTrue(stored.contains("\"model\":\"backup-model\""))
        }

        val restored = BackupArchive(destSessions, destDefault, destSpaces)
        val outcome = restored.restore(
            zipFile = zip,
            restoreSettings = true,
            localSettings = AppSettings(apiKey = "local-key", extraEnv = "LOCAL=1", model = "local"),
        )

        assertEquals(2, outcome.sessionsAdded)
        assertEquals(0, outcome.sessionsSkipped)
        assertEquals(3, outcome.filesAdded)
        assertEquals(0, outcome.filesSkipped)
        assertEquals(manifest.createdAt, outcome.createdAt)
        assertEquals(listOf("ws-1"), outcome.restoredSpaces.map { it.id })
        assertEquals("项目", outcome.restoredSpaces.single().name)
        assertTrue(File(destSessions, "s1.json").isFile)
        assertEquals("alpha", File(destDefault, "a.txt").readText())
        assertEquals("beta", File(destDefault, "dir/b.txt").readText())
        assertEquals("gamma", File(destSpaces, "ws-1/c.txt").readText())

        val applied = outcome.settings
        assertNotNull("restoreSettings=true must return the merged settings", applied)
        assertEquals("backup-model", applied!!.model)
        // The archive never carries the secrets, so applying it keeps the local ones.
        assertEquals("local-key", applied.apiKey)
        assertEquals("LOCAL=1", applied.extraEnv)
    }

    // ---------------------------------------------------------------- merging

    @Test
    fun `existing files stay untouched and sessions only move forward in time`() {
        writeSession(sourceSessions, session("s1", 100, "backup-old"))
        writeSession(sourceSessions, session("s2", 200, "backup-new"))
        File(sourceDefault, "a.txt").writeText("alpha")
        File(sourceDefault, "dir").mkdirs()
        File(sourceDefault, "dir/b.txt").writeText("beta")
        File(sourceSpaces, "ws-1").mkdirs()
        File(sourceSpaces, "ws-1/c.txt").writeText("gamma")
        val zip = File(cache, "backup.zip")
        archive.write(
            zip,
            AppSettings(),
            BackupWorkspaceIndex(spaces = listOf(WorkspaceInfo("ws-1", "项目"))),
            "1",
            1,
        )

        writeSession(destSessions, session("s1", 200, "local-new"))
        writeSession(destSessions, session("s2", 100, "local-old"))
        File(destDefault, "a.txt").writeText("local")
        File(destDefault, "keep.txt").writeText("keep")

        val restored = BackupArchive(destSessions, destDefault, destSpaces)
        val outcome = restored.restore(zip, restoreSettings = false, localSettings = AppSettings())

        assertEquals(1, outcome.sessionsAdded)
        assertEquals(1, outcome.sessionsSkipped)
        assertEquals(2, outcome.filesAdded)
        assertEquals(1, outcome.filesSkipped)
        assertNull(outcome.settings)

        assertEquals("local-new", readSession(destSessions, "s1").title)
        assertEquals("backup-new", readSession(destSessions, "s2").title)
        assertEquals("local", File(destDefault, "a.txt").readText())
        assertEquals("keep", File(destDefault, "keep.txt").readText())
        assertEquals("beta", File(destDefault, "dir/b.txt").readText())
        assertEquals("gamma", File(destSpaces, "ws-1/c.txt").readText())
    }

    @Test
    fun `corrupt and mismatched session entries are skipped without aborting`() {
        val manifest = BackupWire.manifestJson(manifest())
        val mismatched = ChatSession(id = "other", title = "wrong id", updatedAt = 1)
        val zip = zipFile(
            "bad-sessions.zip",
            BackupArchive.MANIFEST_ENTRY to manifest,
            "sessions/broken.json" to "{not json",
            "sessions/s1.json" to BackupWire.json.encodeToString(ChatSession.serializer(), mismatched),
            "sessions/s2.json" to BackupWire.json.encodeToString(
                ChatSession.serializer(),
                ChatSession(id = "s2", title = "ok", updatedAt = 5),
            ),
        )

        val outcome = BackupArchive(destSessions, destDefault, destSpaces)
            .restore(zip, restoreSettings = false, localSettings = AppSettings())

        assertEquals(1, outcome.sessionsAdded)
        assertEquals(2, outcome.sessionsSkipped)
        assertEquals("ok", readSession(destSessions, "s2").title)
        assertFalse(File(destSessions, "broken.json").exists())
    }

    // --------------------------------------------------------------- zip slip

    @Test
    fun `unsafe entry names abort before anything is written`() {
        val unsafe = listOf(
            "../escape.txt",
            "/absolute.txt",
            "workspaces/default/a\\b.txt",
            "workspaces/default/../../escape.txt",
            "sessions/../escape.json",
            "sessions/nested/escape.json",
            "sessions/escape.txt",
            "workspaces/../escape/file.txt",
        )
        unsafe.forEach { name ->
            val zip = zipFile(
                "unsafe.zip",
                BackupArchive.MANIFEST_ENTRY to BackupWire.manifestJson(manifest()),
                name to "x",
            )
            val ex = assertThrows(name, BackupFormatException::class.java) {
                archive.readManifest(zip)
            }
            assertTrue(name, ex.message!!.isNotBlank())
        }
        assertFalse(File(temp.root, "escape.txt").exists())
        assertFalse(File(destDefault.parentFile, "escape.txt").exists())
        assertFalse(File(destSessions.parentFile, "escape.json").exists())
    }

    @Test
    fun `entry name and limit helpers enforce the contract`() {
        assertTrue(BackupArchive.isSafeEntryName("workspaces/default/a.txt"))
        assertTrue(BackupArchive.isSafeEntryName("sessions/s1.json"))
        assertFalse(BackupArchive.isSafeEntryName(""))
        assertFalse(BackupArchive.isSafeEntryName("../a"))
        assertFalse(BackupArchive.isSafeEntryName("/a"))
        assertFalse(BackupArchive.isSafeEntryName("a/../b"))
        assertFalse(BackupArchive.isSafeEntryName("a\\b"))
        assertFalse(BackupArchive.isSafeEntryName("a\u0000b"))

        assertThrows(BackupFormatException::class.java) {
            BackupArchive.checkLimits(BackupArchive.MAX_ENTRIES + 1, 0)
        }
        assertThrows(BackupFormatException::class.java) {
            BackupArchive.checkLimits(0, BackupArchive.MAX_TOTAL_BYTES + 1)
        }
        BackupArchive.checkLimits(BackupArchive.MAX_ENTRIES, BackupArchive.MAX_TOTAL_BYTES)
    }

    // --------------------------------------------------------------- manifest

    @Test
    fun `manifest schema and required fields are enforced`() {
        val newer = zipFile(
            "schema.zip",
            BackupArchive.MANIFEST_ENTRY to BackupWire.manifestJson(manifest(schema = 2)),
        )
        val incompatible = assertThrows(BackupFormatException::class.java) {
            archive.readManifest(newer)
        }
        assertEquals("备份版本不兼容", incompatible.message)

        val missingFields = zipFile(
            "fields.zip",
            BackupArchive.MANIFEST_ENTRY to """{"schema":1}""",
        )
        val invalid = assertThrows(BackupFormatException::class.java) {
            archive.readManifest(missingFields)
        }
        assertEquals("备份清单格式无效或缺少字段", invalid.message)

        val noManifest = zipFile(
            "no-manifest.zip",
            BackupArchive.SETTINGS_ENTRY to "{}",
        )
        val missing = assertThrows(BackupFormatException::class.java) {
            archive.readManifest(noManifest)
        }
        assertEquals("不是有效的 Claudroid 备份：缺少 manifest.json", missing.message)
    }

    // ---------------------------------------------------------------- helpers

    private fun manifest(schema: Int = 1): BackupManifest = BackupManifest(
        schema = schema,
        createdAt = 1_700_000_000_000L,
        appVersion = "1.0",
        versionCode = 1,
        sessions = 0,
        workspaces = 1,
        files = 0,
        bytes = 0,
        skipped = 0,
    )

    private fun session(id: String, updatedAt: Long, title: String): ChatSession =
        ChatSession(id = id, title = title, createdAt = updatedAt, updatedAt = updatedAt)

    private fun writeSession(dir: File, session: ChatSession) {
        File(dir, "${session.id}.json").writeText(
            BackupWire.json.encodeToString(ChatSession.serializer(), session),
        )
    }

    private fun readSession(dir: File, id: String): ChatSession =
        BackupWire.json.decodeFromString(ChatSession.serializer(), File(dir, "$id.json").readText())

    private fun zipFile(name: String, vararg entries: Pair<String, String>): File {
        val file = File(cache, name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (entryName, text) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}

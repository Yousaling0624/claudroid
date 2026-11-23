package app.claudroid.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemoteHostManagerTest {

    /** hosts.json only holds sealed tokens; the manager tests never need real crypto. */
    private object FakeCipher : SecretCipher {
        override fun seal(plaintext: String): String = "sealed:$plaintext"
        override fun open(sealed: String): String? = sealed.removePrefix("sealed:")
    }

    private fun profile(id: String) = RemoteHostProfile(
        id = id,
        name = "host-$id",
        baseUrl = "https://host-$id.example.com",
        tokenSealed = "sealed-token",
        defaultCwd = "/home/me",
    )

    @Test
    fun `updateHost validates and persists the per-host permission mode`() = runBlocking {
        val dir = Files.createTempDirectory("claudroid-hosts").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = File(dir, "hosts.json")
            RemoteHostStore(file).apply {
                load()
                upsert(profile("a"))
            }
            val manager = RemoteHostManager(file, File(dir, "cache"), scope, FakeCipher)
            manager.load()

            val rejected = runCatching {
                manager.updateHost("a", null, null, null, null, permissionMode = "dontAsk")
            }.exceptionOrNull()
            assertTrue("unknown modes must be rejected", rejected is RemoteException)
            assertEquals("default", manager.host("a")?.permissionMode)

            manager.updateHost("a", null, null, null, null, permissionMode = "acceptEdits")
            assertEquals("acceptEdits", manager.host("a")?.permissionMode)

            val reopened = RemoteHostStore(file)
            reopened.load()
            assertEquals("acceptEdits", reopened.find("a")?.permissionMode)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `auto is a valid per-host permission mode and persists`() = runBlocking {
        val dir = Files.createTempDirectory("claudroid-hosts").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = File(dir, "hosts.json")
            RemoteHostStore(file).apply {
                load()
                upsert(profile("a"))
            }
            val manager = RemoteHostManager(file, File(dir, "cache"), scope, FakeCipher)
            manager.load()

            manager.updateHost("a", null, null, null, null, permissionMode = "auto")
            assertEquals("auto", manager.host("a")?.permissionMode)

            val reopened = RemoteHostStore(file)
            reopened.load()
            assertEquals("auto", reopened.find("a")?.permissionMode)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `auto passes the preflight when the host advertises it`() {
        assertNull(
            RemotePermissionModes.unsupportedReason("nas", "auto", listOf("default", "auto")),
        )
    }

    @Test
    fun `auto is rejected with an upgrade hint when the host omits it`() {
        val reason = RemotePermissionModes.unsupportedReason(
            "旧主机",
            "auto",
            listOf("default", "acceptEdits", "plan"),
        )
        assertNotNull(reason)
        requireNotNull(reason)
        assertTrue("names the host", reason.contains("旧主机"))
        assertTrue("names the mode", reason.contains("自动"))
        assertTrue("includes supported modes", reason.contains("自动改文件"))
        assertTrue("offers the safe fallback", reason.contains("每次确认"))
        assertTrue("tells the user to upgrade", reason.contains("升级 claudroid-bridge"))
    }

    @Test
    fun `an empty capability list never blocks a mode`() {
        assertNull(RemotePermissionModes.unsupportedReason("legacy", "auto", emptyList()))
        assertNull(RemotePermissionModes.unsupportedReason("legacy", "plan", emptyList()))
    }
}

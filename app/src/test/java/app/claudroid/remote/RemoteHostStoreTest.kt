package app.claudroid.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemoteHostStoreTest {

    private fun profile(id: String, token: String = "sealed-token") = RemoteHostProfile(
        id = id,
        name = "host-$id",
        baseUrl = "https://host-$id.example.com",
        tokenSealed = token,
        defaultCwd = "/home/me",
    )

    @Test
    fun `persists hosts, tokens and the default id`() = runBlocking {
        val dir = Files.createTempDirectory("claudroid-hosts").toFile()
        try {
            val file = File(dir, "hosts.json")
            val store = RemoteHostStore(file)
            store.load()
            store.upsert(profile("a"))
            store.upsert(profile("b"))
            store.setDefault("b")
            assertEquals("b", store.state.value.defaultHostId)

            val reopened = RemoteHostStore(file)
            reopened.load()
            assertEquals(2, reopened.state.value.hosts.size)
            assertEquals("b", reopened.state.value.defaultHostId)
            assertEquals("sealed-token", reopened.find("a")?.tokenSealed)
            assertEquals("/home/me", reopened.find("a")?.defaultCwd)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `removing the default picks another host`() = runBlocking {
        val dir = Files.createTempDirectory("claudroid-hosts").toFile()
        try {
            val store = RemoteHostStore(File(dir, "hosts.json"))
            store.load()
            store.upsert(profile("a"))
            store.upsert(profile("b"))
            store.setDefault("a")
            store.remove("a")
            assertEquals(listOf("b"), store.state.value.hosts.map { it.id })
            assertEquals("b", store.state.value.defaultHostId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a corrupt file falls back to an empty list`() = runBlocking {
        val dir = Files.createTempDirectory("claudroid-hosts").toFile()
        try {
            val file = File(dir, "hosts.json")
            file.writeText("{not json")
            val store = RemoteHostStore(file)
            store.load()
            assertTrue(store.state.value.hosts.isEmpty())
            assertEquals("", store.state.value.defaultHostId)
            // The app must still be able to pair again after a corrupt file.
            store.upsert(profile("c"))
            assertEquals(1, store.state.value.hosts.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}

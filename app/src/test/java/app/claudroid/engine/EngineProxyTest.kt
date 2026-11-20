package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure half of [EngineProxy]. The Android-facing [EngineProxy.systemProxy] is
 * deliberately untested here: it depends on ConnectivityManager and never needs a mock
 * to prove the normalization contract.
 */
class EngineProxyTest {

    @Test
    fun `normalize adds http to a bare host and port`() {
        assertEquals("http://127.0.0.1:7890", EngineProxy.normalize("127.0.0.1:7890"))
        assertEquals("http://127.0.0.1:7890", EngineProxy.normalize("  127.0.0.1:7890  "))
    }

    @Test
    fun `normalize drops the path, query and trailing slash`() {
        assertEquals("http://host:8080", EngineProxy.normalize("http://host:8080/"))
        assertEquals("http://host:8080", EngineProxy.normalize("http://host:8080/path?q=1#frag"))
    }

    @Test
    fun `normalize keeps https and userinfo`() {
        assertEquals("https://proxy:8443", EngineProxy.normalize("https://proxy:8443"))
        assertEquals(
            "http://user:secret@proxy.local:3128",
            EngineProxy.normalize("http://user:secret@proxy.local:3128/some/path"),
        )
    }

    @Test
    fun `socks proxies are rejected because claude code cannot use them`() {
        assertNull(EngineProxy.normalize("socks://127.0.0.1:7891"))
        assertNull(EngineProxy.normalize("socks5://127.0.0.1:7891"))
        assertNull(EngineProxy.normalize("socks5h://127.0.0.1:7891"))
    }

    @Test
    fun `blank and malformed values are rejected`() {
        assertNull(EngineProxy.normalize(""))
        assertNull(EngineProxy.normalize("   "))
        assertNull(EngineProxy.normalize("http:///nohost"))
        assertNull(EngineProxy.normalize("http://:8080"))
        assertNull(EngineProxy.normalize("http://host:0"))
        assertNull(EngineProxy.normalize("http://host:70000"))
        assertNull(EngineProxy.normalize("not a url with spaces"))
    }

    @Test
    fun `a proxy without a port uses the scheme default`() {
        assertEquals("http://proxy.local", EngineProxy.normalize("proxy.local"))
        assertEquals("https://proxy.local", EngineProxy.normalize("https://proxy.local"))
    }

    @Test
    fun `env exports both name cases and keeps loopback out of the proxy`() {
        val env = EngineProxy.env(
            "http://10.0.0.2:7890",
            "https://api.anthropic.com",
            apiIsLocal = false,
        )

        listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy").forEach { key ->
            assertEquals(key, "http://10.0.0.2:7890", env[key])
        }
        val noProxy = env.getValue("NO_PROXY")
        assertTrue(noProxy.contains("localhost"))
        assertTrue(noProxy.contains("127.0.0.1"))
        assertTrue(noProxy.contains("::1"))
        // A public API host must go through the proxy; only loopback and local APIs skip it.
        assertFalse(noProxy.contains("api.anthropic.com"))
        assertEquals(noProxy, env.getValue("no_proxy"))
        assertFalse(env.containsKey("ALL_PROXY"))
    }

    @Test
    fun `a local api host is appended to no_proxy`() {
        val ipv4 = EngineProxy.env(
            "http://10.0.0.2:7890",
            "http://192.168.1.9:8080",
            apiIsLocal = true,
        )
        assertTrue(ipv4.getValue("NO_PROXY").contains("192.168.1.9"))

        // java.net.URI reports IPv6 literals with brackets; NO_PROXY expects the bare address.
        val ipv6 = EngineProxy.env(
            "http://10.0.0.2:7890",
            "http://[fd00::1]:11434",
            apiIsLocal = true,
        )
        val noProxy = ipv6.getValue("NO_PROXY")
        assertTrue(noProxy.contains("fd00::1"))
        assertFalse(noProxy.contains("[fd00::1]"))
    }

    @Test
    fun `userinfo reaches the environment verbatim`() {
        val proxy = EngineProxy.normalize("http://user:secret@proxy.local:3128")!!
        val env = EngineProxy.env(proxy, "https://api.anthropic.com", apiIsLocal = false)
        assertEquals("http://user:secret@proxy.local:3128", env["HTTPS_PROXY"])
    }
}

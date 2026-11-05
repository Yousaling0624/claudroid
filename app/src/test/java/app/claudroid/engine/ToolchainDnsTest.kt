package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container's resolver configuration.
 *
 * `api.anthropic.com`'s IPv6 edge answers 403 through the TUN proxy used on real
 * devices, and the CLI treats a non-200 `/api/web/domain_info` as "domain unsafe", so
 * every WebFetch failed. The fix is that the jail never learns an IPv6 nameserver: the
 * proxy's IPv4 fake-IP resolver is the only one whose answers get intercepted.
 */
class ToolchainDnsTest {

    private val ipv6 = listOf("fd17:625c:f037:2::3", "2001:db8::53", "fe80::1%wlan0")

    @Test
    fun `ipv6 nameservers are never written into resolv conf`() {
        val text = Toolchain.dnsLines(ipv6 + listOf("198.18.0.2", "192.168.2.1"))
        // The exact failure seen on device: a colon in a nameserver line means glibc
        // queried the real resolver and the request left the proxy.
        text.lineSequence()
            .filter { it.startsWith("nameserver ") }
            .forEach { assertFalse("IPv6 leaked: $it", it.contains(':')) }
        assertEquals(listOf("198.18.0.2", "192.168.2.1"), nameservers(text).take(2))
    }

    @Test
    fun `network ipv4 servers come first and fallbacks fill the rest`() {
        val text = Toolchain.dnsLines(listOf("198.18.0.2"))
        val servers = nameservers(text)
        assertEquals(listOf("198.18.0.2", "223.5.5.5", "119.29.29.29", "1.1.1.1"), servers)
        // glibc reads at most four; more lines would only look like coverage.
        assertEquals(4, servers.size)
        assertTrue(text.contains("options timeout:2 attempts:3"))
    }

    @Test
    fun `an ipv6 only network still yields a usable ipv4 list`() {
        val text = Toolchain.dnsLines(ipv6)
        val servers = nameservers(text)
        assertEquals(Toolchain.DNS_FALLBACKS, servers)
        servers.forEach { assertTrue(it, Toolchain.isIpv4(it)) }
        assertTrue(text.startsWith("nameserver 223.5.5.5"))
    }

    @Test
    fun `duplicates and non addresses are dropped`() {
        val text = Toolchain.dnsLines(
            listOf("198.18.0.2", " 198.18.0.2 ", "", "not-an-ip", "1.2.3", "256.0.0.1", "10.0.0.1"),
        )
        assertEquals(listOf("198.18.0.2", "10.0.0.1", "223.5.5.5", "119.29.29.29"), nameservers(text))
    }

    @Test
    fun `isIpv4 accepts only a strict dotted quad`() {
        listOf("1.2.3.4", "0.0.0.0", "255.255.255.255", "198.18.0.2")
            .forEach { assertTrue(it, Toolchain.isIpv4(it)) }
        listOf("::1", "fd17:625c::3", "1.2.3", "1.2.3.4.5", "01.2.3.4x", "a.b.c.d", "", " 1.2.3.4", "256.1.1.1")
            .forEach { assertFalse(it, Toolchain.isIpv4(it)) }
    }

    @Test
    fun `gai conf prefers ipv4 as a fallback for getaddrinfo`() {
        // Ordering by DNS alone is not enough for callers that already have both records;
        // this is the safety net, not the primary fix.
        assertTrue(Toolchain.GAI_CONF.contains("precedence ::ffff:0:0/96  100"))
        assertTrue(Toolchain.GAI_CONF.endsWith("\n"))
    }

    @Test
    fun `the root rewrite script writes only etc basenames and reports failure`() {
        val jail = "/data/adb/claudroid"
        val files = linkedMapOf(
            "etc/resolv.conf" to "nameserver 198.18.0.2\noptions timeout:2 attempts:3\n",
            "etc/gai.conf" to Toolchain.GAI_CONF,
        )
        val script = Toolchain.netConfigScript(jail, files)
        assertTrue(script.contains("D=${TurnScripts.quote(jail)}"))
        assertTrue(script.contains("mkdir -p \"\$D/etc\""))
        assertTrue(script.contains("printf '%s' ${TurnScripts.quote(files.getValue("etc/resolv.conf"))} > \"\$D/etc/resolv.conf\""))
        assertTrue(script.contains("> \"\$D/etc/gai.conf\""))
        // A caller must not be able to redirect the write outside the jail's etc dir.
        assertFalse(script.contains("/etc/../"))
        assertTrue(script.contains("echo ${Toolchain.NETCONF_OK}"))
        assertTrue(script.contains("NETCONF_FAIL="))
        assertTrue(script.contains("exit 1"))
        // Best-effort script: a failed mkdir must not abort before the writes.
        assertTrue(script.lines().first() == "set +e")
    }

    private fun nameservers(text: String): List<String> = text.lineSequence()
        .filter { it.startsWith("nameserver ") }
        .map { it.removePrefix("nameserver ").trim() }
        .toList()
}

package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Debian 13 apt sources written into every jail.
 *
 * deb822 `URIs:` is not a per-request fallback list: apt creates one source per URI and
 * would fetch both index sets, still hitting `deb.debian.org` on mainland networks. So
 * the default config lists the aliyun mirror only; `toolsScript` overwrites it with the
 * official config after a failed `apt-get update` and retries. Both files use plain http
 * because `ca-certificates` is installed by this very apt transaction.
 */
class ToolchainAptTest {

    private val sources = Toolchain.aptSources()
    private val official = Toolchain.officialAptSources()

    @Test
    fun `the default sources list only the aliyun mirror`() {
        val uris = sources.lines().filter { it.startsWith("URIs: ") }
        assertEquals(2, uris.size)
        assertEquals(listOf(
            "URIs: http://mirrors.aliyun.com/debian",
            "URIs: http://mirrors.aliyun.com/debian-security",
        ), uris)
        // Listing the official host here would make apt fetch it on every update, which
        // is exactly what the mirror-only default is meant to avoid.
        assertFalse(sources.contains("deb.debian.org"))
    }

    @Test
    fun `the official fallback keeps the same suites, keyring and layout`() {
        val uris = official.lines().filter { it.startsWith("URIs: ") }
        assertEquals(listOf(
            "URIs: http://deb.debian.org/debian",
            "URIs: http://deb.debian.org/debian-security",
        ), uris)
        assertFalse(official.contains("mirrors.aliyun.com"))
        // Both files must stay structurally identical apart from the host.
        assertEquals(
            sources.lines().filterNot { it.startsWith("URIs: ") },
            official.lines().filterNot { it.startsWith("URIs: ") },
        )
    }

    @Test
    fun `suites, components and keyring match the debian 13 image`() {
        listOf(sources, official).forEach { text ->
            assertTrue(text.contains("Suites: trixie trixie-updates"))
            assertTrue(text.contains("Suites: trixie-security"))
            assertEquals(2, Regex("""^Components: main$""", RegexOption.MULTILINE).findAll(text).count())
            assertEquals(
                2,
                Regex(
                    """^Signed-By: /usr/share/keyrings/debian-archive-keyring\.pgp$""",
                    RegexOption.MULTILINE,
                ).findAll(text).count(),
            )
            // deb822 files must end with a newline or apt ignores the last field.
            assertTrue(text.endsWith("\n"))
        }
    }

    @Test
    fun `plain http is used because ca-certificates is installed later`() {
        assertFalse(sources.contains("https://"))
        assertFalse(official.contains("https://"))
    }
}

package app.claudroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseInfoTest {

    @Test
    fun `parses the github release payload`() {
        val release = ReleaseCheck.parse(
            """
            {
              "tag_name": "v0.1.12",
              "html_url": "https://github.com/Yousaling0624/claudroid/releases/tag/v0.1.12",
              "published_at": "2026-11-05T00:00:00Z"
            }
            """.trimIndent()
        )
        assertNotNull(release)
        assertEquals("0.1.12", release!!.tag)
        assertEquals(
            "https://github.com/Yousaling0624/claudroid/releases/tag/v0.1.12",
            release.url,
        )
    }

    @Test
    fun `rejects payloads without a version tag`() {
        assertNull(ReleaseCheck.parse("""{"message":"Not Found"}"""))
        assertNull(ReleaseCheck.parse("""{"tag_name":"nightly"}"""))
        assertNull(ReleaseCheck.parse("not json"))
    }

    @Test
    fun `compares numeric versions with different segment counts`() {
        assertEquals(0, ReleaseCheck.compareVersions("0.1.2", "v0.1.2"))
        assertEquals(1, ReleaseCheck.compareVersions("0.2", "0.1.9"))
        assertEquals(-1, ReleaseCheck.compareVersions("0.1.2", "0.1.10"))
        assertEquals(0, ReleaseCheck.compareVersions("0.1.2-dev.3", "0.1.2"))
    }

    @Test
    fun `status distinguishes releases from local builds`() {
        assertEquals(
            ReleaseStatus.UP_TO_DATE,
            ReleaseCheck.status("0.1.2", "v0.1.2"),
        )
        assertEquals(
            ReleaseStatus.UPDATE_AVAILABLE,
            ReleaseCheck.status("0.1.1", "v0.1.2"),
        )
        assertEquals(
            ReleaseStatus.DEV_BUILD,
            ReleaseCheck.status("0.1.2-dev.4", "v0.1.2"),
        )
        assertEquals(
            ReleaseStatus.DEV_BUILD,
            ReleaseCheck.status("1.1.0", "v0.1.2"),
        )
    }
}

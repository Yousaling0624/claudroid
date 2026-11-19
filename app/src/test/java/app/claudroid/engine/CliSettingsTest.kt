package app.claudroid.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CLI settings merge behind WebFetch's `skipWebFetchPreflight`.
 *
 * WebFetch checks `api.anthropic.com` before every fetch; when that host is blocked the
 * tool fails without ever requesting the page. The merge must add the flag without
 * touching any other user setting, and must never rewrite a file it cannot parse.
 */
class CliSettingsTest {

    private fun merged(text: String?): JsonObject =
        Json.parseToJsonElement(CliSettings.merge(text)!!).jsonObject

    @Test
    fun `a missing settings file gets the flag`() {
        val root = merged(null)
        assertEquals(true, root.getValue(CliSettings.SKIP_WEB_FETCH_PREFLIGHT).jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `unrelated user settings are preserved`() {
        val root = merged("""{"model":"opus","permissions":{"deny":["Bash(rm:*)"]}}""")
        assertEquals("opus", root.getValue("model").jsonPrimitive.content)
        assertTrue(root.containsKey("permissions"))
        assertEquals(true, root.getValue(CliSettings.SKIP_WEB_FETCH_PREFLIGHT).jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `an explicit false is upgraded to true`() {
        val root = merged("""{"skipWebFetchPreflight":false}""")
        assertEquals(true, root.getValue(CliSettings.SKIP_WEB_FETCH_PREFLIGHT).jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `an already enabled file is returned verbatim`() {
        val text = """{"skipWebFetchPreflight":true,"model":"sonnet"}"""
        assertEquals(text, CliSettings.merge(text))
    }

    @Test
    fun `a non-object file is left alone`() {
        assertNull(CliSettings.merge("[1,2,3]"))
        assertNull(CliSettings.merge("not json"))
    }

    @Test
    fun `blank content is treated like a missing file`() {
        val root = merged("  \n")
        assertEquals(true, root.getValue(CliSettings.SKIP_WEB_FETCH_PREFLIGHT).jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `the merged document is still valid json`() {
        val text = CliSettings.merge("""{"env":{"FOO":"bar"}}""")!!
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive("bar"), root.getValue("env").jsonObject.getValue("FOO"))
    }
}

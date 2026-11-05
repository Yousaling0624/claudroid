package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvKeysTest {

    @Test
    fun `accepts posix identifiers and keeps equals signs in values`() {
        val env = linkedMapOf<String, String>()
        val rejected = EnvKeys.applyUserLines(
            env,
            """
            # comment
            ANTHROPIC_MODEL=claude-opus-4
            _PRIVATE = spaced value
            TOKEN=a=b=c
            no_equals_here
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), rejected)
        assertEquals("claude-opus-4", env["ANTHROPIC_MODEL"])
        assertEquals("spaced value", env["_PRIVATE"])
        assertEquals("a=b=c", env["TOKEN"])
        assertEquals(3, env.size)
    }

    @Test
    fun `rejects keys that would break out of the export line`() {
        val env = linkedMapOf<String, String>()
        val rejected = EnvKeys.applyUserLines(
            env,
            """
            FOO;touch /data/pwned=1
            `id`=2
            A B=3
            1ST=4
            =5
            OK=6
            """.trimIndent(),
        )
        assertEquals("OK", env.keys.single())
        assertEquals(5, rejected.size)
        assertTrue(rejected.contains(""))
    }

    @Test
    fun `safe entries filter a map built elsewhere`() {
        val entries = EnvKeys.safeEntries(linkedMapOf("GOOD" to "1", "A B" to "2", "X;id" to "3"))
        assertEquals(listOf("GOOD"), entries.map { it.key })
    }

    @Test
    fun `identifier rules match the shell`() {
        assertTrue(EnvKeys.isSafe("PATH"))
        assertTrue(EnvKeys.isSafe("_A1"))
        assertFalse(EnvKeys.isSafe(""))
        assertFalse(EnvKeys.isSafe("1A"))
        assertFalse(EnvKeys.isSafe("A-B"))
        assertFalse(EnvKeys.isSafe("A=B"))
        assertFalse(EnvKeys.isSafe("A\nB"))
    }
}

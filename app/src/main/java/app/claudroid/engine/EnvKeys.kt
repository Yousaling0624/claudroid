package app.claudroid.engine

/**
 * Validation for environment variable names.
 *
 * Turn scripts splice a key into `export <key>=<quoted value>` without quoting the key
 * (quoting would make it a literal string, not an assignment). A user-supplied key such
 * as `FOO; rm -rf /` would therefore inject a second shell command into a turn that
 * runs as root. Only POSIX identifiers may pass; bad lines are skipped with a hint
 * instead of failing the turn.
 */
internal object EnvKeys {

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun isSafe(key: String): Boolean = IDENTIFIER.matches(key)

    /**
     * Merges the settings text field's `KEY=VALUE` lines into [into].
     *
     * Comments and lines without `=` are ignored as before. Values may contain `=` and
     * are trimmed. Returns the rejected keys so the caller can warn (the caller must not
     * log values: those may be tokens).
     */
    fun applyUserLines(into: MutableMap<String, String>, text: String): List<String> {
        val rejected = mutableListOf<String>()
        text.lineSequence().map { it.trim() }
            .filter { it.contains("=") && !it.startsWith("#") }
            .forEach { line ->
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim()
                if (isSafe(key)) into[key] = value else rejected += key
            }
        return rejected
    }

    /** Defense in depth for [TurnScripts]: never emit an unsafe key, whoever built the map. */
    fun safeEntries(env: Map<String, String>): List<Map.Entry<String, String>> =
        env.entries.filter { isSafe(it.key) }
}

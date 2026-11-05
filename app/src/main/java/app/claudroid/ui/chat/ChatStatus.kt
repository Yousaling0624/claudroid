package app.claudroid.ui.chat

/**
 * The header line under the chat title and beside "Claude".
 *
 * [ChatController.status] is deliberately raw: while a turn runs it also carries
 * one-off CLI diagnostics such as `[claude-code:unrecognized_model] {...}`.
 * Those lines are useful in logs but read as noise (and can leak the query) in a
 * 11sp subtitle, so the UI falls back to a calm "正在回复…" and leaves real
 * failures to the dedicated error notice.
 */
internal fun chatStatusLine(running: Boolean, rawStatus: String, model: String): String {
    if (!running) return model
    val cleaned = rawStatus.trim()
    val looksLikeDiagnostic = cleaned.startsWith("[") || cleaned.startsWith("{")
    return if (cleaned.isEmpty() || looksLikeDiagnostic) "正在回复…" else cleaned
}

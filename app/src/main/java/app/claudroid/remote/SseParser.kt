package app.claudroid.remote

/** One fully parsed SSE event. */
data class SseFrame(
    val event: String,
    val id: Long?,
    val data: String,
)

/**
 * Incremental Server-Sent Events parser. Feed arbitrary string chunks; complete
 * frames are returned when the blank-line terminator arrives. Pure logic, unit-tested.
 */
class SseParser {
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var event = "message"
    private var id: Long? = null
    private val frames = ArrayList<SseFrame>()

    fun feed(chunk: String): List<SseFrame> {
        frames.clear()
        var index = 0
        while (index < chunk.length) {
            when (val c = chunk[index]) {
                '\n' -> {
                    consumeLine(line.toString().removeSuffix("\r"))
                    line.setLength(0)
                }
                else -> line.append(c)
            }
            index += 1
        }
        return frames.toList()
    }

    private fun consumeLine(value: String) {
        if (value.isEmpty()) {
            if (data.isNotEmpty()) {
                frames.add(SseFrame(event, id, data.toString()))
                data.setLength(0)
                event = "message"
                id = null
            }
            return
        }
        // Comment/heartbeat.
        if (value.startsWith(":")) return
        val colon = value.indexOf(':')
        val field = if (colon < 0) value else value.substring(0, colon)
        val raw = if (colon < 0) "" else value.substring(colon + 1)
        val fieldValue = raw.removePrefix(" ")
        when (field) {
            "event" -> event = fieldValue.ifBlank { "message" }
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(fieldValue)
            }
            "id" -> id = fieldValue.toLongOrNull()
        }
    }
}

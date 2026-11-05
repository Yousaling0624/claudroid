package app.claudroid.ui.components

import app.claudroid.data.Block
import app.claudroid.data.ChatSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure grouping used by the session drawer: "today / yesterday / past 7 days /
 * earlier". Kept free of Compose and Android so it can be unit-tested with a fixed
 * clock and zone.
 */
enum class SessionBucket(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    PAST_WEEK("过去 7 天"),
    EARLIER("更早"),
}

data class SessionGroup(val bucket: SessionBucket, val sessions: List<ChatSession>)

fun groupSessions(
    sessions: List<ChatSession>,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<SessionGroup> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val buckets = linkedMapOf<SessionBucket, MutableList<ChatSession>>()
    sessions.sortedByDescending { it.updatedAt }.forEach { session ->
        val date = Instant.ofEpochMilli(session.updatedAt).atZone(zone).toLocalDate()
        // Clock skew can put a transcript "tomorrow"; treat it as today instead of
        // dropping the row into a bucket users cannot reason about.
        val days = ChronoUnit.DAYS.between(date, today).coerceAtLeast(0)
        val bucket = when {
            days == 0L -> SessionBucket.TODAY
            days == 1L -> SessionBucket.YESTERDAY
            days < 7L -> SessionBucket.PAST_WEEK
            else -> SessionBucket.EARLIER
        }
        buckets.getOrPut(bucket) { mutableListOf() }.add(session)
    }
    return SessionBucket.entries.mapNotNull { bucket ->
        buckets[bucket]?.takeIf { it.isNotEmpty() }?.let { SessionGroup(bucket, it) }
    }
}

/** Search matches the title and the transcript, mirroring what the user remembers. */
fun filterSessions(sessions: List<ChatSession>, query: String): List<ChatSession> {
    val needle = query.trim()
    if (needle.isEmpty()) return sessions
    return sessions.filter { session ->
        session.title.contains(needle, ignoreCase = true) ||
            session.messages.any { message ->
                message.blocks.any { block ->
                    when (block) {
                        is Block.Text -> block.text.contains(needle, ignoreCase = true)
                        is Block.Thinking -> block.text.contains(needle, ignoreCase = true)
                        is Block.Error -> block.text.contains(needle, ignoreCase = true)
                        is Block.Tool ->
                            block.name.contains(needle, ignoreCase = true) ||
                                block.input.contains(needle, ignoreCase = true) ||
                                block.output?.contains(needle, ignoreCase = true) == true
                    }
                }
            }
    }
}

/** Compact right-hand timestamp: HH:mm today, 昨天, weekday within the week, MM-dd. */
fun sessionTimeLabel(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val time = Instant.ofEpochMilli(timestamp).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(time.toLocalDate(), today).coerceAtLeast(0)
    return when {
        days == 0L -> time.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        days == 1L -> "昨天"
        days < 7L -> time.format(DateTimeFormatter.ofPattern("EEE", locale))
        else -> time.format(DateTimeFormatter.ofPattern("MM-dd", locale))
    }
}

package app.claudroid.ui.components

import app.claudroid.data.Block
import app.claudroid.data.ChatMessage
import app.claudroid.data.ChatSession
import app.claudroid.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class SessionGroupingTest {

    private val zone = ZoneId.of("UTC")
    private val now = at("2026-11-05", hour = 12)

    private fun at(date: String, hour: Int = 12): Long =
        LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, updatedAt: Long, text: String = "hello") = ChatSession(
        id = id,
        title = id,
        updatedAt = updatedAt,
        messages = listOf(
            ChatMessage(role = Role.USER, blocks = listOf(Block.Text(text))),
        ),
    )

    @Test
    fun `buckets span today yesterday past week and earlier`() {
        val sessions = listOf(
            session("today", at("2026-11-05", 9)),
            session("yesterday", at("2026-11-04", 20)),
            session("three", at("2026-11-02", 8)),
            session("six", at("2026-10-30", 8)),
            session("seven", at("2026-10-29", 8)),
            session("old", at("2026-01-01", 8)),
        )

        val groups = groupSessions(sessions, now, zone)

        assertEquals(
            listOf(
                SessionBucket.TODAY,
                SessionBucket.YESTERDAY,
                SessionBucket.PAST_WEEK,
                SessionBucket.EARLIER,
            ),
            groups.map { it.bucket },
        )
        assertEquals(listOf("today"), groups[0].sessions.map { it.id })
        assertEquals(listOf("yesterday"), groups[1].sessions.map { it.id })
        assertEquals(listOf("three", "six"), groups[2].sessions.map { it.id })
        assertEquals(listOf("seven", "old"), groups[3].sessions.map { it.id })
    }

    @Test
    fun `sessions are ordered newest first and future timestamps stay in today`() {
        val groups = groupSessions(
            listOf(
                session("a", at("2026-11-05", 8)),
                session("b", at("2026-11-05", 11)),
                session("skew", at("2026-11-06", 9)),
            ),
            now,
            zone,
        )
        assertEquals(listOf("skew", "b", "a"), groups.single().sessions.map { it.id })
    }

    @Test
    fun `search matches title and message text case-insensitively`() {
        val first = session("Alpha", at("2026-11-05", 9), text = "检查 Magisk 模块")
        val second = session("Beta", at("2026-11-05", 8), text = "清理缓存")
        val sessions = listOf(first, second)

        assertEquals(listOf("Alpha"), filterSessions(sessions, "magisk").map { it.id })
        assertEquals(listOf("Beta"), filterSessions(sessions, "beta").map { it.id })
        assertEquals(sessions, filterSessions(sessions, "  "))
        assertTrue(filterSessions(sessions, "不存在").isEmpty())
    }

    @Test
    fun `time labels switch from clock to yesterday weekday and date`() {
        assertEquals("09:00", sessionTimeLabel(at("2026-11-05", 9), now, zone, Locale.US))
        assertEquals("昨天", sessionTimeLabel(at("2026-11-04", 9), now, zone, Locale.US))
        assertEquals("Mon", sessionTimeLabel(at("2026-11-02", 9), now, zone, Locale.US))
        assertEquals("10-29", sessionTimeLabel(at("2026-10-29", 9), now, zone, Locale.US))
    }
}

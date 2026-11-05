package app.claudroid.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {

    @Test
    fun `greeting follows the hour boundaries`() {
        assertEquals("夜深了", greetingForHour(4))
        assertEquals("早上好", greetingForHour(5))
        assertEquals("早上好", greetingForHour(10))
        assertEquals("中午好", greetingForHour(11))
        assertEquals("中午好", greetingForHour(13))
        assertEquals("下午好", greetingForHour(14))
        assertEquals("下午好", greetingForHour(17))
        assertEquals("晚上好", greetingForHour(18))
        assertEquals("晚上好", greetingForHour(22))
        assertEquals("夜深了", greetingForHour(23))
        assertEquals("夜深了", greetingForHour(0))
    }
}

package app.claudroid.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStatusTest {

    @Test
    fun idleShowsConfiguredModel() {
        assertEquals("auto/hy4free", chatStatusLine(running = false, rawStatus = "", model = "auto/hy4free"))
    }

    @Test
    fun friendlyStatusIsKeptWhileRunning() {
        assertEquals("正在思考…", chatStatusLine(running = true, rawStatus = "正在思考…", model = "m"))
        assertEquals("执行工具 Bash…", chatStatusLine(running = true, rawStatus = "执行工具 Bash…", model = "m"))
    }

    @Test
    fun blankStatusFallsBackToThinking() {
        assertEquals("正在回复…", chatStatusLine(running = true, rawStatus = "   ", model = "m"))
    }

    @Test
    fun rawCliDiagnosticsAreHidden() {
        assertEquals(
            "正在回复…",
            chatStatusLine(
                running = true,
                rawStatus = """[claude-code:unrecognized_model] {"model":"auto/hy4free"}""",
                model = "auto/hy4free",
            ),
        )
        assertEquals(
            "正在回复…",
            chatStatusLine(running = true, rawStatus = """{"type":"result"}""", model = "auto/hy4free"),
        )
    }
}

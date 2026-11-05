package app.claudroid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelProviderBadgeTest {

    @Test
    fun `auto group uses a single letter`() {
        assertEquals("A", modelProviderBadge("auto/dfmodel"))
    }

    @Test
    fun `anthropic models use the brand letter`() {
        assertEquals("C", modelProviderBadge("claude-sonnet-5-5"))
    }

    @Test
    fun `provider prefixes are shortened to two letters`() {
        assertEquals("OP", modelProviderBadge("opencode-zen-free/crouton-alpha-free"))
        assertEquals("QO", modelProviderBadge("qoder-cn/dfmodel"))
    }

    @Test
    fun `blank ids fall back to a question mark`() {
        assertEquals("?", modelProviderBadge(""))
    }
}

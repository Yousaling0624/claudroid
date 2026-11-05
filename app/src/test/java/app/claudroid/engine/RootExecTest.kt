package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stops a jail delete while a chroot session still has it as its root.
 *
 * [RootExec] itself needs an Android `Context` and a device, so only the pure decision
 * is covered here; the call sites in `deleteJail`/`deleteTree` are exercised on device.
 */
class RootExecTest {

    @Test
    fun `no live chroot session allows the delete`() {
        assertNull(RootExec.liveChrootRefusal(0))
    }

    @Test
    fun `one live chroot session refuses the delete`() {
        val reason = RootExec.liveChrootRefusal(1)
        assertEquals("仍有 1 个 chroot 会话存活，拒绝删除", reason)
    }

    @Test
    fun `the refusal names the number of live sessions`() {
        val reason = RootExec.liveChrootRefusal(3).orEmpty()
        assertTrue(reason, reason.contains("仍有 3 个 chroot 会话存活，拒绝删除"))
    }
}

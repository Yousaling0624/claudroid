package app.claudroid.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam must survive both orders, because `Application.onCreate` runs before any
 * Flutter class is loaded in the `-PflutterUi=true` build and after the host's own
 * registration in the default one.
 */
class FlutterBridgeHostTest {

    @After
    fun reset() = FlutterBridgeHost.resetForTests()

    @Test
    fun `start before install is replayed when the registrar arrives`() {
        val seen = mutableListOf<Any>()
        FlutterBridgeHost.start("app")
        assertFalse(FlutterBridgeHost.installed)

        FlutterBridgeHost.setInstaller { seen += it }
        assertTrue(FlutterBridgeHost.installed)
        assertEquals(listOf<Any>("app"), seen)
    }

    @Test
    fun `install before start runs immediately`() {
        val seen = mutableListOf<Any>()
        FlutterBridgeHost.setInstaller { seen += it }
        FlutterBridgeHost.start("app")
        assertEquals(listOf<Any>("app"), seen)
    }

    @Test
    fun `a second start never creates a second engine`() {
        val seen = mutableListOf<Any>()
        FlutterBridgeHost.setInstaller { seen += it }
        FlutterBridgeHost.start("first")
        FlutterBridgeHost.start("second")
        assertEquals(1, seen.size)
    }

    @Test
    fun `explicit registration suppresses the pending replay`() {
        val seen = mutableListOf<Any>()
        FlutterBridgeHost.start("app")
        // The Flutter host built its own engine; the replay must not build another.
        FlutterBridgeHost.markSatisfied()
        FlutterBridgeHost.setInstaller { seen += it }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `without a registrar start is a silent no-op`() {
        FlutterBridgeHost.start("app")
        assertFalse(FlutterBridgeHost.installed)
    }
}

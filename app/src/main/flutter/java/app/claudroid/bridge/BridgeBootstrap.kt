package app.claudroid.bridge

/**
 * Flutter-side entry point for [FlutterBridgeHost].
 *
 * Loading this object installs the seam; the Flutter host (the Activity in this
 * source set) must touch it before the engine is created, e.g.
 * `BridgeBootstrap.install()` in `onCreate`. `ClaudroidApp` then calls
 * `FlutterBridgeHost.start(this)`, which prewarms the single cached engine and
 * registers [BridgePlugin] on it.
 *
 * Kept separate from [BridgePlugin] so the seam is installed even if the plugin is
 * only referenced later: the two classes share one source set but neither depends on
 * the other's init order.
 */
object BridgeBootstrap {

    /** Idempotent; safe to call from every Activity creation. */
    fun install() {
        BridgePlugin.installSeam()
    }

    init {
        install()
    }
}

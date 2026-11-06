package app.claudroid.bridge

/**
 * Compile-time seam between the app module and the optional Flutter embedding.
 *
 * `src/main/flutter/java/` is only on the classpath under `-PflutterUi=true`, so
 * `src/main/java/` can never name a Flutter class. `ClaudroidApp` calls [start] with
 * itself; the Flutter source set installs the real registrar via [setInstaller].
 *
 * Both orders must work: `Application.onCreate` runs before any Flutter class is
 * loaded, so a [start] that arrives first is remembered and replayed when the registrar
 * appears. Exactly one of two paths may run — the replay below, or an explicit
 * `BridgePlugin.register(engine)` by the Flutter host, which calls [markSatisfied]
 * first. Attaching the plugin twice would install duplicate channel handlers.
 */
object FlutterBridgeHost {

    /** `(application) -> Unit`; typed as [Any] so no Flutter type leaks into this file. */
    private var installer: ((Any) -> Unit)? = null

    /** Host passed to [start] before a registrar existed. */
    private var pendingHost: Any? = null

    private var started = false

    /** Installed by the Flutter source set; replays a pending [start]. */
    fun setInstaller(block: ((Any) -> Unit)?) {
        val host: Any?
        synchronized(this) {
            installer = block
            host = pendingHost?.takeIf { block != null && !started }
            if (host != null) {
                pendingHost = null
                started = true
            }
        }
        // Never invoke the installer under the lock: it builds a FlutterEngine.
        if (host != null) block?.invoke(host)
    }

    /** True when the Flutter side is present in this build. */
    val installed: Boolean get() = synchronized(this) { installer != null }

    /** No-op unless a registrar is installed; safe to call from every `onCreate`. */
    fun start(host: Any) {
        val block: ((Any) -> Unit)?
        synchronized(this) {
            if (started) return
            block = installer
            if (block == null) {
                // Flutter classes load later; remember the host and replay then.
                pendingHost = host
                return
            }
            started = true
        }
        block?.invoke(host)
    }

    /**
     * Called by an explicit registration path (the Flutter host created its own engine)
     * so the pending [start] never creates a second engine.
     */
    fun markSatisfied() {
        synchronized(this) {
            started = true
            pendingHost = null
        }
    }

    /** Test-only: forgets the registrar and the single-shot guard. */
    internal fun resetForTests() {
        synchronized(this) {
            installer = null
            pendingHost = null
            started = false
        }
    }
}

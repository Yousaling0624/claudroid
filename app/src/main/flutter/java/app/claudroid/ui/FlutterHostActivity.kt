package app.claudroid.ui

import android.content.Context
import android.os.Bundle
import app.claudroid.bridge.BridgeBootstrap
import app.claudroid.bridge.BridgePlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache

/**
 * Hosts the optional Flutter frontend. Only present in the classpath when the build
 * runs with `-PflutterUi=true`; the default APK never contains this class, which is
 * exactly what [MainActivity]'s trampoline probes for.
 */
class FlutterHostActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super: the engine may be created inside `provideFlutterEngine` during
        // the super call, and the channels must be registered on it by then.
        BridgeBootstrap.install()
        super.onCreate(savedInstanceState)
    }

    /**
     * Reuses the one engine parked in [FlutterEngineCache] under
     * [BridgePlugin.ENGINE_CACHE_KEY], so the bridge survives Activity recreation
     * (rotation, returning from background) instead of rebuilding every channel.
     */
    override fun provideFlutterEngine(context: Context): FlutterEngine? {
        val cache = FlutterEngineCache.getInstance()
        cache.get(BridgePlugin.ENGINE_CACHE_KEY)?.let { return it }
        BridgePlugin.attachCachedEngine(context)
        return cache.get(BridgePlugin.ENGINE_CACHE_KEY)
    }

    /** The cached engine outlives this Activity; destroying it here would drop the session. */
    override fun shouldDestroyEngineWithHost(): Boolean = false
}

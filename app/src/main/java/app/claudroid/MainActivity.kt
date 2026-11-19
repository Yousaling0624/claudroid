package app.claudroid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import app.claudroid.bridge.BridgePlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache

/**
 * The app's only host: Flutter renders the whole interface, Kotlin owns the
 * engine, persistence and the platform channels (see docs/BRIDGE_CONTRACT.md).
 *
 * The cached engine outlives the Activity, so the shell state and every channel
 * survive rotation and returning from the background.
 */
class MainActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super: the engine may be created inside `provideFlutterEngine` during
        // the super call, and the channels must be registered on it by then.
        BridgePlugin.prewarm(applicationContext)
        super.onCreate(savedInstanceState)
        askForNotificationPermission()
    }

    /**
     * The "turn still running" notification needs the permission on Android 13+.
     * FlutterActivity extends plain Activity, so this uses the platform request
     * rather than the ComponentActivity result API.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
    }

    /** Reuses the engine parked in [FlutterEngineCache]; channels never restart. */
    override fun provideFlutterEngine(context: Context): FlutterEngine? {
        val cache = FlutterEngineCache.getInstance()
        cache.get(BridgePlugin.ENGINE_CACHE_KEY)?.let { return it }
        BridgePlugin.attachCachedEngine(context)
        return cache.get(BridgePlugin.ENGINE_CACHE_KEY)
    }

    /** The cached engine outlives this Activity; destroying it would drop the session. */
    override fun shouldDestroyEngineWithHost(): Boolean = false

    private companion object {
        const val NOTIFICATION_REQUEST = 0x0A76
    }
}

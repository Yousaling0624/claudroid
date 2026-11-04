package app.claudroid.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What is currently installed, and where the jail lives.
 *
 * Since Android 10 a targetSdk 29+ app may not exec() files it wrote into its own data
 * directory (W^X / SELinux). The CLI is an executable we download, so in root mode the
 * jail is created under /data/adb (a Magisk-owned, exec-friendly place) by a root shell,
 * and the app keeps only small control files in its private storage.
 */
@Serializable
data class Installation(
    val version: String,
    val jailPath: String,
    val arch: String,
    val rootMode: Boolean,
    /** GNU bash is bundled into the jail when the Alpine package download succeeded. */
    val hasBash: Boolean = false,
    val installedAt: Long = System.currentTimeMillis(),
) {
    fun jail(): File = File(jailPath)
    fun binary(): File = File(jail(), "opt/claude/$version/claude")
    fun loader(): File = File(jail(), "lib/ld-musl-$arch.so.1")
    fun busybox(): File = File(jail(), "bin/busybox")
    fun bash(): File = File(jail(), "bin/bash")
}

object Paths {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun engine(ctx: Context): File = File(ctx.filesDir, "engine").apply { mkdirs() }

    /** Where downloads/unpacking happen before the jail is assembled. */
    fun staging(ctx: Context): File = File(ctx.cacheDir, "engine-stage").apply { mkdirs() }

    fun workspace(ctx: Context): File = File(ctx.filesDir, "workspace").apply { mkdirs() }

    fun home(ctx: Context): File = File(ctx.filesDir, "home").apply { mkdirs() }

    fun sessions(ctx: Context): File = File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun engineLog(ctx: Context): File = File(engine(ctx), "engine.log")

    /** Preferred jail location when we have root. */
    const val ROOT_JAIL = "/data/adb/claudroid"

    /** Fallback when /data/adb is unavailable but su works. */
    const val ROOT_JAIL_FALLBACK = "/data/local/tmp/claudroid"

    private fun installFile(ctx: Context) = File(engine(ctx), "install.json")

    fun installation(ctx: Context): Installation? = runCatching {
        val f = installFile(ctx)
        if (!f.isFile) return null
        json.decodeFromString(Installation.serializer(), f.readText())
    }.getOrNull()

    fun saveInstallation(ctx: Context, installation: Installation) {
        installFile(ctx).writeText(json.encodeToString(Installation.serializer(), installation))
    }

    fun clearInstallation(ctx: Context) {
        installFile(ctx).delete()
    }
}

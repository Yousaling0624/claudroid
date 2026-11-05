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
 *
 * The jail is a full Debian rootfs (see [EngineManager]); the Alpine-era fields are kept
 * only so an old install.json remains parseable. A blank [rootfsVersion] means "legacy
 * Alpine jail" and is deliberately treated as unhealthy so it gets replaced.
 */
@Serializable
data class Installation(
    val version: String,
    val jailPath: String,
    val arch: String,
    val rootMode: Boolean,
    /** GNU bash is always present in the Debian rootfs; the default keeps old JSON valid. */
    val hasBash: Boolean = false,
    val installedAt: Long = System.currentTimeMillis(),
    /** e.g. "debian-13-slim"; blank on an Alpine-era install. */
    val rootfs: String = "",
    /** Debian release ("13"); blank marks a legacy install that must be reinstalled. */
    val rootfsVersion: String = "",
    /** Layer digest (`sha256:...`) the rootfs was unpacked from. */
    val rootfsSha256: String = "",
) {
    fun jail(): File = File(jailPath)
    fun binary(): File = File(jail(), "opt/claude/$version/claude")

    /** Present in every Debian rootfs; the cheapest "this is really Debian" marker. */
    fun debianVersion(): File = File(jail(), "etc/debian_version")

    /** Written by us: image tag, release and layer digest, for support logs. */
    fun rootfsMarker(): File = File(jail(), "etc/claudroid-rootfs")

    fun isDebian(): Boolean = rootfsVersion.isNotBlank() && debianVersion().isFile

    /** ELF interpreter of the Debian rootfs (Debian 13 is usrmerge: /lib -> usr/lib). */
    fun loader(): File = if (arch == "x86_64") {
        File(jail(), "lib64/ld-linux-x86-64.so.2")
    } else {
        File(jail(), "lib/ld-linux-aarch64.so.1")
    }

    fun multiarch(): String = if (arch == "x86_64") "x86_64-linux-gnu" else "aarch64-linux-gnu"

    /**
     * Host-side library search path for the rare no-root/no-PRoot launch. The rootfs is
     * not mounted at "/", so every entry is prefixed with the jail path.
     */
    fun libraryPath(): String = listOf("usr/lib/${multiarch()}", "usr/lib", "lib")
        .joinToString(":") { File(jail(), it).absolutePath }

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

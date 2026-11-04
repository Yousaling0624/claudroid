package app.claudroid.engine

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Which npm artifact matches this device. */
data class PlatformTarget(
    val abi: String,
    val npmPackage: String,
    val muslArch: String,
    val alpineArch: String,
)

data class PackageInfo(val version: String, val tarball: String, val integrity: String?)

object Registry {

    private val json = Json { ignoreUnknownKeys = true }

    fun target(): PlatformTarget? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when (abi) {
            "arm64-v8a" -> PlatformTarget(
                abi = abi,
                npmPackage = "@anthropic-ai/claude-code-linux-arm64-musl",
                muslArch = "aarch64",
                alpineArch = "aarch64",
            )
            "x86_64" -> PlatformTarget(
                abi = abi,
                npmPackage = "@anthropic-ai/claude-code-linux-x64-musl",
                muslArch = "x86_64",
                alpineArch = "x86_64",
            )
            else -> null // 32-bit ABIs are not published by upstream
        }
    }

    /** Latest published version of the CLI (the umbrella package carries the version). */
    suspend fun latestCliVersion(): String = withContext(Dispatchers.IO) {
        val body = HttpUtil.getText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")
        json.parseToJsonElement(body).jsonObject["version"]!!.jsonPrimitive.content
    }

    /** Tarball for the platform-specific binary package. */
    suspend fun platformPackage(pkg: String, version: String? = null): PackageInfo = withContext(Dispatchers.IO) {
        val url = if (version == null) {
            "https://registry.npmjs.org/$pkg/latest"
        } else {
            "https://registry.npmjs.org/$pkg/$version"
        }
        val body = HttpUtil.getText(url)
        val obj = json.parseToJsonElement(body).jsonObject
        val dist = obj["dist"]!!.jsonObject
        PackageInfo(
            version = obj["version"]!!.jsonPrimitive.content,
            tarball = dist["tarball"]!!.jsonPrimitive.content,
            integrity = dist["integrity"]?.jsonPrimitive?.content,
        )
    }

    /**
     * Alpine publishes the musl dynamic loader that the CLI binary asks for via PT_INTERP.
     * We resolve the current package filename from APKINDEX instead of pinning a version.
     */
    suspend fun muslLoaderUrl(arch: String): String = withContext(Dispatchers.IO) {
        val repo = "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/$arch"
        val tmp = java.io.File.createTempFile("apkindex", ".tar.gz")
        try {
            val digest = HttpUtil.download("$repo/APKINDEX.tar.gz", tmp)
            check(digest.isNotEmpty()) { "APKINDEX download failed" }
            val index = TarGz.readTextEntry(tmp, "APKINDEX") ?: error("APKINDEX missing")
            val blocks = index.split("\n\n")
            val musl = blocks.firstOrNull { block ->
                block.lineSequence().any { it == "P:musl" }
            } ?: error("musl package not found in APKINDEX")
            val version = musl.lineSequence().first { it.startsWith("V:") }.removePrefix("V:")
            "$repo/musl-$version.apk"
        } finally {
            tmp.delete()
        }
    }
}

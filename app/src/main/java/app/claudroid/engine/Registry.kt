package app.claudroid.engine

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One registry + token endpoint pair for the OCI pull.
 *
 * A mirror cannot change what we install: the blob URL is content-addressed by the
 * pinned `sha256:…` digest, so every source that answers must return exactly the bytes
 * whose hash we already know. Mirrors only exist because `auth.docker.io` and
 * `registry-1.docker.io` are unreachable or poisoned on some mobile networks.
 */
data class OciSource(
    /** For logs only; never contains a token. */
    val name: String,
    /** No trailing slash, e.g. `https://registry-1.docker.io`. */
    val registry: String,
    /** Token endpoint, e.g. `https://auth.docker.io/token`. */
    val tokenEndpoint: String,
    /** `service` query value the endpoint expects. */
    val tokenService: String,
) {
    fun blobUrl(repository: String, digest: String): String =
        "$registry/v2/$repository/blobs/$digest"

    /**
     * `?service=…&scope=repository:<repo>:pull`. The values are constants, and `:`/`/`
     * are legal in a query, so this matches the exact URL verified against the mirror
     * (and Docker Hub) instead of relying on a token server to decode `%3A`.
     */
    fun tokenUrl(repository: String): String {
        require(REPOSITORY.matches(repository)) { "unsafe repository: $repository" }
        return "$tokenEndpoint?service=$tokenService&scope=repository:$repository:pull"
    }

    private companion object {
        val REPOSITORY = Regex("[a-z0-9][a-z0-9._/-]*")
    }
}

/** Which npm artifact matches this device. */
data class PlatformTarget(
    val abi: String,
    val npmPackage: String,
    /** Debian's name for the machine (`dpkg --print-architecture`). */
    val arch: String,
)

data class PackageInfo(val version: String, val tarball: String, val integrity: String?)

/**
 * A Debian rootfs image pinned by the SHA-256 of its (compressed) layer blob.
 *
 * Source: Docker Hub `library/debian:13-slim`, the official Debian image. We pull the
 * single gzip layer through the OCI registry API instead of proot-distro because
 * proot-distro only publishes `.tar.xz` assets and adding an xz decoder to the app is
 * not worth it. The pinned digests were downloaded and re-hashed on 2026-11-06:
 *
 *   arm64  sha256:acd43e9bebc37bc4ba7a5f47bd7598913337803e53d468f37d940f11f5d5f1ef
 *          30,214,037 bytes
 *   amd64  sha256:37fb86c2a767b88d500c6872195f88078f64765fe0d2f9cc375cc2efbd4c0a41
 *           29,856,132 bytes
 *
 * The digest is the content address used by the registry: it therefore also protects
 * against a mirror serving different bytes. Bumping Debian means updating both the
 * tag and these values, and testing the new layer's symlink layout.
 */
data class RootfsSpec(
    val image: String,
    val digest: String,
    val sizeBytes: Long,
    val version: String,
)

object Registry {

    private val json = Json { ignoreUnknownKeys = true }

    /** Committed image tag; only informational (the digest is what we verify). */
    const val ROOTFS_TAG = "13-slim"

    /** Docker Hub repository for the OCI pull. */
    const val ROOTFS_REPOSITORY = "library/debian"

    /**
     * Pull sources in order. The official pair comes first; the DaoCloud mirror is
     * tried only when the official one fails (poisoned DNS / no IPv4 route on some
     * networks). Both are pinned by the same digest, so a hostile or stale mirror
     * cannot substitute different bytes — the sha256 check rejects it.
     */
    val OCI_SOURCES = listOf(
        OciSource(
            name = "Docker Hub",
            registry = "https://registry-1.docker.io",
            tokenEndpoint = "https://auth.docker.io/token",
            tokenService = "registry.docker.io",
        ),
        OciSource(
            name = "DaoCloud 镜像",
            registry = "https://docker.m.daocloud.io",
            tokenEndpoint = "https://m.daocloud.io/auth/token",
            tokenService = "docker.m.daocloud.io",
        ),
    )

    private const val DEBIAN_ARM64_DIGEST =
        "sha256:acd43e9bebc37bc4ba7a5f47bd7598913337803e53d468f37d940f11f5d5f1ef"
    private const val DEBIAN_X86_64_DIGEST =
        "sha256:37fb86c2a767b88d500c6872195f88078f64765fe0d2f9cc375cc2efbd4c0a41"

    val ROOTFS_ARM64 = RootfsSpec(
        image = "debian:$ROOTFS_TAG",
        digest = DEBIAN_ARM64_DIGEST,
        sizeBytes = 30_214_037L,
        version = "13",
    )

    val ROOTFS_X86_64 = RootfsSpec(
        image = "debian:$ROOTFS_TAG",
        digest = DEBIAN_X86_64_DIGEST,
        sizeBytes = 29_856_132L,
        version = "13",
    )

    fun target(): PlatformTarget? = targetForAbi(Build.SUPPORTED_ABIS.firstOrNull())

    /** ABI → npm/rootfs selection; [PlatformTarget.arch] is the historical field name
     *  (`aarch64` / `x86_64`) used by [app.claudroid.core.Installation]. */
    fun targetForAbi(abi: String?): PlatformTarget? = when (abi) {
        "arm64-v8a" -> PlatformTarget(
            abi = abi,
            // glibc build: the Debian rootfs ships glibc, no musl loader needed.
            npmPackage = "@anthropic-ai/claude-code-linux-arm64",
            arch = "aarch64",
        )
        "x86_64" -> PlatformTarget(
            abi = abi,
            npmPackage = "@anthropic-ai/claude-code-linux-x64",
            arch = "x86_64",
        )
        else -> null // upstream publishes no 32-bit Linux build
    }

    fun rootfsFor(target: PlatformTarget): RootfsSpec = when (target.abi) {
        "arm64-v8a" -> ROOTFS_ARM64
        "x86_64" -> ROOTFS_X86_64
        else -> error("unsupported abi ${target.abi}")
    }

    /** OCI blob endpoint of the primary source; the response normally 307-redirects. */
    fun blobUrl(repository: String, digest: String): String =
        OCI_SOURCES.first().blobUrl(repository, digest)

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
}

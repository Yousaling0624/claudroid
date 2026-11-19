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
 * One npm registry that publishes the CLI metadata and its platform tarball.
 *
 * 国内镜像排第一：registry.npmjs.org 在部分大陆移动网络不可达或极慢，导致首次安装和
 * 每次 CLI 升级都要翻墙。官方源保留为回退，境外网络仍可工作。镜像不能绕过校验：
 * tarball 仍必须匹配同一 registry 返回的 dist.integrity（sha512），缺失即拒绝安装。
 */
data class NpmSource(val name: String, val baseUrl: String)

/**
 * A Debian rootfs image pinned by the SHA-256 of its (compressed) layer blob.
 *
 * Source: Docker Hub `library/debian:13`, the official standard Debian image. We pull
 * the single gzip layer through the OCI registry API instead of proot-distro because
 * proot-distro only publishes `.tar.xz` assets and adding an xz decoder to the app is
 * not worth it. The pinned digests were downloaded and re-hashed on 2026-11-19:
 *
 *   arm64  sha256:23b69c48ce5c2a6fb0681d959fa700427ad2a7cf7e6be5d25249170a1853d7ad
 *          49,778,604 bytes
 *   amd64  sha256:a6dd1c05fbba4f9028078a11f143cbb625d2408921eb8207bfa1dd1ad301276f
 *          49,412,508 bytes
 *
 * The standard image unpacks to ~135 MB / 5443 entries. Its installed package set is
 * identical to `-slim` (the extra ~40 MB is documentation/manpages), so tools such as
 * `top`, `ps` and `ip` still come from apt (`procps`/`psmisc`/`iproute2`), not the
 * base image.
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
    const val ROOTFS_TAG = "13"

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

    /**
     * npm registries in the order they are tried. The mainland mirror goes first because
     * the official registry is unreachable or very slow on some mobile networks; the
     * official registry stays as the fallback for devices outside mainland China.
     * Neither source may skip the sha512 check — see [platformPackage].
     */
    val NPM_SOURCES = listOf(
        NpmSource("npmmirror 国内镜像", "https://registry.npmmirror.com"),
        NpmSource("npm 官方源", "https://registry.npmjs.org"),
    )

    /** The CLI metadata package; its platform tarball is resolved separately. */
    private const val CLI_PACKAGE = "@anthropic-ai/claude-code"

    private const val DEBIAN_ARM64_DIGEST =
        "sha256:23b69c48ce5c2a6fb0681d959fa700427ad2a7cf7e6be5d25249170a1853d7ad"
    private const val DEBIAN_X86_64_DIGEST =
        "sha256:a6dd1c05fbba4f9028078a11f143cbb625d2408921eb8207bfa1dd1ad301276f"

    val ROOTFS_ARM64 = RootfsSpec(
        image = "debian:$ROOTFS_TAG",
        digest = DEBIAN_ARM64_DIGEST,
        sizeBytes = 49_778_604L,
        version = "13",
    )

    val ROOTFS_X86_64 = RootfsSpec(
        image = "debian:$ROOTFS_TAG",
        digest = DEBIAN_X86_64_DIGEST,
        sizeBytes = 49_412_508L,
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
        firstMetadata(NPM_SOURCES) { source ->
            val body = HttpUtil.getText(packageMetadataUrl(source, CLI_PACKAGE, version = null))
            json.parseToJsonElement(body).jsonObject["version"]!!.jsonPrimitive.content
        }
    }

    /** Tarball for the platform-specific binary package. */
    suspend fun platformPackage(pkg: String, version: String? = null): PackageInfo =
        withContext(Dispatchers.IO) {
            firstMetadata(NPM_SOURCES) { source ->
                val body = HttpUtil.getText(packageMetadataUrl(source, pkg, version))
                val obj = json.parseToJsonElement(body).jsonObject
                val dist = obj["dist"]!!.jsonObject
                PackageInfo(
                    version = obj["version"]!!.jsonPrimitive.content,
                    tarball = dist["tarball"]!!.jsonPrimitive.content,
                    integrity = dist["integrity"]?.jsonPrimitive?.content,
                )
            }
        }

    /**
     * `…/<pkg>/latest` or `…/<pkg>/<version>`. Kept pure so the mirror URL shape is
     * unit-tested instead of only appearing in an HTTP call.
     */
    internal fun packageMetadataUrl(source: NpmSource, pkg: String, version: String?): String =
        if (version == null) "${source.baseUrl}/$pkg/latest" else "${source.baseUrl}/$pkg/$version"

    /**
     * First successful [fetch] across [sources]; throws the last failure when all fail.
     *
     * Deliberately not `runCatching(...).getOrNull()`: a null result would let the caller
     * install "nothing", while the original exception (timeout, HTTP 404, malformed JSON)
     * is what the user needs to see. Kept generic and pure for unit tests.
     */
    internal fun <T> firstMetadata(sources: List<NpmSource>, fetch: (NpmSource) -> T): T {
        require(sources.isNotEmpty()) { "npm 源列表为空" }
        var last: Throwable? = null
        for (source in sources) {
            try {
                return fetch(source)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("npm 元数据下载失败")
    }
}

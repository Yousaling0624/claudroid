package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryTest {

    @Test
    fun `arm64 maps to the glibc arm64 cli and the debian arm64 layer`() {
        val target = Registry.targetForAbi("arm64-v8a")!!
        assertEquals("arm64-v8a", target.abi)
        assertEquals("@anthropic-ai/claude-code-linux-arm64", target.npmPackage)
        assertEquals("aarch64", target.arch)

        val rootfs = Registry.rootfsFor(target)
        assertEquals("debian:13-slim", rootfs.image)
        assertEquals("13", rootfs.version)
        assertEquals(Registry.ROOTFS_ARM64.digest, rootfs.digest)
    }

    @Test
    fun `x86_64 maps to the x64 package and layer`() {
        val target = Registry.targetForAbi("x86_64")!!
        assertEquals("@anthropic-ai/claude-code-linux-x64", target.npmPackage)
        assertEquals("x86_64", target.arch)
        assertEquals(Registry.ROOTFS_X86_64.digest, Registry.rootfsFor(target).digest)
    }

    @Test
    fun `32 bit and unknown abis are rejected`() {
        assertNull(Registry.targetForAbi("armeabi-v7a"))
        assertNull(Registry.targetForAbi("x86"))
        assertNull(Registry.targetForAbi(null))
    }

    @Test
    fun `both pinned rootfs digests are well formed and the layers are plausible`() {
        listOf(Registry.ROOTFS_ARM64, Registry.ROOTFS_X86_64).forEach { spec ->
            assertEquals(64, HttpUtil.digestHex(spec.digest).length)
            assertTrue(spec.digest.startsWith("sha256:"))
            // A debian:13-slim layer is ~30 MB; catches a copy/paste of a wrong image.
            assertTrue("unexpected layer size ${spec.sizeBytes}", spec.sizeBytes in 20_000_000L..40_000_000L)
        }
        // The two architectures must not share a digest.
        assertTrue(Registry.ROOTFS_ARM64.digest != Registry.ROOTFS_X86_64.digest)
    }

    @Test
    fun `blob url uses the registry content address`() {
        assertEquals(
            "https://registry-1.docker.io/v2/library/debian/blobs/sha256:abc",
            Registry.blobUrl("library/debian", "sha256:abc"),
        )
    }

    @Test
    fun `oci sources try docker hub first and the mirror as fallback`() {
        assertEquals(2, Registry.OCI_SOURCES.size)
        val (official, mirror) = Registry.OCI_SOURCES

        assertEquals("https://registry-1.docker.io", official.registry)
        assertEquals("https://auth.docker.io/token", official.tokenEndpoint)
        assertEquals("registry.docker.io", official.tokenService)

        assertEquals("https://docker.m.daocloud.io", mirror.registry)
        assertEquals("https://m.daocloud.io/auth/token", mirror.tokenEndpoint)
        assertEquals("docker.m.daocloud.io", mirror.tokenService)

        // Log lines must stay distinguishable without ever including a token.
        assertEquals(2, Registry.OCI_SOURCES.map { it.name }.distinct().size)
    }

    @Test
    fun `mirror keeps the same blob address and sends a scoped token query`() {
        val mirror = Registry.OCI_SOURCES[1]
        assertEquals(
            "https://docker.m.daocloud.io/v2/library/debian/blobs/sha256:abc",
            mirror.blobUrl("library/debian", "sha256:abc"),
        )
        val token = mirror.tokenUrl("library/debian")
        assertTrue(token.startsWith("https://m.daocloud.io/auth/token?"))
        assertTrue(token.contains("service=docker.m.daocloud.io"))
        // The exact shape verified against the mirror; ':' and '/' are valid in a query.
        assertTrue(token.contains("scope=repository:library/debian:pull"))
    }
}

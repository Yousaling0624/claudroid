package app.claudroid.engine

import android.content.Context
import android.net.ConnectivityManager
import app.claudroid.core.Installation
import app.claudroid.core.Logx
import app.claudroid.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Provisioning of the embedded backend.
 *
 * The CLI published on npm since 2.x is a self-contained native binary per platform
 * (no Node.js runtime involved), linked against the musl loader. We ship four things
 * into the jail:
 *
 *   1. the CLI binary            -> opt/claude/<version>/claude
 *   2. the musl dynamic loader   -> lib/ld-musl-<arch>.so.1   (Alpine `musl` package)
 *   3. a minimal userland        -> bin/busybox               (Alpine `busybox` package)
 *   4. jail config               -> etc/resolv.conf, etc/hosts, CA bundle
 *
 * With root the jail is assembled by a root shell under /data/adb/claudroid, because
 * Android 10+ forbids exec() of files inside the app's own data directory. Without root
 * we still assemble it locally (only useful for diagnosis).
 *
 * Caching rule (learned the hard way): a cached download is only reused when its sha512
 * still matches the registry value. A size check is not enough — an interrupted transfer
 * leaves a plausible-looking but truncated file, and a truncated ELF fails at runtime
 * with SIGBUS ("Bus error") instead of a clear error.
 */
class Toolchain(private val ctx: Context, private val rootExec: RootExec) {

    data class Progress(val stage: String, val fraction: Float, val detail: String = "")

    /** GNU bash + the Alpine libraries it links against, staged for the jail. */
    data class BashBundle(val bash: File, val libs: List<File>)

    suspend fun ensure(
        force: Boolean = false,
        useRoot: Boolean,
        onProgress: (Progress) -> Unit = {},
    ): Installation = withContext(Dispatchers.IO) {
        val target = Registry.target()
            ?: error("当前 CPU 架构不受支持：Claude Code 官方只发布 arm64 / x86_64 版本")

        onProgress(Progress("查询官方版本", 0.02f, target.npmPackage))
        val latest = Registry.latestCliVersion()
        val existing = Paths.installation(ctx)
        Logx.i("CLI 已安装=${existing?.version} 官方最新=$latest root=$useRoot", ctx)

        if (!force && existing != null && existing.version == latest && existing.rootMode == useRoot && verify(existing)) {
            onProgress(Progress("已是最新", 1f, latest))
            return@withContext existing
        }

        requireFreeSpace(1200)

        val staging = Paths.staging(ctx)
        onProgress(Progress("下载 CLI $latest", 0.05f, target.abi))
        val claude = stageClaude(target, latest, staging, onProgress)
        val loader = ensureLoader(target, staging, onProgress)
        val alpineIndex = ensureAlpineIndex(target, staging)
        val busybox = ensureBusybox(target, staging, alpineIndex, onProgress)
        val bash = ensureBash(target, staging, alpineIndex, onProgress)
        alpineIndex.delete()
        val ca = ensureCaBundle(staging)

        onProgress(Progress("组装运行环境", 0.94f, if (useRoot) "root jail" else "应用私有目录"))
        val jailPath = if (useRoot) {
            val dir = pickRootJail()
            assembleAsRoot(dir, latest, target.muslArch, claude, loader, busybox, bash, ca)
            dir
        } else {
            val dir = File(Paths.engine(ctx), "jail")
            assembleAsApp(dir, latest, target.muslArch, claude, loader, busybox, bash, ca)
            dir.absolutePath
        }

        val installation = Installation(
            version = latest,
            jailPath = jailPath,
            arch = target.muslArch,
            rootMode = useRoot,
            hasBash = bash != null,
        )
        Paths.saveInstallation(ctx, installation)

        // The 300 MB staging copy is no longer needed once the jail has it.
        runCatching { claude.delete() }
        onProgress(Progress("就绪", 1f, latest))
        Logx.i("CLI $latest 已激活（jail=$jailPath, root=$useRoot）", ctx)
        installation
    }

    /**
     * An installation only counts as healthy when the binary is executable *and* has a
     * plausible size. A truncated 306 MB binary still passes `test -x` and then dies with
     * SIGBUS, which is very hard to diagnose from the UI.
     */
    private fun verify(installation: Installation): Boolean {
        val q = rootExec::q
        val checks = listOf(
            installation.binary() to MIN_BINARY_BYTES,
            installation.loader() to MIN_LOADER_BYTES,
            installation.busybox() to MIN_LOADER_BYTES,
        )
        return if (!installation.rootMode) {
            checks.all { (file, min) -> file.isFile && file.length() > min }
        } else {
            checks.all { (file, min) ->
                val out = rootExec.run(
                    "test -x ${q(file.absolutePath)} && stat -c %s ${q(file.absolutePath)} 2>/dev/null || echo 0"
                ).out.trim().substringBefore('\n')
                (out.toLongOrNull() ?: 0L) > min
            }
        }
    }

    // ---------------------------------------------------------------- staging

    private suspend fun stageClaude(
        target: PlatformTarget,
        version: String,
        staging: File,
        onProgress: (Progress) -> Unit,
    ): File {
        val info = Registry.platformPackage(target.npmPackage, version)
        val tgz = File(staging, "claude-$version.tgz")
        val out = File(staging, "claude")
        val marker = File(staging, "claude-$version.ok")

        // Each upgrade leaves the previous 150 MB archive behind; keep only this version.
        staging.listFiles { f -> f.isFile && f.name.startsWith("claude-") && f.name.endsWith(".tgz") }
            ?.filter { it.absolutePath != tgz.absolutePath }
            ?.forEach { it.delete() }

        // Reuse only an extraction produced from a verified archive.
        if (out.isFile && marker.isFile && marker.readText().trim() == info.integrity &&
            out.length() > MIN_BINARY_BYTES
        ) {
            onProgress(Progress("复用已校验的安装包", 0.80f, humanBytes(out.length())))
            return out
        }
        marker.delete()
        out.delete()

        var cachedOk = false
        if (tgz.isFile) {
            onProgress(Progress("校验本地缓存", 0.06f))
            cachedOk = HttpUtil.matchesIntegrity(HttpUtil.sha512Base64(tgz), info.integrity)
            if (!cachedOk) {
                Logx.e("本地缓存已损坏（sha512 不匹配），重新下载", ctx)
                tgz.delete()
            }
        }
        if (!cachedOk) {
            val digest = HttpUtil.download(info.tarball, tgz) { read, total ->
                val frac = if (total > 0) read.toFloat() / total else 0f
                onProgress(Progress("下载 CLI $version", 0.05f + 0.70f * frac, humanBytes(read)))
            }
            if (!HttpUtil.matchesIntegrity(digest, info.integrity)) {
                tgz.delete()
                error("下载校验失败（sha512 与 npm 公布值不符），已删除缓存，请重试")
            }
            Logx.i("CLI 下载完成并通过 sha512 校验：${humanBytes(tgz.length())}", ctx)
        }

        onProgress(Progress("解包 CLI", 0.80f))
        val ok = TarGz.extractEntry(tgz, "package/claude", out)
        if (!ok) {
            out.delete()
            tgz.delete()
            error("安装包结构异常：未找到 package/claude")
        }
        if (out.length() <= MIN_BINARY_BYTES) {
            val got = out.length()
            out.delete()
            tgz.delete()
            error("解包结果不完整（$got 字节，期望约 300MB）。很可能是下载被中断，已清理缓存，请重试。")
        }
        out.setExecutable(true, false)
        marker.writeText(info.integrity ?: "none")
        // Keep the verified archive: switching between root/chroot and proot rebuilds the
        // jail from it without another 150 MB download.
        return out
    }

    private suspend fun ensureLoader(target: PlatformTarget, staging: File, onProgress: (Progress) -> Unit): File {
        val out = File(staging, "ld-musl-${target.muslArch}.so.1")
        if (out.isFile && out.length() > 100_000) return out
        onProgress(Progress("下载 musl 加载器", 0.86f))
        val url = Registry.muslLoaderUrl(target.alpineArch)
        Logx.i("musl 加载器: $url", ctx)
        val apk = File(staging, "musl.apk")
        HttpUtil.download(url, apk)
        val entry = "lib/ld-musl-${target.muslArch}.so.1"
        out.delete()
        if (!TarGz.extractEntry(apk, entry, out) || out.length() < 100_000) {
            out.delete()
            error("musl 包缺少 $entry")
        }
        out.setExecutable(true, false)
        apk.delete()
        return out
    }

    private fun ensureAlpineIndex(target: PlatformTarget, staging: File): File {
        val indexApk = File(staging, "APKINDEX.tar.gz")
        if (indexApk.isFile && indexApk.length() > 100_000) return indexApk
        val repo = alpineRepo(target)
        HttpUtil.download("$repo/APKINDEX.tar.gz", indexApk)
        return indexApk
    }

    private fun alpineRecord(indexApk: File, pkg: String): String {
        val index = TarGz.readTextEntry(indexApk, "APKINDEX") ?: error("APKINDEX 读取失败")
        return index.split("\n\n").firstOrNull { block ->
            block.lineSequence().any { it == "P:$pkg" }
        } ?: error("未在 APKINDEX 中找到 $pkg")
    }

    private fun alpineVersion(record: String): String =
        record.lineSequence().first { it.startsWith("V:") }.removePrefix("V:")

    private fun alpineRepo(target: PlatformTarget): String =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/${target.alpineArch}"

    private suspend fun ensureBusybox(
        target: PlatformTarget,
        staging: File,
        indexApk: File,
        onProgress: (Progress) -> Unit,
    ): File {
        val out = File(staging, "busybox")
        if (out.isFile && out.length() > 100_000) return out
        onProgress(Progress("准备 root 用户态", 0.90f))
        val repo = alpineRepo(target)
        val ver = alpineVersion(alpineRecord(indexApk, "busybox"))
        val apk = File(staging, "busybox.apk")
        HttpUtil.download("$repo/busybox-$ver.apk", apk)
        out.delete()
        if (!TarGz.extractEntry(apk, "bin/busybox", out) || out.length() < 100_000) {
            out.delete()
            error("busybox 包结构异常")
        }
        out.setExecutable(true, false)
        apk.delete()
        Logx.i("busybox $ver 已就绪", ctx)
        return out
    }

    /**
     * GNU bash + readline + ncursesw. The CLI's Bash tool expects bash; busybox only
     * ships ash. Optional: a failed download leaves the jail on /bin/sh.
     */
    private suspend fun ensureBash(
        target: PlatformTarget,
        staging: File,
        indexApk: File,
        onProgress: (Progress) -> Unit,
    ): BashBundle? = runCatching {
        val dir = File(staging, "bash-bundle")
        val bash = File(dir, "bin/bash")
        val libs = File(dir, "lib").listFiles()?.filter { it.isFile && it.length() > 10_000 } ?: emptyList()
        if (bash.isFile && bash.length() > 500_000 &&
            libs.any { it.name.startsWith("libreadline") } &&
            libs.any { it.name.startsWith("libncursesw") }
        ) {
            return BashBundle(bash, libs)
        }

        onProgress(Progress("准备 bash", 0.92f))
        val repo = alpineRepo(target)
        fun apk(name: String): File {
            val version = alpineVersion(alpineRecord(indexApk, name))
            val file = File(staging, "$name.apk")
            if (!file.isFile || file.length() < 1_000) {
                HttpUtil.download("$repo/$name-$version.apk", file)
            }
            return file
        }

        dir.deleteRecursively()
        val bashApk = apk("bash")
        val readlineApk = apk("readline")
        val ncursesApk = apk("libncursesw")

        TarGz.extractMatching(bashApk, dir) { entry ->
            if (entry.name == "bin/bash") "bin/bash" else null
        }
        TarGz.extractMatching(readlineApk, dir) { entry ->
            when {
                entry.name.startsWith("usr/lib/libreadline.so.") -> "lib/libreadline.so.8"
                entry.name.startsWith("usr/lib/libhistory.so.") -> "lib/libhistory.so.8"
                else -> null
            }
        }
        TarGz.extractMatching(ncursesApk, dir) { entry ->
            if (entry.name.startsWith("usr/lib/libncursesw.so.")) "lib/libncursesw.so.6" else null
        }

        val stagedBash = File(dir, "bin/bash")
        check(stagedBash.isFile && stagedBash.length() > 500_000) { "bash 解包结果异常" }
        stagedBash.setExecutable(true, false)
        val stagedLibs = File(dir, "lib").listFiles()?.filter { it.isFile && it.length() > 10_000 } ?: emptyList()
        check(stagedLibs.any { it.name.startsWith("libreadline") }) { "缺少 libreadline" }
        check(stagedLibs.any { it.name.startsWith("libncursesw") }) { "缺少 libncursesw" }
        Logx.i("bash 已就绪：${humanBytes(stagedBash.length())} + ${stagedLibs.size} 个库", ctx)
        BashBundle(stagedBash, stagedLibs)
    }.onFailure {
        Logx.e("bash 安装失败（将退回 /bin/sh）：${it.message}", ctx)
    }.getOrNull()

    private suspend fun ensureCaBundle(staging: File): File? {
        val out = File(staging, "ca-certificates.crt")
        if (out.isFile && out.length() > 50_000) return out
        return runCatching {
            HttpUtil.download("https://curl.se/ca/cacert.pem", out)
            out.takeIf { it.length() > 50_000 }
        }.onFailure { Logx.e("CA 根证书下载失败（可忽略）：${it.message}", ctx) }.getOrNull()
    }

    // ---------------------------------------------------------------- assembly

    private fun pickRootJail(): String {
        val candidates = listOf(Paths.ROOT_JAIL, Paths.ROOT_JAIL_FALLBACK)
        for (candidate in candidates) {
            val r = rootExec.run("mkdir -p '${rootExec.q(candidate)}' && test -w '${rootExec.q(candidate)}' && echo ok")
            if (r.out.contains("ok")) {
                Logx.i("jail 位置: $candidate", ctx)
                return candidate
            }
        }
        error("找不到可写的 jail 目录（/data/adb 与 /data/local/tmp 都不可写）")
    }

    /**
     * One script does the whole jail: mkdir, copy, chmod, symlinks, config files.
     * `set -e` plus an explicit source/destination size comparison matters: without it a
     * `cp` that runs out of space leaves a truncated binary and the script still exits 0.
     */
    private fun assembleAsRoot(
        jailPath: String,
        version: String,
        arch: String,
        claude: File,
        loader: File,
        busybox: File,
        bash: BashBundle?,
        ca: File?,
    ) {
        val q = rootExec::q
        val dirs = listOf(
            "opt/claude/$version", "lib", "bin", "etc/ssl/certs", "root", "work", "tmp",
            "dev", "proc", "sys", "system", "data", "sdcard", "usr/bin", "usr/lib",
            "apex", "vendor", "odm", "product",
        )
        val script = buildString {
            appendLine("set -e")
            appendLine("J=${q(jailPath)}")
            dirs.forEach { appendLine("mkdir -p \"\$J/$it\"") }
            appendLine("SRC=${q(claude.absolutePath)}")
            appendLine("DST=\"\$J/opt/claude/$version/claude\"")
            appendLine("cp -f \"\$SRC\" \"\$DST\"")
            appendLine("S1=\$(stat -c %s \"\$SRC\")")
            appendLine("S2=\$(stat -c %s \"\$DST\")")
            appendLine("[ \"\$S1\" = \"\$S2\" ] || { echo \"claude copy truncated: \$S1 -> \$S2\"; exit 11; }")
            appendLine("chmod 0755 \"\$DST\"")
            appendLine("cp -f ${q(loader.absolutePath)} \"\$J/lib/ld-musl-$arch.so.1\"")
            appendLine("chmod 0755 \"\$J/lib/ld-musl-$arch.so.1\"")
            appendLine("ln -sf ld-musl-$arch.so.1 \"\$J/lib/libc.musl-$arch.so.1\"")
            appendLine("cp -f ${q(busybox.absolutePath)} \"\$J/bin/busybox\"")
            appendLine("chmod 0755 \"\$J/bin/busybox\"")
            appendLine("for a in ${APPLETS.joinToString(" ")}; do ln -sf busybox \"\$J/bin/\$a\"; done")
            bash?.let { bundle ->
                appendLine("cp -f ${q(bundle.bash.absolutePath)} \"\$J/bin/bash\"")
                appendLine("chmod 0755 \"\$J/bin/bash\"")
                bundle.libs.forEach { lib ->
                    appendLine("cp -f ${q(lib.absolutePath)} \"\$J/usr/lib/${lib.name}\"")
                    appendLine("chmod 0644 \"\$J/usr/lib/${lib.name}\"")
                }
            }
            ca?.let {
                appendLine("cp -f ${q(it.absolutePath)} \"\$J/etc/ssl/certs/ca-certificates.crt\"")
            }
            appendLine("printf '%s\\n' ${q(dnsConfig())} > \"\$J/etc/resolv.conf\"")
            appendLine("printf '%s\\n' '127.0.0.1 localhost' '::1 localhost' > \"\$J/etc/hosts\"")
            appendLine("printf '%s\\n' 'root:x:0:0:root:/root:/bin/sh' > \"\$J/etc/passwd\"")
            appendLine("printf '%s\\n' 'root:x:0:' > \"\$J/etc/group\"")
            // keep only the active version (each one is ~300 MB)
            appendLine("for d in \"\$J\"/opt/claude/*; do")
            appendLine("  [ \"\$d\" = \"\$J/opt/claude/$version\" ] || rm -rf \"\$d\"")
            appendLine("done")
            appendLine("test -x \"\$DST\"")
            appendLine("echo JAIL_OK")
        }
        val result = rootExec.run(script, 240_000)
        if (!result.ok || !result.out.contains("JAIL_OK")) {
            error("jail 组装失败（exit=${result.code}）：${result.combined.takeLast(400)}")
        }
        Logx.i("jail 组装完成并校验尺寸：$jailPath", ctx)
    }

    /** Non-root variant: identical layout, written by the app itself. */
    private fun assembleAsApp(
        jail: File,
        version: String,
        arch: String,
        claude: File,
        loader: File,
        busybox: File,
        bash: BashBundle?,
        ca: File?,
    ) {
        listOf(
            "opt/claude/$version", "lib", "bin", "etc/ssl/certs", "root", "work", "tmp",
            "dev", "proc", "sys", "system", "data", "sdcard", "usr/bin", "usr/lib",
            "apex", "vendor", "odm", "product",
        ).forEach { File(jail, it).mkdirs() }

        val dst = File(jail, "opt/claude/$version/claude")
        Files.copy(claude.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        require(dst.length() == claude.length()) { "claude 复制不完整：${claude.length()} -> ${dst.length()}" }
        Files.copy(loader.toPath(), File(jail, "lib/ld-musl-$arch.so.1").toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(busybox.toPath(), File(jail, "bin/busybox").toPath(), StandardCopyOption.REPLACE_EXISTING)
        ca?.let { Files.copy(it.toPath(), File(jail, "etc/ssl/certs/ca-certificates.crt").toPath(), StandardCopyOption.REPLACE_EXISTING) }

        dst.setExecutable(true, false)
        File(jail, "lib/ld-musl-$arch.so.1").setExecutable(true, false)
        File(jail, "bin/busybox").setExecutable(true, false)

        symlink(File(jail, "lib/libc.musl-$arch.so.1"), "ld-musl-$arch.so.1")
        APPLETS.forEach { applet -> symlink(File(jail, "bin/$applet"), "busybox") }
        bash?.let { bundle ->
            val target = File(jail, "bin/bash")
            Files.copy(bundle.bash.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target.setExecutable(true, false)
            bundle.libs.forEach { lib ->
                val dstLib = File(jail, "usr/lib/${lib.name}")
                Files.copy(lib.toPath(), dstLib.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        File(jail, "etc/resolv.conf").writeText(dnsConfig() + "\n")
        File(jail, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(jail, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\n")
        File(jail, "etc/group").writeText("root:x:0:\n")

        File(jail, "opt/claude").listFiles()?.filter { it.isDirectory && it.name != version }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    // ---------------------------------------------------------------- helpers

    /** 306 MB binary + 150 MB archive + headroom; failing early beats a truncated copy. */
    private fun requireFreeSpace(needMb: Long) {
        val stat = android.os.StatFs(Paths.engine(ctx).absolutePath)
        val freeMb = stat.availableBytes / (1024 * 1024)
        if (freeMb < needMb) {
            error("存储空间不足：安装需要约 ${needMb}MB，当前可用 ${freeMb}MB。请清理后重试。")
        }
        Logx.i("可用空间 ${freeMb}MB（需要约 ${needMb}MB）", ctx)
    }

    /**
     * Android has no /etc/resolv.conf. Prefer the DNS servers of the current network;
     * the legacy properties are often empty on modern devices, so fall back to the
     * public resolvers the README already documents.
     */
    private fun dnsConfig(): String {
        val servers = mutableListOf<String>()
        runCatching {
            val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.activeNetwork?.let { network ->
                manager.getLinkProperties(network)?.dnsServers.orEmpty().forEach { address ->
                    address.hostAddress?.let { servers += it }
                }
            }
        }
        servers += listOfNotNull(prop("net.dns1"), prop("net.dns2"))
        servers += listOf("223.5.5.5", "119.29.29.29", "1.1.1.1")
        return servers.distinct().take(4).joinToString("\n") { "nameserver $it" } +
            "\noptions timeout:2 attempts:3"
    }

    private fun prop(name: String): String? = runCatching {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val v = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        v.ifBlank { null }
    }.getOrNull()

    private fun symlink(link: File, target: String) {
        runCatching {
            link.parentFile?.mkdirs()
            if (link.exists()) link.delete()
            Files.createSymbolicLink(link.toPath(), File(target).toPath())
        }
    }

    fun humanBytes(v: Long): String = when {
        v >= 1L shl 30 -> "%.1f GB".format(v.toDouble() / (1L shl 30))
        v >= 1L shl 20 -> "%.0f MB".format(v.toDouble() / (1L shl 20))
        v >= 1L shl 10 -> "%.0f KB".format(v.toDouble() / (1L shl 10))
        else -> "$v B"
    }

    companion object {
        /** Real binary is ~306 MB; anything under 100 MB is definitely truncated. */
        const val MIN_BINARY_BYTES = 100L * 1024 * 1024
        const val MIN_LOADER_BYTES = 100_000L

        val APPLETS = listOf(
            "sh", "ash", "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "ln",
            "chmod", "chown", "touch", "stat", "readlink", "realpath", "basename", "dirname",
            "grep", "sed", "awk", "find", "xargs", "sort", "uniq", "head", "tail", "wc",
            "echo", "printf", "env", "id", "ps", "kill", "sleep", "date", "tar", "gzip",
            "gunzip", "unzip", "df", "du", "mount", "umount", "test", "tr", "cut", "tee",
            "xxd", "od", "sha256sum", "md5sum", "wget", "which", "whoami", "pwd", "true", "false",
        )
    }
}

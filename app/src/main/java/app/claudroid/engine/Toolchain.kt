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
import java.util.concurrent.TimeUnit

/**
 * Provisioning of the embedded backend.
 *
 * The engine is no longer a hand-built Alpine mini-jail. We install a real Debian
 * rootfs (the official `debian:13-slim` OCI layer, pinned by digest) and run the
 * official Claude Code glibc build inside it under PRoot (no root) or chroot (root):
 *
 *   1. rootfs layer             -> filesDir/engine/jail (or /data/adb/claudroid)
 *   2. basic userland           -> apt-get install bash git grep coreutils sed awk
 *                                  tar gzip xz-utils curl ca-certificates less
 *   3. the CLI binary           -> opt/claude/<version>/claude
 *   4. jail config              -> etc/resolv.conf, etc/hosts, apt sources/retries
 *
 * The rootfs is downloaded at install time, never shipped in the APK. Both the OCI
 * layer digest (SHA-256 of the compressed blob) and the npm `sha512` integrity are
 * verified before anything is unpacked; a cached file is reused only when it re-hashes
 * to the same value. A truncated 30 MB layer or 300 MB ELF otherwise fails much later
 * with an opaque "Bus error".
 *
 * With root the jail is assembled by a root shell under /data/adb/claudroid, because
 * Android 10+ forbids exec() of files inside the app's own data directory.
 */
class Toolchain(private val ctx: Context, private val rootExec: RootExec) {

    data class Progress(val stage: String, val fraction: Float, val detail: String = "")

    private data class Exec(val code: Int, val out: String, val err: String) {
        val combined: String get() = (out + if (err.isBlank()) "" else "\n$err").trim()
        val ok: Boolean get() = code == 0
    }

    suspend fun ensure(
        force: Boolean = false,
        useRoot: Boolean,
        onProgress: (Progress) -> Unit = {},
    ): Installation = withContext(Dispatchers.IO) {
        val target = Registry.target()
            ?: error("当前 CPU 架构不受支持：Claude Code 官方只发布 arm64 / x86_64 版本")
        val spec = Registry.rootfsFor(target)

        onProgress(Progress("查询官方版本", 0.02f, target.npmPackage))
        val latest = Registry.latestCliVersion()
        val existing = Paths.installation(ctx)
        Logx.i(
            "CLI 已安装=${existing?.version} 官方最新=$latest root=$useRoot " +
                "rootfs=${existing?.rootfsVersion ?: "旧版"}",
            ctx,
        )

        if (!force && existing != null && existing.version == latest &&
            existing.rootMode == useRoot && existing.rootfsSha256 == spec.digest &&
            verify(existing, useRoot)
        ) {
            onProgress(Progress("已是最新", 1f, latest))
            return@withContext existing
        }

        // A Debian rootfs with the pinned digest and working tools only needs the CLI
        // replaced. Anything else (Alpine-era install, changed rootfs, broken tools) is
        // rebuilt from scratch, which is also the migration path.
        val canUpgrade = !force && existing != null && existing.rootMode == useRoot &&
            existing.rootfsVersion == spec.version && existing.rootfsSha256 == spec.digest &&
            verify(existing, useRoot)
        // Peak: 30 MB layer -> ~115 MB tree, ~300 MB CLI binary, a second copy in the
        // jail, plus apt downloads. Upgrading only adds a new CLI on top of an existing
        // jail, hence the much smaller number.
        requireFreeSpace(if (canUpgrade) 700 else 1200)

        val staging = Paths.staging(ctx)
        onProgress(Progress("下载 CLI $latest", 0.05f, target.abi))
        val claude = stageClaude(target, latest, staging, onProgress)

        if (canUpgrade) {
            onProgress(Progress("更新 CLI", 0.84f, latest))
            val updated = upgradeInPlace(existing, claude, latest, onProgress)
            cleanupStaging(claude)
            onProgress(Progress("就绪", 1f, latest))
            Logx.i("CLI $latest 已更新（jail=${updated.jailPath}, root=$useRoot）", ctx)
            return@withContext updated
        }

        onProgress(Progress("下载 Debian rootfs", 0.46f, spec.image))
        val archive = stageRootfs(spec, staging, onProgress)

        onProgress(Progress("组装 Debian 容器", 0.86f, if (useRoot) "root jail" else "应用私有目录"))
        val installation = fullInstall(target, spec, latest, claude, archive, useRoot, onProgress)

        // Everything below runs against the `.new` tree. Until it verifies, install.json
        // and the current jail are untouched; any failure just drops `.new`.
        val activated = try {
            onProgress(Progress("安装容器工具（apt）", 0.92f, "bash git grep …"))
            val tools = runGuest(installation, toolsScript(latest), 900_000)
            if (!tools.ok || !tools.combined.contains("TOOLS_OK") || !tools.combined.contains("CLAUDE_OK")) {
                throw IllegalStateException(toolsFailure(tools))
            }
            if (!verify(installation, useRoot)) {
                throw IllegalStateException("安装后自检失败：Debian 标记、bash、git 或 CLI 缺失。请在「终端」里检查，或重试安装。")
            }
            activate(installation, latest)
        } catch (t: Throwable) {
            discardTree(installation.jailPath, useRoot)
            if (useRoot) runCatching { File(staging, "rootfs-stage").deleteRecursively() }
            throw t
        }

        Paths.saveInstallation(ctx, activated)
        // Only now may the superseded locations be reclaimed: until the line above,
        // install.json still pointed at the *previous* install, which may be exactly one
        // of these trees. Deleting it first and dying before the save would leave a
        // dangling pointer (next launch: full reinstall) and an orphaned new jail.
        reclaimSuperseded(activated)
        cleanupStaging(claude)
        onProgress(Progress("就绪", 1f, latest))
        Logx.i("Debian ${spec.version} + CLI $latest 已激活（jail=${activated.jailPath}, root=$useRoot）", ctx)
        activated
    }

    /**
     * An installation counts as healthy only when the Debian marker, bash, git and a
     * plausible CLI are all there. `test -x` alone passes for a truncated binary, which
     * then dies with SIGBUS, so the size check stays.
     *
     * `internal` so [EngineManager] uses the exact same definition instead of drifting.
     */
    internal fun verify(installation: Installation, useRoot: Boolean): Boolean {
        val binary = installation.binary()
        if (!useRoot) {
            return installation.isDebian() &&
                binary.isFile && binary.length() > MIN_BINARY_BYTES &&
                installation.bash().isFile &&
                File(installation.jail(), "usr/bin/git").isFile
        }
        val q = rootExec::q
        val jail = q(installation.jailPath)
        val out = rootExec.run(
            "test -f $jail/etc/debian_version && " +
                "test -x $jail/bin/bash && " +
                "test -x $jail/usr/bin/git && " +
                "S=\$(stat -c %s ${q(binary.absolutePath)} 2>/dev/null || echo 0) && " +
                "[ \"\$S\" -gt $MIN_BINARY_BYTES ] && echo OK",
            30_000,
        ).out
        return out.contains("OK")
    }

    // ---------------------------------------------------------------- staging

    private suspend fun stageClaude(
        target: PlatformTarget,
        version: String,
        staging: File,
        onProgress: (Progress) -> Unit,
    ): File {
        val info = runCatching { Registry.platformPackage(target.npmPackage, version) }
            .getOrElse {
                throw IllegalStateException(
                    "官方未发布对应的 Linux glibc 安装包（${target.npmPackage} $version）。" +
                        "请检查网络后重试；如果持续失败，稍后再升级。",
                    it,
                )
            }
        // Fail closed *before* the 150 MB download: without a published sha512 there is
        // nothing to verify the binary against, and an unverified ELF in the jail is
        // worse than no install at all.
        val integrity = info.integrity
        if (integrity.isNullOrBlank()) {
            error(
                "镜像未提供 sha512 摘要（${target.npmPackage} $version 的 dist.integrity 为空），" +
                    "已拒绝安装。请检查网络（可能是代理改写了 registry 响应）后重试。",
            )
        }
        val tgz = File(staging, "claude-$version.tgz")
        val out = File(staging, "claude")
        val marker = File(staging, "claude-$version.ok")

        // Each upgrade leaves the previous ~150 MB archive behind; keep only this version.
        staging.listFiles { f -> f.isFile && f.name.startsWith("claude-") && f.name.endsWith(".tgz") }
            ?.filter { it.absolutePath != tgz.absolutePath }
            ?.forEach { it.delete() }

        // Reuse only an extraction produced from a verified archive.
        if (out.isFile && marker.isFile && marker.readText().trim() == integrity &&
            out.length() > MIN_BINARY_BYTES
        ) {
            onProgress(Progress("复用已校验的安装包", 0.42f, humanBytes(out.length())))
            return out
        }
        marker.delete()
        out.delete()

        var cachedOk = false
        if (tgz.isFile) {
            onProgress(Progress("校验本地缓存", 0.06f))
            cachedOk = HttpUtil.matchesIntegrity(HttpUtil.sha512Base64(tgz), integrity)
            if (!cachedOk) {
                Logx.e("本地缓存已损坏（sha512 不匹配），重新下载", ctx)
                tgz.delete()
            }
        }
        if (!cachedOk) {
            val digest = HttpUtil.download(info.tarball, tgz) { read, total ->
                val frac = if (total > 0) read.toFloat() / total else 0f
                onProgress(Progress("下载 CLI $version", 0.05f + 0.30f * frac, humanBytes(read)))
            }
            // A missing integrity was already rejected above, so reaching this branch
            // always means the bytes really differ from the published digest.
            if (!HttpUtil.matchesIntegrity(digest, integrity)) {
                tgz.delete()
                error("下载校验失败（sha512 与 npm 公布值不符），已删除缓存，请重试")
            }
            Logx.i("CLI 下载完成并通过 sha512 校验：${humanBytes(tgz.length())}", ctx)
        }

        onProgress(Progress("解包 CLI", 0.38f))
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
        marker.writeText(integrity)
        // Keep the verified archive: rebuilding the jail (root <-> proot) must not
        // download another 150 MB.
        return out
    }

    /** Downloads (or revalidates) the pinned Debian layer and returns the local archive. */
    private fun stageRootfs(
        spec: RootfsSpec,
        staging: File,
        onProgress: (Progress) -> Unit,
    ): File {
        val expected = HttpUtil.digestHex(spec.digest)
        if (expected.isEmpty()) error("内置 rootfs 摘要无效，请更新应用")
        val name = "debian-${spec.version}-${expected.take(12)}.tar.gz"
        val dest = File(staging, name)

        // A bump of the pinned digest leaves the old layer behind; it is never used again.
        staging.listFiles { f ->
            f.isFile && f.name.startsWith("debian-") && f.name.endsWith(".tar.gz") &&
                f.name != dest.name
        }?.forEach { runCatching { it.delete() } }

        if (dest.isFile) {
            onProgress(Progress("校验 rootfs 缓存", 0.46f, humanBytes(dest.length())))
            if (dest.length() == spec.sizeBytes && HttpUtil.sha256Hex(dest) == expected) {
                return dest
            }
            Logx.e("rootfs 缓存损坏（sha256/尺寸不匹配），重新下载", ctx)
            dest.delete()
        }
        // A previous attempt may have died between download and delete, leaving a
        // partial layer under the same name; the retry must not append to it.
        if (dest.exists()) dest.delete()

        val got = try {
            // Official registry first, then the DaoCloud mirror. Each is tried once with
            // a short connect timeout, so a poisoned auth.docker.io cannot stall the
            // install; every source is verified against the same pinned digest below.
            HttpUtil.downloadOciBlob(Registry.ROOTFS_REPOSITORY, spec.digest, dest) { read, total ->
                val frac = if (total > 0) read.toFloat() / total else 0f
                onProgress(Progress("下载 Debian rootfs", 0.46f + 0.34f * frac, humanBytes(read)))
            }
        } catch (t: Throwable) {
            // Never leave a partial layer behind: the cache is only reused after a
            // full re-hash, and deleting here also frees the space for the retry.
            runCatching { dest.delete() }
            throw IllegalStateException(
                "Debian rootfs 下载失败（官方源与镜像均不可达或校验不通过）。" +
                    "请检查网络后重试；错误：${t.message}",
                t,
            )
        }
        if (got.sha256 != expected || (spec.sizeBytes > 0 && dest.length() != spec.sizeBytes)) {
            dest.delete()
            error("Debian rootfs 校验失败（sha256 与内置摘要不符），已删除损坏文件，请重试")
        }
        Logx.i(
            "Debian ${spec.image} 已从 ${got.source} 下载并通过 sha256 校验：" +
                "${humanBytes(got.bytes)}",
            ctx,
        )
        return dest
    }

    // ---------------------------------------------------------------- assembly

    /**
     * Builds a complete Debian tree under the *temporary* name (`jail.new`), leaving the
     * current jail alone. The caller runs apt and verify against this tree and only then
     * calls [activate]; a failure anywhere before the swap simply removes `.new`, so a
     * reinstall can no longer destroy a working install first (the old code `rm -rf`'d
     * the jail up front).
     */
    private fun fullInstall(
        target: PlatformTarget,
        spec: RootfsSpec,
        version: String,
        claude: File,
        archive: File,
        useRoot: Boolean,
        onProgress: (Progress) -> Unit,
    ): Installation {
        val staging = Paths.staging(ctx)
        val newJail = if (useRoot) {
            pickRootJail() + NEW_SUFFIX
        } else {
            File(Paths.engine(ctx), "jail$NEW_SUFFIX").absolutePath
        }
        val extraction = if (useRoot) {
            // Root copies from app-private storage into /data/adb; keep the unpacked tree
            // outside the jail name so a root-side failure has nothing to roll back.
            File(staging, "rootfs-stage").apply { deleteRecursively(); mkdirs() }
        } else {
            // `jail.new` can be left behind by an interrupted attempt, and a chroot-era
            // mount may still hang below it: sweep mounts before removing the tree.
            File(newJail).apply { discardTree(absolutePath, useRoot = false); mkdirs() }
        }

        try {
            onProgress(Progress("解包 Debian rootfs", 0.88f, humanBytes(archive.length())))
            val entries = TarGz.extractAll(archive, extraction) { read, _ ->
                val frac = (read.toFloat() / (archive.length() * 5)).coerceIn(0f, 1f)
                onProgress(Progress("解包 Debian rootfs", 0.88f + 0.04f * frac, humanBytes(read)))
            }
            Logx.i("rootfs 解包完成：$entries 个条目 -> ${extraction.absolutePath}", ctx)

            configureJail(extraction, version, spec)
            installCli(extraction, claude, version)
            if (useRoot) {
                assembleAsRoot(newJail, extraction, version)
                runCatching { extraction.deleteRecursively() }
            }

            val installation = Installation(
                version = version,
                jailPath = newJail,
                arch = target.arch,
                rootMode = useRoot,
                hasBash = true,
                rootfs = spec.image,
                rootfsVersion = spec.version,
                rootfsSha256 = spec.digest,
            )
            pruneVersions(installation, version)
            return installation
        } catch (t: Throwable) {
            // install.json still points at the old jail; never leave a half tree behind
            // (it would also make the next attempt reuse stale files).
            discardTree(newJail, useRoot)
            if (useRoot) runCatching { extraction.deleteRecursively() }
            throw t
        }
    }

    /**
     * Swaps the verified `.new` tree into the official path.
     *
     * Both names are siblings, so the rename is atomic; `install.json` is written by the
     * caller afterwards, which keeps a failed swap pointing at a still-existing install.
     * Reclaiming the *previous* install location is deliberately not part of this step:
     * the caller does it only after the new pointer is on disk (see [reclaimSuperseded]).
     */
    private fun activate(installation: Installation, version: String): Installation {
        val newPath = installation.jailPath
        require(newPath.endsWith(NEW_SUFFIX)) { "不是临时 jail：$newPath" }
        val basePath = newPath.removeSuffix(NEW_SUFFIX)

        if (installation.rootMode) {
            swapAsRoot(basePath, newPath, version)
        } else {
            swapLocal(basePath, newPath)
        }
        return installation.copy(jailPath = basePath)
    }

    /**
     * Deletes the locations the just-activated install superseded: a PRoot-era tree under
     * app data (unreachable in root mode), the other root candidate, and a root-era tree
     * (unreadable without root) after a switch to PRoot.
     *
     * Runs *after* `Paths.saveInstallation`, so a crash in between can only leave a stale
     * tree behind, never a pointer at a deleted one. Failures are logged with the path
     * (the delete helpers already refuse rather than risk walking into a mount); a
     * leftover tree costs disk space but breaks nothing, so the install still succeeds.
     */
    private fun reclaimSuperseded(activated: Installation) {
        if (activated.rootMode) {
            discardTree(File(Paths.engine(ctx), "jail").absolutePath, useRoot = false)
            // The other root candidate may hold an install from a previous run (e.g.
            // /data/local/tmp before /data/adb became writable).
            listOf(Paths.ROOT_JAIL, Paths.ROOT_JAIL_FALLBACK)
                .filter { it != activated.jailPath }
                .forEach { discardTree(it, useRoot = true) }
        } else if (rootExec.isGranted()) {
            // Switching root -> PRoot: the root jail is no longer used. deleteJail re-reads
            // the live mount table first; a leftover chroot mount must never be walked
            // into by `rm -rf` (that is what deleted /data on a real device).
            listOf(Paths.ROOT_JAIL, Paths.ROOT_JAIL_FALLBACK)
                .forEach { discardTree(it, useRoot = true) }
        }
    }

    private fun swapLocal(basePath: String, newPath: String) {
        val base = File(basePath)
        val old = File(basePath + OLD_SUFFIX)
        // A leftover sibling can still carry a chroot-era mount; sweep before deleting.
        if (old.exists()) discardTree(old.absolutePath, useRoot = false)
        if (base.exists() && !base.renameTo(old)) {
            error("无法交接旧 jail（${base.absolutePath} → ${old.absolutePath}），旧安装保持不变")
        }
        if (!File(newPath).renameTo(base)) {
            // Put the old tree back under its official name; the install.json pointer
            // never changed, so the app keeps working.
            runCatching { if (old.exists() && !base.exists()) old.renameTo(base) }
            error("无法启用新 jail（$newPath → $basePath），已回滚，旧安装仍可用")
        }
        discardTree(old.absolutePath, useRoot = false)
        Logx.i("新 jail 已启用：$basePath", ctx)
    }

    /** Root variant of [swapLocal]; the checks run on `.new` before the old tree moves. */
    private fun swapAsRoot(basePath: String, newPath: String, version: String) {
        val q = rootExec::q
        // A `.old` left by an interrupted swap must be gone before `mv "$J" "$O"`, or the
        // move would nest the live jail inside it. If the sweep is refused (a live chroot
        // session, or a mount it could not detach) the swap must not run at all: `mv` into
        // a non-empty `.old` would silently bury the current jail and still print SWAP_OK.
        if (!discardTree(basePath + OLD_SUFFIX, useRoot = true)) {
            error(
                "无法清理上一代 jail（${basePath + OLD_SUFFIX}）：仍有 chroot 会话或挂载在使用它。" +
                    "请先关闭「终端」里正在运行的命令，稍后重试。旧安装保持不变。",
            )
        }
        val script = swapScript(
            jail = q(basePath),
            next = q(newPath),
            old = q(basePath + OLD_SUFFIX),
            version = version,
        )
        val result = rootExec.run(script, 300_000)
        if (!result.ok || !result.out.contains("SWAP_OK")) {
            error("启用新 jail 失败（exit=${result.code}）：${result.combined.takeLast(400)}")
        }
        // Best-effort: the swap already succeeded and install.json is about to point at
        // the new jail, so a refused delete only costs disk space. [discardTree] already
        // logged the path and the cause, so the result is only consumed, not re-logged.
        discardTree(basePath + OLD_SUFFIX, useRoot = true)
        Logx.i("新 jail 已启用：$basePath", ctx)
    }

    /**
     * Deletes a tree under construction or a superseded install.
     *
     * Every jail tree removal goes through [RootExec.deleteJail] (root trees) or
     * [RootExec.deleteTree] (app-private trees): both unmount the binds a chroot session
     * left below the tree before deleting. Deleting a mountpoint directly descends into
     * the host directory behind it (that wiped `/data` on a real device), so this is not
     * a place for a plain `deleteRecursively`.
     *
     * Returns true when the tree is gone (or never existed). False means the delete was
     * refused — mounts left, unreadable mount table, or a live chroot session — so a
     * caller whose next step depends on the path being free must abort instead of
     * carrying on. Most call sites only log, because a leftover tree is harmless there.
     */
    private fun discardTree(path: String, useRoot: Boolean): Boolean {
        if (useRoot) {
            val result = runCatching { rootExec.deleteJail(path) }
                .onFailure { Logx.e("删除 jail 失败：$path（${it.message}）", ctx) }
                .getOrNull()
            if (result == null) return false
            if (!result.ok) {
                Logx.e("删除 jail 失败：$path（${result.combined.takeLast(200)}）", ctx)
                return false
            }
            return true
        }
        // App-private trees (PRoot mode) can still carry mounts from a chroot install
        // performed while root was available, so the app-side delete is guarded too.
        if (!rootExec.deleteTree(path)) {
            Logx.e("删除 jail 失败：$path（目录仍在）", ctx)
            return false
        }
        return true
    }

    /**
     * Everything Debian needs to work on Android: DNS, hosts, hostname, mount points and
     * a deterministic apt configuration. Written into the staged tree before it is
     * copied into place.
     */
    private fun configureJail(root: File, version: String, spec: RootfsSpec) {
        listOf(
            "work", "root", "tmp", "dev", "proc", "sys", "system", "data", "sdcard",
            "apex", "vendor", "odm", "product", "opt/claude/$version",
            "etc/ssl/certs", "etc/apt/apt.conf.d",
            "var/lib/apt/lists/partial", "var/cache/apt/archives/partial",
        ).forEach { File(root, it).mkdirs() }

        // resolv.conf + gai.conf come from the same helper that refreshes an existing
        // install ([refreshNetworkConfig]), so the fix cannot land in only one of them.
        networkFiles().forEach { (relative, content) -> File(root, relative).writeText(content) }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/hostname").writeText("claudroid\n")
        File(root, "etc/claudroid-rootfs").writeText(
            "${spec.image} ${spec.digest} sha256-verified ${spec.sizeBytes} bytes\n"
        )

        // Debian 13 ships deb822 sources pointing at the same mirror; rewrite them so the
        // suite names are explicit and support can see exactly what we use. Plain http is
        // intentional: ca-certificates is only installed afterwards.
        File(root, "etc/apt/sources.list.d/debian.sources").writeText(
            """
            Types: deb
            URIs: http://deb.debian.org/debian
            Suites: trixie trixie-updates
            Components: main
            Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp

            Types: deb
            URIs: http://deb.debian.org/debian-security
            Suites: trixie-security
            Components: main
            Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp
            """.trimIndent() + "\n"
        )

        // PRoot presents the app uid as root; apt's _apt sandbox user then cannot open
        // its own files, hence Sandbox::User. Retries/timeouts make flaky mobile
        // networks much less fatal.
        File(root, "etc/apt/apt.conf.d/99claudroid").writeText(
            """
            APT::Sandbox::User "root";
            Acquire::Retries "3";
            Acquire::http::Timeout "30";
            Acquire::https::Timeout "30";
            DPkg::Lock::Timeout "60";
            """.trimIndent() + "\n"
        )
    }

    /** Copy the CLI into opt/claude/<version> with a same-directory rename. */
    private fun installCli(jail: File, claude: File, version: String) {
        val dir = File(jail, "opt/claude/$version").apply { mkdirs() }
        val dst = File(dir, "claude")
        val tmp = File(dir, "claude.tmp")
        Files.copy(claude.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        require(tmp.length() == claude.length()) {
            "claude 复制不完整：${claude.length()} -> ${tmp.length()}"
        }
        tmp.setExecutable(true, false)
        if (!tmp.renameTo(dst)) {
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            dst.setExecutable(true, false)
        }
    }

    /**
     * Drops stale CLI versions from `opt/claude/`, keeping the one just installed.
     *
     * Both branches re-check the mount table first: `opt/claude/<version>` is normally
     * mount-free, but it lives inside the jail, and a chroot session may have left a bind
     * there. `rm -rf` through such a mountpoint deletes the *host* directory behind it —
     * the same failure mode as deleting a jail root, which is why this must never be a
     * bare recursive delete.
     */
    private fun pruneVersions(installation: Installation, version: String) {
        val keep = "${installation.jailPath}/opt/claude/$version"
        if (!installation.rootMode) {
            val versions = File(installation.jail(), "opt/claude")
            // Unreadable table proves nothing, so it blocks the delete (fail-closed).
            val mountsText = runCatching { File("/proc/mounts").readText() }.getOrNull()
            versions.listFiles()
                ?.filter { (it.isDirectory || Files.isSymbolicLink(it.toPath())) && it.absolutePath != keep }
                ?.forEach { dir ->
                    val mounts = mountsText?.let { MountTable.mountsUnder(it, dir.absolutePath) }
                    // null (unreadable table) and a non-empty list both block the delete:
                    // only a confirmed mount-free directory may be removed.
                    when {
                        mounts == null ->
                            Logx.e("跳过清理旧 CLI 版本：${dir.absolutePath}（无法读取 /proc/mounts，不能确认无挂载）", ctx)
                        mounts.isNotEmpty() ->
                            Logx.e("跳过清理旧 CLI 版本：${dir.absolutePath} 仍有挂载 ${mounts.joinToString(", ")}", ctx)
                        // A link is unlinked, never walked: deleteRecursively would descend
                        // into its target (the same hazard as replacing a symlink in TarGz).
                        Files.isSymbolicLink(dir.toPath()) -> dir.delete()
                        else -> runCatching { dir.deleteRecursively() }
                            .onFailure { Logx.e("清理旧 CLI 版本失败：${dir.absolutePath}（${it.message}）", ctx) }
                    }
                }
        } else {
            val result = rootExec.run(MountTable.pruneScript("${installation.jailPath}/opt/claude", keep), 60_000)
            // Every surviving version is named, so the log explains the leftover instead of
            // leaving a silent gap; a missing PRUNE_DONE means the script aborted (no mount
            // table, su refused, timeout) and nothing was pruned.
            result.out.lineSequence()
                .filter { it.startsWith("SKIP_MOUNTED=") }
                .forEach { Logx.e("跳过清理旧 CLI 版本：${it.removePrefix("SKIP_MOUNTED=")} 仍有挂载", ctx) }
            if (result.out.contains("NO_MOUNT_TABLE")) {
                Logx.e("跳过清理旧 CLI 版本：root 侧无法读取 /proc/mounts，不能确认无挂载", ctx)
            } else if (!result.out.contains("PRUNE_DONE")) {
                Logx.e("清理旧 CLI 版本未完成（exit=${result.code}）：${result.combined.takeLast(200)}", ctx)
            }
        }
    }

    /**
     * Root variant of the final move: the app can read its staging tree but only root
     * may write /data/adb. `cp -a` preserves modes and symlinks; the size/marker checks
     * turn a full disk or interrupted copy into a clear failure instead of a jail that
     * "looks installed".
     */
    private fun assembleAsRoot(jailPath: String, staged: File, version: String) {
        val q = rootExec::q
        val bb = rootExec.busybox()
        val chown = if (bb != null) "$bb chown" else "chown"
        // `cp -a` needs an empty target, and the previous tree must not be deleted by a
        // plain `rm -rf`: a chroot session leaves host binds mounted below the jail, and
        // removing a mountpoint descends into the host directory behind it.
        val cleared = rootExec.deleteJail(jailPath)
        if (!cleared.ok) {
            error("清理旧 jail 失败（exit=${cleared.code}）：${cleared.combined.takeLast(300)}")
        }
        val script = buildString {
            appendLine("set -e")
            appendLine("J=${q(jailPath)}")
            appendLine("SRC=${q(staged.absolutePath)}")
            appendLine("mkdir -p \"\$J\"")
            appendLine("if ! cp -a \"\$SRC/.\" \"\$J/\"; then cp -R -p \"\$SRC/.\" \"\$J/\"; fi")
            // -h: never follow symlinks such as /etc/alternatives/awk -> /usr/bin/mawk
            // (an absolute target would otherwise point outside the jail on the host).
            appendLine("$chown -Rh 0:0 \"\$J\" 2>/dev/null || true")
            appendLine("chmod 1777 \"\$J/tmp\" 2>/dev/null || true")
            appendLine("chmod 0700 \"\$J/root\" 2>/dev/null || true")
            appendLine("test -f \"\$J/etc/debian_version\"")
            appendLine("test -x \"\$J/bin/bash\"")
            appendLine("test -x \"\$J/opt/claude/$version/claude\"")
            appendLine("echo ROOTFS_OK")
        }
        val result = rootExec.run(script, 600_000)
        if (!result.ok || !result.out.contains("ROOTFS_OK")) {
            error("Debian 容器复制失败（exit=${result.code}）：${result.combined.takeLast(400)}")
        }
        Logx.i("Debian 容器已复制到 $jailPath（chown root:root）", ctx)
    }

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

    // ---------------------------------------------------------------- upgrade

    /**
     * Same Debian rootfs, new CLI: copy the binary, run the tool check/apt pass, verify,
     * and only then prune the old version. If apt fails the old CLI is still intact and
     * install.json still points at it, so the app keeps working.
     */
    private fun upgradeInPlace(
        existing: Installation,
        claude: File,
        version: String,
        onProgress: (Progress) -> Unit,
    ): Installation {
        val updated = existing.copy(
            version = version,
            hasBash = true,
            installedAt = System.currentTimeMillis(),
        )
        if (existing.rootMode) {
            val q = rootExec::q
            val dir = "${existing.jailPath}/opt/claude/$version"
            val dst = "$dir/claude"
            val result = rootExec.run(
                "set -e; mkdir -p ${q(dir)}; cp -f ${q(claude.absolutePath)} ${q(dst)}; " +
                    "chmod 0755 ${q(dst)}; " +
                    "S1=\$(stat -c %s ${q(claude.absolutePath)}); S2=\$(stat -c %s ${q(dst)}); " +
                    "[ \"\$S1\" = \"\$S2\" ]; echo COPY_OK",
                300_000,
            )
            if (!result.ok || !result.out.contains("COPY_OK")) {
                error("CLI 复制到 root jail 失败（exit=${result.code}）：${result.combined.takeLast(300)}")
            }
        } else {
            installCli(existing.jail(), claude, version)
        }

        val tools = runGuest(updated, toolsScript(version), 900_000)
        if (!tools.ok || !tools.combined.contains("TOOLS_OK") || !tools.combined.contains("CLAUDE_OK")) {
            throw IllegalStateException(toolsFailure(tools))
        }
        if (!verify(updated, existing.rootMode)) {
            throw IllegalStateException("更新后自检失败：CLI 不可执行或容器工具缺失，已保留旧版本。")
        }
        // Pointer first, pruning second: install.json still named the *old* version until
        // this line, and pruning removes exactly that binary. Dying in between would
        // leave a pointer at a deleted CLI (next launch: full reinstall).
        Paths.saveInstallation(ctx, updated)
        pruneVersions(updated, version)
        onProgress(Progress("CLI 已更新", 0.98f, version))
        return updated
    }

    // ---------------------------------------------------------------- guest execution

    /** Runs a shell script inside the Debian container (PRoot or chroot). */
    private fun runGuest(installation: Installation, script: String, timeoutMs: Long): Exec {
        val env = guestEnv()
        if (!installation.rootMode) {
            val spec = ProotRunner(ctx).runSpec(
                installation,
                listOf("/bin/sh", "-c", script),
                env,
                workDir = "/root",
            )
            return runLocal(spec.argv, spec.env, spec.cwd, timeoutMs)
        }

        val su = rootExec.suBinary()
            ?: return Exec(127, "", "找不到 su，无法在 root 模式运行容器命令")
        val engineDir = Paths.engine(ctx)
        return if (rootExec.chrootReady(installation.jailPath)) {
            val bb = rootExec.busybox()
                ?: return Exec(127, "", "缺少 busybox chroot")
            val wrapper = File(engineDir, "provision-chroot.sh")
            wrapper.writeText(
                TurnScripts.chrootShell(
                    env = env,
                    jail = installation.jailPath,
                    workspace = Paths.workspace(ctx).absolutePath,
                    home = Paths.home(ctx).absolutePath,
                    chrootTool = bb,
                    guestScript = script,
                    pidFile = null,
                )
            )
            val r = try {
                rootExec.unmountJail(installation.jailPath)
                rootExec.chrootBegin()
                rootExec.execute(listOf(su, "-c", "sh ${wrapper.absolutePath}"), timeoutMs)
            } finally {
                // The provisioning chroot binds workspace/home into the jail; those mounts
                // survive the process and must not be left for a later `rm -rf` to walk
                // into (that is what wiped /data on a real device).
                rootExec.chrootEnd()
                rootExec.unmountJail(installation.jailPath)
            }
            Exec(r.code, r.out, r.err)
        } else {
            // Root but no usable chroot(1): run PRoot through su, exactly like the turn
            // path will. Better than failing the install outright.
            val spec = ProotRunner(ctx).runSpec(
                installation,
                listOf("/bin/sh", "-c", script),
                env,
                workDir = "/root",
            )
            val wrapper = File(engineDir, "provision-proot.sh")
            wrapper.writeText(TurnScripts.rootExec(spec.env, spec.argv, pidFile = null))
            val r = rootExec.execute(listOf(su, "-c", "sh ${wrapper.absolutePath}"), timeoutMs)
            Exec(r.code, r.out, r.err)
        }
    }

    private fun runLocal(
        argv: List<String>,
        env: Map<String, String>,
        cwd: File,
        timeoutMs: Long,
    ): Exec {
        val pb = ProcessBuilder(argv).directory(cwd).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)
        val process = runCatching { pb.start() }
            .getOrElse { return Exec(-2, "", it.message ?: "启动失败") }
        val out = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().use { input ->
                val buf = CharArray(4096)
                while (true) {
                    val n = runCatching { input.read(buf) }.getOrDefault(-1)
                    if (n <= 0) break
                    synchronized(out) {
                        val room = MAX_LOG_CHARS - out.length
                        if (room > 0) out.append(buf, 0, minOf(n, room))
                    }
                }
            }
        }
        reader.start()
        val done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        reader.join(3_000)
        return Exec(
            if (done) process.exitValue() else -1,
            out.toString(),
            if (done) "" else "命令超时（${timeoutMs / 1000}s）",
        )
    }

    /** Actionable failure text; the UI shows this verbatim. */
    private fun toolsFailure(r: Exec): String = toolsFailureText(r.code, r.combined)

    private fun cleanupStaging(claude: File) {
        // Keep the verified archives: switching root <-> proot re-extracts without a new
        // 30 MB layer / ~100 MB CLI download. Drop the staged binary (a second ~300 MB
        // copy that is already inside the jail) and the root staging tree.
        runCatching { claude.delete() }
        runCatching { File(Paths.staging(ctx), "rootfs-stage").deleteRecursively() }
    }

    // ---------------------------------------------------------------- helpers

    /** Fresh install needs CLI + layer + two copies + apt; failing early beats ENOSPC. */
    private fun requireFreeSpace(needMb: Long) {
        val stat = android.os.StatFs(Paths.engine(ctx).absolutePath)
        val freeMb = stat.availableBytes / (1024 * 1024)
        if (freeMb < needMb) {
            error(
                "存储空间不足：安装 Debian 容器 + 工具需要约 ${needMb}MB，当前可用 ${freeMb}MB。" +
                    "请清理空间后重试（容器和截图缓存是常见占用）。"
            )
        }
        Logx.i("可用空间 ${freeMb}MB（需要约 ${needMb}MB）", ctx)
    }

    /**
     * Android has no /etc/resolv.conf. Prefer the DNS servers of the current network;
     * the legacy properties are often empty on modern devices, so fall back to the
     * public resolvers the README already documents.
     *
     * Only IPv4 servers are written: see [dnsLines] for why the address family matters.
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
        return dnsLines(servers)
    }

    /**
     * The two files that decide which address family the container resolves to.
     *
     * Written both at install time ([configureJail]) and on every start of an existing
     * install ([refreshNetworkConfig]), so a fix here reaches users who never reinstall.
     */
    private fun networkFiles(): Map<String, String> = linkedMapOf(
        "etc/resolv.conf" to dnsConfig() + "\n",
        "etc/gai.conf" to GAI_CONF,
    )

    /**
     * Rewrites `etc/resolv.conf` and `etc/gai.conf` of an existing install.
     *
     * `configureJail` only runs during an install, so without this an install created
     * before the IPv4-first fix would keep its old resolver forever (the WebFetch
     * domain check would keep returning 403 through the TUN proxy's IPv6 edge).
     *
     * Idempotent and best-effort by contract: a failure only logs, it must never turn a
     * Ready engine into a Failed one — a stale resolv.conf is still functional, just
     * with the WebFetch check failing. The content is compared first, so an already
     * correct jail costs no `su` call.
     */
    fun refreshNetworkConfig(installation: Installation) {
        val files = networkFiles()
        if (!installation.rootMode) {
            // The app owns filesDir/engine/jail and can write it directly.
            files.forEach { (relative, content) ->
                writeIfChanged(File(installation.jail(), relative), content)
            }
            return
        }
        // /data/adb is root-owned; without a grant the jail is unreadable anyway and the
        // engine cannot run, so there is nothing to fix here.
        if (!rootExec.isGranted()) return
        // Readable only when the app happens to have access; otherwise the root shell
        // decides and we pay one `su -c` per launch.
        if (files.all { (relative, content) ->
                runCatching { File(installation.jail(), relative).readText() }.getOrNull() == content
            }
        ) return
        val result = rootExec.run(netConfigScript(installation.jailPath, files), 30_000)
        if (!result.out.contains(NETCONF_OK)) {
            Logx.e(
                "重写容器 DNS 配置失败：${installation.jailPath}（exit=${result.code}，" +
                    "${result.combined.takeLast(200)}）",
                ctx,
            )
        }
    }

    /** Writes [content] unless the file already holds exactly that text; failures log. */
    private fun writeIfChanged(file: File, content: String) {
        if (runCatching { file.readText() }.getOrNull() == content) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }.onFailure { Logx.e("写入 ${file.absolutePath} 失败：${it.message}", ctx) }
    }

    private fun prop(name: String): String? = runCatching {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val v = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        v.ifBlank { null }
    }.getOrNull()

    fun humanBytes(v: Long): String = when {
        v >= 1L shl 30 -> "%.1f GB".format(v.toDouble() / (1L shl 30))
        v >= 1L shl 20 -> "%.0f MB".format(v.toDouble() / (1L shl 20))
        v >= 1L shl 10 -> "%.0f KB".format(v.toDouble() / (1L shl 10))
        else -> "$v B"
    }

    companion object {
        /** Real binary is ~300 MB; anything under 100 MB is definitely truncated. */
        const val MIN_BINARY_BYTES = 100L * 1024 * 1024

        /**
         * glibc prefers IPv6 whenever the host has an AAAA record, and `api.anthropic.com`'s
         * IPv6 edge answers 403 through the TUN proxy used on real devices — which made the
         * CLI's WebFetch domain check (`/api/web/domain_info`) always fail. These two files
         * keep the container on IPv4; see [dnsLines] and [GAI_CONF].
         */
        internal const val GAI_CONF =
            "# Prefer IPv4: some CDN edges reject this device IPv6 egress.\n" +
                "precedence ::ffff:0:0/96  100\n"

        /** Printed by [netConfigScript] only when both files were written. */
        internal const val NETCONF_OK = "NETCONF_OK"

        /**
         * Public resolvers used when the network reports fewer than [MAX_NAMESERVERS] IPv4
         * servers (or only IPv6 ones, which are dropped).
         */
        internal val DNS_FALLBACKS = listOf("223.5.5.5", "119.29.29.29", "1.1.1.1")

        /** glibc reads at most this many nameservers; extra lines are dead weight. */
        internal const val MAX_NAMESERVERS = 4

        /**
         * Builds the `resolv.conf` body from the servers Android reported.
         *
         * IPv6 literal nameservers are dropped on purpose. The device's TUN proxy exposes
         * an IPv6 resolver (`fd17:…`) that returns the *real* A/AAAA records, so a resolver
         * list that starts with it sends the CLI to Cloudflare's IPv6 edge, which rejects
         * the WebFetch domain check with 403. Only the proxy's IPv4 resolver (a fake-IP
         * `198.18.x.x`) gets the request intercepted, and IPv4 is what works end to end.
         * The network's own IPv4 servers come first, then the public fallbacks fill the
         * remaining slots — dropping the fallbacks outright when the network only offers
         * IPv6 would leave the container without a resolver.
         */
        internal fun dnsLines(servers: List<String>): String {
            val network = servers
                .map { it.trim() }
                .filter { isIpv4(it) }
                .distinct()
                .take(MAX_NAMESERVERS)
            val all = (network + DNS_FALLBACKS).distinct().take(MAX_NAMESERVERS)
            return all.joinToString("\n") { "nameserver $it" } +
                "\noptions timeout:2 attempts:3"
        }

        /**
         * Strict dotted-quad check. Also filters out IPv6 (`:`), scoped link-local
         * addresses (`fe80::1%wlan0`) and anything else that is not a literal IPv4.
         */
        internal fun isIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.length in 1..3 && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
            }
        }

        /**
         * Root-side rewrite of the network files for an existing jail.
         *
         * Needed because `/data/adb/claudroid` belongs to root while the app runs as its
         * own uid. Only the basename of each key is used, so a caller cannot write outside
         * `<jail>/etc`; `printf '%s'` keeps the content verbatim (no `%` expansion).
         */
        internal fun netConfigScript(jailPath: String, files: Map<String, String>): String = buildString {
            appendLine("set +e")
            appendLine("D=${TurnScripts.quote(jailPath.trim().trimEnd('/'))}")
            appendLine("mkdir -p \"\$D/etc\" 2>/dev/null")
            files.forEach { (relative, content) ->
                val name = relative.substringAfterLast('/')
                appendLine(
                    "printf '%s' ${TurnScripts.quote(content)} > \"\$D/etc/$name\" " +
                        "|| { echo \"NETCONF_FAIL=$name\"; FAILED=1; }",
                )
            }
            appendLine("if [ \"\${FAILED:-0}\" = 1 ]; then exit 1; fi")
            appendLine("echo $NETCONF_OK")
        }

        private const val MAX_LOG_CHARS = 200_000

        /**
         * The new tree is built under `<jail>.new` and only renamed after apt + verify,
         * so a failed reinstall never touches the working jail. `.old` exists only for
         * the duration of the rename.
         */
        private const val NEW_SUFFIX = ".new"
        private const val OLD_SUFFIX = ".old"

        /** Base tools the agent needs (git, grep, sed/awk …). */
        val TOOLS = listOf(
            "bash", "git", "grep", "sed", "awk", "tar", "gzip", "xz", "curl", "less",
        )

        private val TOOL_PACKAGES = listOf(
            "bash", "git", "grep", "coreutils", "sed", "mawk", "tar", "gzip",
            "xz-utils", "curl", "ca-certificates", "less",
        )

        internal fun guestEnv(): Map<String, String> = linkedMapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/root",
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
        )

        /**
         * The provisioning script. Unit-tested because it is the difference between
         * "installed" and "installed but apt never ran".
         *
         * It is idempotent: a healthy container only prints the current versions, so an
         * upgrade with an unchanged rootfs is cheap. Failures are marked with stable
         * tokens (`APT_UPDATE_FAIL`, `TOOLS_MISSING`) that [toolsFailure] translates.
         */
        internal fun toolsScript(version: String): String = buildString {
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            appendLine("export LANG=C.UTF-8")
            appendLine("mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial 2>/dev/null || true")
            appendLine("dpkg --configure -a >/dev/null 2>&1 || true")
            appendLine("missing=''")
            appendLine("for t in ${TOOLS.joinToString(" ")}; do")
            appendLine("  command -v \"\$t\" >/dev/null 2>&1 || missing=\"\$missing \$t\"")
            appendLine("done")
            appendLine("if [ -n \"\$missing\" ]; then")
            appendLine("  echo \"APT_NEED:\$missing\"")
            appendLine("  ok=0")
            appendLine("  for i in 1 2 3; do")
            appendLine("    if apt-get -q -o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 update; then ok=1; break; fi")
            appendLine("    echo \"APT_UPDATE_RETRY \$i\"; sleep \$((i * 3))")
            appendLine("  done")
            appendLine("  [ \"\$ok\" = 1 ] || { echo APT_UPDATE_FAIL; exit 21; }")
            appendLine("  ok=0")
            appendLine("  for i in 1 2 3; do")
            appendLine(
                "    if apt-get -y --no-install-recommends -o DPkg::Lock::Timeout=60 install " +
                    TOOL_PACKAGES.joinToString(" ") + "; then ok=1; break; fi"
            )
            appendLine("    echo \"APT_INSTALL_RETRY \$i\"")
            appendLine("    dpkg --configure -a >/dev/null 2>&1 || true")
            appendLine("    sleep \$((i * 3))")
            appendLine("  done")
            appendLine("  [ \"\$ok\" = 1 ] || { echo APT_INSTALL_FAIL; exit 22; }")
            appendLine("  rm -f /var/cache/apt/archives/*.deb 2>/dev/null || true")
            appendLine("  rm -f /var/lib/apt/lists/*.lz4 2>/dev/null || true")
            appendLine("fi")
            appendLine("missing=''")
            appendLine("for t in ${TOOLS.joinToString(" ")}; do")
            appendLine("  command -v \"\$t\" >/dev/null 2>&1 || missing=\"\$missing \$t\"")
            appendLine("done")
            appendLine("[ -z \"\$missing\" ] || { echo \"TOOLS_MISSING:\$missing\"; exit 23; }")
            appendLine("test -f /etc/debian_version || { echo NOT_DEBIAN; exit 24; }")
            appendLine("test -x /opt/claude/$version/claude || { echo CLAUDE_MISSING; exit 25; }")
            appendLine("/opt/claude/$version/claude --version 2>&1 | head -n 1")
            appendLine("echo TOOLS_OK")
            appendLine("echo CLAUDE_OK")
        }

        /**
         * Root-side swap of the verified `.new` tree into place. Arguments must already be
         * shell-quoted (see `RootExec.q`); the script is pure so the ordering rules below
         * are unit-tested.
         *
         * `test ! -e "$O"` is the important line: if the old tree survived the app-side
         * sweep (a mount it could not detach, a concurrent session), `mv "$J" "$O"` would
         * move the live jail *inside* `.old` and then move `.new` over the old name — the
         * install would look successful while the previous jail is buried a level deeper.
         */
        internal fun swapScript(jail: String, next: String, old: String, version: String): String =
            buildString {
                appendLine("set -e")
                appendLine("J=$jail")
                appendLine("N=$next")
                appendLine("O=$old")
                appendLine("test -f \"\$N/etc/debian_version\"")
                appendLine("test -x \"\$N/bin/bash\"")
                appendLine("test -x \"\$N/opt/claude/$version/claude\"")
                appendLine("test ! -e \"\$O\"")
                appendLine("if [ -e \"\$J\" ]; then mv \"\$J\" \"\$O\"; fi")
                appendLine("if ! mv \"\$N\" \"\$J\"; then")
                appendLine("  if [ -e \"\$O\" ] && [ ! -e \"\$J\" ]; then mv \"\$O\" \"\$J\" || true; fi")
                appendLine("  echo SWAP_FAILED")
                appendLine("  exit 1")
                appendLine("fi")
                appendLine("echo SWAP_OK")
            }

        /**
         * Translates the last ~400 chars of provisioning output into a message the user
         * can act on. Kept pure so the token -> hint mapping is unit-tested.
         */
        internal fun toolsFailureText(code: Int, combined: String): String {
            val text = combined
            val detail = text.replace('\n', ' ').replace('\t', ' ').trim().takeLast(400)
            // The script appends each tool with a leading space, so `TOOLS_MISSING: git xz`
            // is the normal shape; allow (and trim) that whitespace or the hint loses the
            // only actionable part of the message.
            val missing = Regex("""TOOLS_MISSING:?[ \t]*([^\r\n]*)""")
                .find(text)?.groupValues?.getOrNull(1)?.trim()?.take(200)
            val hint = when {
                text.contains("APT_UPDATE_FAIL") ->
                    "容器无法连接 Debian 软件源。请检查网络或 DNS（例如关闭代理/换网络）后重试；" +
                        "也可以在「终端」里手动执行 apt-get update。"
                text.contains("APT_INSTALL_FAIL") ->
                    "apt 安装基础工具失败，常见原因是磁盘空间不足或软件源不稳定。" +
                        "请清理空间后重试；仍失败可在「终端」里手动执行 " +
                        "apt-get install -y bash git grep coreutils sed mawk tar gzip xz-utils curl ca-certificates less。"
                !missing.isNullOrBlank() ->
                    "安装完成后仍缺少工具：$missing。请重试安装，或在「终端」里手动 apt-get install。"
                text.contains("CLAUDE_MISSING") ->
                    "Claude Code 可执行文件未放入容器，请重试安装。"
                text.contains("NOT_DEBIAN") ->
                    "容器不是有效的 Debian rootfs，请重试安装。"
                code == -1 ->
                    "容器工具安装超时（网络过慢或 apt 卡住）。请稍后重试；也可以在「终端」里手动执行 apt。"
                else -> "Debian 容器初始化失败"
            }
            return "$hint（exit=$code）$detail"
        }
    }
}

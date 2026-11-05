package app.claudroid.engine

import android.content.Context
import app.claudroid.core.Logx
import app.claudroid.core.Paths
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Root access helper.
 *
 * Everything privileged goes through `su -c`. We write the command as a script file first
 * because the interesting commands contain quotes, redirections and here-docs, and quoting
 * them twice (`su -c` + shell) is where these features usually break.
 */
class RootExec(private val ctx: Context) {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
        val combined: String get() = (out + (if (err.isBlank()) "" else "\n$err")).trim()
    }

    @Volatile private var suChecked = false
    @Volatile private var suPath: String? = null
    @Volatile private var granted = false
    @Volatile private var busyboxPath: String? = null
    /**
     * Keyed by jail path: provisioning probes `<jail>.new`, and the answer must not be
     * reused for the official jail (or for a jail that was just moved/removed).
     */
    @Volatile private var chrootCache: Pair<String, Boolean>? = null

    /** Unique suffix for per-call root scripts; see [run]. */
    private val runSeq = AtomicInteger(0)

    fun suBinary(): String? {
        if (!suChecked) {
            suChecked = true
            suPath = SU_CANDIDATES.firstOrNull { File(it).canExecute() }
                ?: runCatching {
                    val p = ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start()
                    val path = p.inputStream.bufferedReader().readText().trim().lines().lastOrNull().orEmpty()
                    p.waitFor()
                    path.ifBlank { null }
                }.getOrNull()
            Logx.i("su 二进制: ${suPath ?: "未找到"}")
        }
        return suPath
    }

    /** Asks the su manager for a shell. The first call may show a Magisk/KernelSU dialog. */
    fun probeRoot(): Boolean {
        val su = suBinary() ?: return false
        granted = runCatching {
            val p = ProcessBuilder(su, "-c", "id -u").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val finished = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            finished && out.trim() == "0"
        }.getOrDefault(false)
        Logx.i(if (granted) "root 已授权" else "root 未授权 / 已拒绝")
        return granted
    }

    fun isGranted(): Boolean = granted

    fun busybox(): String? {
        if (busyboxPath != null) return busyboxPath
        busyboxPath = BUSYBOX_CANDIDATES.firstOrNull { File(it).canExecute() } ?: runCatching {
            run("command -v busybox 2>/dev/null || true").out.trim().ifBlank { null }
        }.getOrNull()
        return busyboxPath
    }

    /**
     * chroot(1) exists in Magisk's busybox; toybox does not provide one. We probe with
     * `/bin/sh` because the Debian rootfs has no busybox to fall back on.
     */
    fun chrootReady(installation: app.claudroid.core.Installation): Boolean =
        chrootReady(installation.jailPath)

    fun chrootReady(jailPath: String): Boolean {
        chrootCache?.let { (path, ok) -> if (path == jailPath) return ok }
        val bb = busybox() ?: return false.also { chrootCache = jailPath to false }
        val r = run("$bb chroot ${q(jailPath)} /bin/sh -c 'exit 0'")
        val ok = r.ok
        chrootCache = jailPath to ok
        Logx.i("chroot 可用: $ok（${bb}）")
        return ok
    }

    fun resetCache() {
        suChecked = false
        suPath = null
        busyboxPath = null
        chrootCache = null
    }

    /**
     * Runs [body] as root inside a shell script.
     *
     * Each call gets its own file: [deleteJail] must execute exactly the script it
     * generated, and a shared file could be rewritten by a concurrent root call
     * (a self-test probe, a terminal sweep) between `writeText` and `su` reading it.
     */
    fun run(body: String, timeoutMs: Long = 60_000): Result {
        val su = suBinary() ?: return Result(127, "", "找不到 su")
        val script = File(ctx.filesDir, "engine/run-${System.nanoTime()}-${runSeq.incrementAndGet()}.sh")
        return try {
            script.parentFile?.mkdirs()
            script.writeText("#!/system/bin/sh\n" + body + "\n")
            execute(listOf(su, "-c", "sh ${script.absolutePath}"), timeoutMs)
        } finally {
            runCatching { script.delete() }
        }
    }

    /**
     * The only supported way to delete a jail tree.
     *
     * A chroot session bind-mounts host paths into the jail and those mounts outlive the
     * chroot process (they belong to the root shell's mount namespace). A plain
     * `rm -rf <jail>` therefore descends into the *host* directories behind them — that
     * is what wiped `/data` and `/dev` on a real device.
     *
     * Read → unmount → delete → assert all run inside **one** root shell script
     * ([MountTable.deleteScript]): `/proc/mounts` is per mount namespace, so an app-side
     * read can miss the binds exactly when it matters, and an app-uid `umount` only gets
     * EPERM. The script refuses to run `rm -rf` while any mountpoint is still at or
     * below the target.
     *
     * Refuses any path outside the whitelist instead of deleting something it was not
     * meant to: [Paths.ROOT_JAIL], [Paths.ROOT_JAIL_FALLBACK] (plus their `.new`/`.old`
     * swap siblings) and anything under `filesDir/engine`.
     */
    fun deleteJail(jailPath: String, timeoutMs: Long = 300_000): Result {
        val target = jailPath.trim().trimEnd('/')
        if (!allowed(target)) {
            Logx.e("拒绝删除非白名单路径：$jailPath", ctx)
            return Result(2, "", "拒绝删除非 jail 路径：$jailPath")
        }
        return synchronized(deleteLock) {
            val result = run(MountTable.deleteScript(target, umountBinary()), timeoutMs)
            if (result.out.lineSequence().any { it.trim() == "GONE" }) {
                Logx.i("jail 已删除：$target", ctx)
                return result
            }
            val mountsSeen = marker(result.out, "JAIL_MOUNTS=")
            val mountsLeft = marker(result.out, "JAIL_MOUNTS_LEFT=")
            val remaining = mountLeftPaths(result.out)
            Logx.e(
                "删除 jail 中止：$target（脚本内看到 $mountsSeen 个挂载，剩余 $mountsLeft 个" +
                    (if (remaining.isEmpty()) "" else "：" + remaining.joinToString(", ")) +
                    "，退出码 ${result.code}）",
                ctx,
            )
            val detail = when {
                mountsLeft != null && mountsLeft > 0 ->
                    "仍有 $mountsLeft 个挂载未卸载（${remaining.joinToString(", ")}），拒绝删除：$target"
                else -> "jail 未删除：$target（退出码 ${result.code}）"
            }
            Result(
                if (mountsLeft != null && mountsLeft > 0) 4 else 3,
                result.out,
                detail + result.combined.takeLast(200),
            )
        }
    }

    /**
     * App-side counterpart of [deleteJail] for trees this uid can delete itself
     * (PRoot installs under `filesDir/engine`).
     *
     * The rule is the same: never recurse into a path that still has a mount below it —
     * for a PRoot tree the host directory behind the bind is the user's workspace. With
     * root the sweep runs in the root shell (app-uid `umount` would just get EPERM); the
     * tree is only deleted when that sweep reports nothing left.
     */
    fun deleteTree(path: String): Boolean {
        val target = path.trim().trimEnd('/')
        if (!allowed(target)) {
            Logx.e("拒绝删除非白名单路径：$path", ctx)
            return false
        }
        return synchronized(deleteLock) {
            if (granted) {
                val sweep = run(MountTable.unmountScript(target, umountBinary()), 120_000)
                val left = marker(sweep.out, "JAIL_MOUNTS_LEFT=")
                // No marker means the root sweep did not run to completion (su refused,
                // timeout, script error). We cannot prove the tree is mount-free, so it
                // must not be deleted.
                if (left == null) {
                    Logx.e("删除 $target 中止：无法确认 root 侧挂载已清理（退出码 ${sweep.code}）", ctx)
                    return false
                }
                if (left > 0) {
                    Logx.e("删除 $target 中止：root 侧仍有 $left 个挂载", ctx)
                    return false
                }
            }
            // Second, app-side check: even a successful root sweep must be visible here
            // before the recursive delete is allowed to start.
            val mounts = currentMountsUnder(target)
            if (mounts.isNotEmpty()) {
                Logx.e("删除 $target 中止：仍有挂载 ${mounts.joinToString(", ")}", ctx)
                return false
            }
            val gone = runCatching { File(target).deleteRecursively() }.getOrDefault(false)
            if (!gone && File(target).exists()) {
                Logx.e("删除失败，目录仍然存在：$target", ctx)
                return false
            }
            true
        }
    }

    /**
     * Removes the mounts a chroot session left below [jailPath]. Called by the app after
     * each chroot turn/terminal command ends, so the next launch and any later deletion
     * start from a clean mount table.
     *
     * Runs in the root shell when root is available — the binds live in that namespace
     * and only root may detach them. Without root this is best-effort and expected to
     * fail; it is logged as information, never as success.
     *
     * Skipped while another chroot is alive: unmounting the shared workspace/home binds
     * would break the concurrent session. [deleteJail] still sweeps unconditionally.
     */
    fun unmountJail(jailPath: String) {
        if (activeChroots.get() > 0) return
        val target = jailPath.trim().trimEnd('/')
        if (target.isEmpty()) return
        // Same whitelist as the delete path: unmounting below an arbitrary path (e.g.
        // "/data") would detach host mounts the jail was never allowed to touch.
        if (!allowed(target)) {
            Logx.e("拒绝清理非白名单路径的挂载：$jailPath", ctx)
            return
        }
        if (granted) {
            val result = run(MountTable.unmountScript(target, umountBinary()), 120_000)
            val seen = marker(result.out, "JAIL_MOUNTS=")
            val left = marker(result.out, "JAIL_MOUNTS_LEFT=")
            val remaining = mountLeftPaths(result.out)
            if (left != null && left > 0) {
                Logx.e(
                    "清理 jail 挂载未完成：$target（看到 $seen 个，剩余 $left 个：" +
                        remaining.joinToString(", ") + "）",
                    ctx,
                )
            } else {
                Logx.i("清理 jail 挂载：$target（看到 ${seen ?: 0} 个，已全部卸载）", ctx)
            }
            return
        }
        val mounts = currentMountsUnder(target)
        if (mounts.isEmpty()) return
        Logx.i("无 root，尝试清理挂载（预计失败）：${mounts.joinToString(", ")}", ctx)
        mounts.forEach { runCatching { execute(listOf(umountBinary(), "-l", it), 20_000) } }
    }

    /** Marks the start of a chroot session; pair with [chrootEnd] in a `finally`. */
    fun chrootBegin() {
        activeChroots.incrementAndGet()
    }

    fun chrootEnd() {
        activeChroots.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** App-side view of the mount table; only a hint when root is available. */
    private fun currentMountsUnder(jailPath: String): List<String> {
        if (jailPath.isEmpty()) return emptyList()
        val text = runCatching { File("/proc/mounts").readText() }.getOrNull() ?: return emptyList()
        return MountTable.mountsUnder(text, jailPath)
    }

    private fun umountBinary(): String =
        if (File("/system/bin/umount").canExecute()) "/system/bin/umount" else "umount"

    private fun allowed(target: String): Boolean = MountTable.isAllowedJail(
        target,
        listOf(Paths.ROOT_JAIL, Paths.ROOT_JAIL_FALLBACK),
        File(ctx.filesDir, "engine").absolutePath,
    )

    /** Value of a `NAME=<n>` marker line printed by the root scripts. */
    private fun marker(out: String, name: String): Int? = out.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(name) }
        .mapNotNull { it.removePrefix(name).trim().toIntOrNull() }
        .lastOrNull()

    /**
     * Lines like `MOUNT_LEFT=/data/jail/subpath` from list_mounts, decoded: the script
     * echoes the raw `/proc/mounts` field, which octal-escapes spaces.
     */
    private fun mountLeftPaths(out: String): List<String> = out.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("MOUNT_LEFT=") }
        .map { MountTable.decodeField(it.removePrefix("MOUNT_LEFT=").trim()) }
        .distinct()
        .toList()

    fun execute(cmd: List<String>, timeoutMs: Long = 60_000): Result = runCatching {
        val pb = ProcessBuilder(cmd).directory(ctx.filesDir)
        val p = pb.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { p.inputStream.bufferedReader().forEachLine { line -> synchronized(out) { out.appendLine(line) } } }
        val t2 = Thread { p.errorStream.bufferedReader().forEachLine { line -> synchronized(err) { err.appendLine(line) } } }
        t1.start(); t2.start()
        val done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroyForcibly()
            t1.join(2000); t2.join(2000)
            return Result(-1, out.toString(), "命令超时")
        }
        t1.join(3000); t2.join(3000)
        Result(p.exitValue(), out.toString().trim(), err.toString().trim())
    }.getOrElse { Result(-2, "", it.message ?: "执行失败") }

    /** Single-quote a value for /system/bin/sh. */
    fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        /**
         * Serialises the read-unmount-delete sequence; concurrent deletes could race and
         * a delete that runs while a swap is mid-flight could remove the wrong tree.
         */
        private val deleteLock = Any()

        /**
         * Chroot sessions currently alive in this app process. The terminal and a chat
         * turn can run at the same time; unmounting after one of them ends would pull the
         * workspace/home mounts out from under the other.
         */
        private val activeChroots = AtomicInteger(0)

        private val SU_CANDIDATES = listOf(
            "/debug_ramdisk/su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system_ext/bin/su", "/su/bin/su",
        )
        private val BUSYBOX_CANDIDATES = listOf(
            "/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/debug_ramdisk/busybox",
            "/system/xbin/busybox", "/system/bin/busybox",
        )
    }
}

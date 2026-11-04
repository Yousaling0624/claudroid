package app.claudroid.engine

import android.content.Context
import app.claudroid.core.Logx
import java.io.File

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
    @Volatile private var chrootReady: Boolean? = null

    private val scriptFile: File by lazy { File(ctx.filesDir, "engine/run.sh").apply { parentFile?.mkdirs() } }

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

    /** chroot(1) exists in Magisk's busybox; toybox does not provide one. */
    fun chrootReady(installation: app.claudroid.core.Installation): Boolean {
        chrootReady?.let { return it }
        val bb = busybox() ?: return false.also { chrootReady = false }
        val r = run("$bb chroot '${q(installation.jailPath)}' /bin/busybox true")
        val ok = r.ok
        chrootReady = ok
        Logx.i("chroot 可用: $ok（${bb}）")
        return ok
    }

    fun resetCache() {
        suChecked = false
        suPath = null
        busyboxPath = null
        chrootReady = null
    }

    /** Runs [body] as root inside a shell script. */
    fun run(body: String, timeoutMs: Long = 60_000): Result {
        val su = suBinary() ?: return Result(127, "", "找不到 su")
        scriptFile.writeText("#!/system/bin/sh\n" + body + "\n")
        return execute(listOf(su, "-c", "sh ${scriptFile.absolutePath}"), timeoutMs)
    }

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

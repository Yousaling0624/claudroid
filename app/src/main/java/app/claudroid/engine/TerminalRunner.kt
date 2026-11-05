package app.claudroid.engine

import android.content.Context
import app.claudroid.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one-off shell commands in the workspace.
 *
 * This is deliberately not a persistent PTY. Each command is its own process and only
 * the working directory survives: the command reports its final `$PWD` through a small
 * status file in the home bind, and the next call starts there. Shell variables, aliases
 * and functions therefore do not leak between commands, which keeps a crashed or hung
 * command from poisoning the terminal session.
 *
 * The launch shape mirrors [ClaudeEngine]: PRoot, root+chroot, root without chroot, or
 * a plain app-side shell. Credentials are never part of the terminal environment.
 */
class TerminalRunner(
    private val ctx: Context,
    private val root: RootExec,
) {

    data class Result(
        val output: String,
        val exitCode: Int,
        /** 命令结束后的工作区相对路径，"" 表示 /work 根。 */
        val cwdRel: String,
        val error: String? = null,
        /** True when output exceeded [MAX_OUTPUT_CHARS] and the rest was drained only. */
        val truncated: Boolean = false,
    )

    /**
     * Executes [command] in [cwdRel]. Output is streamed through [onOutput] on an IO
     * thread; stdout and stderr are merged because the terminal has one pane.
     */
    suspend fun run(
        plan: EngineManager.ShellPlan,
        command: String,
        cwdRel: String,
        onOutput: suspend (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val workspace = Paths.workspace(ctx)
        val home = Paths.home(ctx)
        val startCwd = resolveExistingCwd(workspace, cwdRel)
        val guest = plan.useProot || (plan.useRoot && plan.useChroot)

        // The status file lives in the home directory because that directory is already
        // bound to /root in PRoot/chroot. Direct and root-direct runs use its host path.
        // The name is unique so overlapping commands cannot overwrite each other.
        val token = "term-${System.nanoTime()}-${UUID.randomUUID().toString().take(8)}"
        val statusHost = File(home, "$token.status")
        val pidHost = File(Paths.engine(ctx), "$token.pid")
        val statusRef = if (guest) "/root/${statusHost.name}" else statusHost.absolutePath

        val hostCwd = File(workspace, startCwd).absolutePath
        val guestCwd = if (startCwd.isEmpty()) "/work" else "/work/$startCwd"
        val env = baseEnv(
            guest = guest,
            home = if (guest) "/root" else home.absolutePath,
            tmp = if (guest) "/tmp" else ctx.cacheDir.absolutePath,
            shell = when {
                !guest -> "/system/bin/sh"
                plan.installation.hasBash -> "/bin/bash"
                else -> "/bin/sh"
            },
        )
        val guestScript = if (guest) buildScript(guestCwd, "/work", command, statusRef) else null
        val hostBody = if (!guest) {
            buildScript(hostCwd, workspace.absolutePath, command, statusRef)
        } else {
            null
        }

        var scriptFile: File? = null
        val commandLine: List<String>
        val processCwd: File
        val processEnv: Map<String, String>
        when {
            plan.useProot -> {
                val spec = try {
                    ProotRunner(ctx).shellSpec(
                        installation = plan.installation,
                        workDir = guestCwd,
                        script = guestScript!!,
                        baseEnv = env,
                    )
                } catch (e: Exception) {
                    return@withContext Result(
                        output = "",
                        exitCode = -1,
                        cwdRel = startCwd,
                        error = e.message ?: "PRoot 无法启动",
                    )
                }
                commandLine = spec.argv
                processCwd = spec.cwd
                processEnv = spec.env
            }
            plan.useRoot && plan.useChroot -> {
                val wrapper = TurnScripts.chrootShell(
                    env = env,
                    jail = plan.installation.jailPath,
                    workspace = workspace.absolutePath,
                    home = home.absolutePath,
                    chrootTool = root.busybox() ?: "${plan.installation.jailPath}/bin/busybox",
                    guestScript = guestScript!!,
                    pidFile = pidHost.absolutePath,
                )
                scriptFile = writeScript(token, wrapper)
                commandLine = suCommand(scriptFile)
                processCwd = ctx.filesDir
                processEnv = suEnv()
            }
            plan.useRoot -> {
                val wrapper = hostScript(
                    env = env,
                    cwd = hostCwd,
                    fallback = workspace.absolutePath,
                    command = command,
                    statusFile = statusRef,
                    pidFile = pidHost.absolutePath,
                )
                scriptFile = writeScript(token, wrapper)
                commandLine = suCommand(scriptFile)
                processCwd = workspace
                processEnv = suEnv()
            }
            else -> {
                commandLine = listOf("/system/bin/sh", "-c", hostBody!!)
                processCwd = workspace
                processEnv = env
            }
        }

        val pb = ProcessBuilder(commandLine).directory(processCwd)
        pb.environment().clear()
        pb.environment().putAll(processEnv)
        // One pane: stderr is part of the command output, not a separate stream.
        pb.redirectErrorStream(true)
        statusHost.delete()
        pidHost.delete()
        val process = try {
            pb.start()
        } catch (e: Exception) {
            statusHost.delete()
            pidHost.delete()
            scriptFile?.delete()
            return@withContext Result(
                output = "",
                exitCode = -1,
                cwdRel = startCwd,
                error = "命令无法启动：${e.message ?: "未知错误"}",
            )
        }
        // Commands such as `cat` or `read` would otherwise wait for terminal input
        // until the 120 s timeout. There is no interactive stdin in this API.
        runCatching { process.outputStream.close() }

        val output = StringBuilder()
        val truncated = AtomicBoolean(false)
        fun snapshot(): String = synchronized(output) { output.toString() }
        val reader = launch {
            process.inputStream.reader(Charsets.UTF_8).use { input ->
                val buffer = CharArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    val chunk = String(buffer, 0, n)
                    val accepted = synchronized(output) {
                        appendCapped(output, chunk, MAX_OUTPUT_CHARS)
                    }
                    if (accepted == null || accepted.length < chunk.length) {
                        // Keep draining the pipe so the command can finish normally, but
                        // stop growing memory and stop feeding the UI callback.
                        truncated.set(true)
                    }
                    if (accepted != null) onOutput(accepted)
                }
            }
        }

        try {
            var timedOut = false
            val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
            while (process.isAlive) {
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    break
                }
                delay(50)
            }
            if (timedOut) terminate(process, pidHost, plan.useRoot)

            // A background grandchild can inherit stdout and keep the pipe open after
            // the command itself exits; do not let that turn into a second timeout.
            if (withTimeoutOrNull(3_000) { reader.join() } == null) {
                runCatching { process.inputStream.close() }
                reader.cancel()
            }

            if (timedOut) {
                return@withContext Result(
                    output = snapshot(),
                    exitCode = -1,
                    cwdRel = startCwd,
                    error = "命令超时（120 秒），已终止。",
                    truncated = truncated.get(),
                )
            }

            val status = if (statusHost.isFile) {
                parseStatus(readCapped(statusHost, MAX_STATUS_BYTES))
            } else {
                null
            }
            if (status == null) {
                // `exit` (or `exec`) skips the status write, and a failed su can also
                // leave no status file. The process did finish, so keep the previous
                // cwd instead of inventing a new one.
                return@withContext Result(
                    output = snapshot(),
                    exitCode = 0,
                    cwdRel = startCwd,
                    error = "命令没有留下工作目录状态（例如执行了 exit，或 shell 未能启动）；已保持当前目录。",
                    truncated = truncated.get(),
                )
            }

            val rootRef = if (guest) "/work" else workspace.absolutePath
            var nextCwd = relativizeCwd(status.pwd, rootRef)
            if (nextCwd == null && !guest) {
                // /data/data and /data/user/0 are symlinks to each other; getcwd may
                // return the canonical form even though we launched from the other one.
                val canonicalPwd = runCatching { File(status.pwd).canonicalPath }.getOrNull()
                val canonicalRoot = runCatching { workspace.canonicalPath }.getOrNull()
                if (canonicalPwd != null && canonicalRoot != null) {
                    nextCwd = relativizeCwd(canonicalPwd, canonicalRoot)
                }
            }
            if (nextCwd == null) {
                return@withContext Result(
                    output = snapshot(),
                    exitCode = status.exitCode,
                    cwdRel = "",
                    error = "命令把工作目录移出了工作区，已回到工作区根目录。",
                    truncated = truncated.get(),
                )
            }
            // `pwd` may report a logical path through a symlink. Re-check the host-side
            // canonical target so a symlink inside the workspace cannot persist an
            // outside directory as the next cwd.
            val verified = resolveExistingCwd(workspace, nextCwd)
            if (verified.isEmpty() && nextCwd.isNotEmpty()) {
                return@withContext Result(
                    output = snapshot(),
                    exitCode = status.exitCode,
                    cwdRel = "",
                    error = "命令结束后的工作目录不在工作区内或已不存在，已回到工作区根目录。",
                    truncated = truncated.get(),
                )
            }
            Result(snapshot(), status.exitCode, verified, truncated = truncated.get())
        } finally {
            if (process.isAlive) terminate(process, pidHost, plan.useRoot)
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            statusHost.delete()
            pidHost.delete()
            scriptFile?.delete()
        }
    }

    /**
     * Best-effort shutdown. PRoot cleans up its tracees on SIGTERM (`--kill-on-exit`);
     * for root modes the wrapper pid is killed through `su` because the direct child we
     * own is only `su` and the actual command lives below it.
     */
    private fun terminate(process: Process, pidFile: File?, viaRoot: Boolean) {
        val pid = readPid(pidFile)
        if (viaRoot && pid != null) {
            runCatching {
                val su = root.suBinary()
                if (su != null) {
                    val killer = ProcessBuilder(su, "-c", "kill -9 $pid").start()
                    killer.waitFor(2, TimeUnit.SECONDS)
                    if (killer.isAlive) killer.destroyForcibly()
                }
            }
        }
        runCatching { process.destroy() }
        if (!runCatching { process.waitFor(600, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            runCatching { process.destroyForcibly() }
        }
        if (!viaRoot && pid != null) {
            runCatching {
                val killer = ProcessBuilder("/system/bin/kill", "-9", pid.toString()).start()
                killer.waitFor(1, TimeUnit.SECONDS)
                if (killer.isAlive) killer.destroyForcibly()
            }
        }
    }

    private fun readPid(file: File?): Int? = runCatching {
        file?.takeIf { it.isFile }
            ?.readText()
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.toIntOrNull()
    }.getOrNull()?.takeIf { it > 1 }

    /** Syntactic validation plus an existence/canonical check against the real workspace. */
    private fun resolveExistingCwd(workspace: File, cwdRel: String): String {
        val safe = sanitizeCwd(cwdRel)
        if (safe.isEmpty()) return ""
        val target = File(workspace, safe)
        if (!target.isDirectory) return ""
        val canonicalRoot = runCatching { workspace.canonicalFile }.getOrNull() ?: return ""
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return ""
        val inside = canonicalTarget == canonicalRoot ||
            canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        return if (inside) safe else ""
    }

    private fun writeScript(token: String, text: String): File =
        File(Paths.engine(ctx), "$token.sh").apply { writeText(text) }

    private fun suCommand(script: File): List<String> = listOf(
        root.suBinary() ?: "su",
        "-c",
        "/system/bin/sh ${TurnScripts.quote(script.absolutePath)}",
    )

    /** `su` only needs enough environment to find the shell; the script exports the rest. */
    private fun suEnv(): Map<String, String> = mapOf(
        "PATH" to "/system/bin:/system/xbin",
        "HOME" to "/",
    )

    companion object {

        const val TIMEOUT_MS = 120_000L
        /** Retained-output cap. Beyond it the pipe is drained, not accumulated. */
        internal const val MAX_OUTPUT_CHARS = 1024 * 1024
        private const val MAX_STATUS_BYTES = 64 * 1024

        /**
         * Pure relative-path check. Rejects absolute paths, NUL bytes and `..` escapes;
         * `a/../b` is allowed because it resolves inside the workspace. Invalid input
         * falls back to the workspace root ("").
         */
        internal fun sanitizeCwd(raw: String): String = sanitizeCwdOrNull(raw) ?: ""

        internal fun sanitizeCwdOrNull(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty() || value == ".") return ""
            if (value.startsWith("/")) return null
            if (value.contains('\u0000')) return null
            val parts = ArrayList<String>()
            for (part in value.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> {
                        if (parts.isEmpty()) return null
                        parts.removeAt(parts.lastIndex)
                    }
                    else -> parts.add(part)
                }
            }
            return parts.joinToString("/")
        }

        /**
         * The command body. `pwd` and the exit code are written last so a command that
         * replaces the shell (`exit`, `exec`) simply leaves no status file.
         */
        internal fun buildScript(
            cwd: String,
            fallback: String,
            command: String,
            statusFile: String,
        ): String = buildString {
            appendLine("cd ${TurnScripts.quote(cwd)} 2>/dev/null || cd ${TurnScripts.quote(fallback)}")
            appendLine(command)
            appendLine("__claudroid_exit=\$?")
            appendLine("pwd > ${TurnScripts.quote(statusFile)}")
            appendLine("printf '%s\\n' \"\$__claudroid_exit\" >> ${TurnScripts.quote(statusFile)}")
        }

        /** Root wrapper: exports the environment, records the host shell pid, runs the body. */
        internal fun hostScript(
            env: Map<String, String>,
            cwd: String,
            fallback: String,
            command: String,
            statusFile: String,
            pidFile: String?,
        ): String = buildString {
            appendLine("#!/system/bin/sh")
            pidFile?.let { appendLine(TurnScripts.pidLine(it)) }
            env.forEach { (k, v) -> appendLine("export $k=${TurnScripts.quote(v)}") }
            append(buildScript(cwd, fallback, command, statusFile))
        }

        /** Status file format: `pwd` on the first line(s), exit code on the last line. */
        internal fun parseStatus(text: String): TerminalStatus? {
            val body = text.trimEnd('\n', '\r')
            if (body.isBlank()) return null
            val lines = body.lineSequence().toList()
            val exitCode = lines.last().trim().toIntOrNull() ?: return null
            val pwd = lines.dropLast(1).joinToString("\n").trimEnd('\r')
            if (pwd.isBlank()) return null
            return TerminalStatus(pwd, exitCode)
        }

        /**
         * Converts an absolute `pwd` back to a workspace-relative path. Returns null
         * when the command escaped the root (symlinks included), so callers can reset
         * to the workspace root.
         */
        internal fun relativizeCwd(pwd: String, root: String): String? {
            val path = pwd.trim().trimEnd('/')
            val base = root.trim().trimEnd('/')
            if (path == base) return ""
            if (base.isEmpty() || !path.startsWith("$base/")) return null
            return sanitizeCwdOrNull(path.removePrefix("$base/"))
        }

        /**
         * Minimal terminal environment. No ANTHROPIC_* entries: a terminal command has
         * no business seeing the model endpoint or its credentials.
         */
        internal fun baseEnv(
            guest: Boolean,
            home: String,
            tmp: String,
            shell: String,
        ): Map<String, String> = linkedMapOf(
            "PATH" to if (guest) {
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin"
            } else {
                "/system/bin:/system/xbin"
            },
            "HOME" to home,
            "TMPDIR" to tmp,
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "SHELL" to shell,
        )

        /** Bounded read: the status file is tiny, but never trust its size blindly. */
        internal fun readCapped(file: File, maxBytes: Int): String {
            val buffer = ByteArray(maxBytes)
            var total = 0
            file.inputStream().use { input ->
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n <= 0) break
                    total += n
                }
            }
            return String(buffer, 0, total, Charsets.UTF_8)
        }

        /**
         * Appends at most [maxChars] total characters. Returns the accepted prefix, or
         * null when the cap is already reached. A non-null result shorter than [chunk]
         * means the cap was crossed; the caller must keep draining but stop retaining.
         */
        internal fun appendCapped(
            output: StringBuilder,
            chunk: CharSequence,
            maxChars: Int,
        ): String? {
            val remaining = maxChars - output.length
            if (remaining <= 0 || chunk.length == 0) return null
            val take = minOf(chunk.length, remaining)
            output.append(chunk, 0, take)
            return chunk.subSequence(0, take).toString()
        }
    }
}

internal data class TerminalStatus(val pwd: String, val exitCode: Int)

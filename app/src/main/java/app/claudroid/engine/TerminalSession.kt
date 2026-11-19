package app.claudroid.engine

import android.content.Context
import app.claudroid.core.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One long-lived interactive shell for the workspace terminal (Termius-style).
 *
 * The guest gets a real pseudo-terminal from util-linux `script(1)`, which is part of
 * the Debian rootfs and inherits the `/dev` bind that PRoot and chroot already provide.
 * `script` forks the shell under a PTY, so Ctrl-C, `apt` confirmation prompts, `top`,
 * `vim` and line editing all work; the shell keeps running between commands instead of
 * exiting after each one. Without that PTY the same shell would still work, but every
 * full-screen program would fail with "Inappropriate ioctl for device".
 *
 * The session owns its process, a throttled output pump, resize probes and the exit
 * status file. It is deliberately separate from [TerminalRunner]: the one-shot runner
 * keeps its "command per process" contract and its tests.
 *
 * Events emitted through [emit] follow the frozen bridge protocol:
 * `started`, `chunk`, `exit`, `stopped`, `error`.
 */
class TerminalSession(
    private val ctx: Context,
    private val root: RootExec,
    private val workspace: File,
    private val emit: suspend (Map<String, Any?>) -> Unit,
) {

    enum class Mode { Proot, ProotRoot, Chroot, Direct }

    private enum class State { Idle, Running, Exited, Stopped }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Unique per session: control files live under the home bind (`/root` in the guest)
     * next to the user's dotfiles, so a stale file from an earlier session must never be
     * mistaken for this one's pid/tty/exit status.
     */
    private val token = "term-${System.nanoTime()}-${UUID.randomUUID().toString().take(8)}"
    private val home = Paths.home(ctx)
    private val engineDir = Paths.engine(ctx)

    private val innerHost = File(home, "$token.inner")
    private val exitHost = File(home, "$token.exit")
    private val pidHost = File(home, "$token.pid")
    private val ttyHost = File(home, "$token.tty")
    private val rootPidHost = File(engineDir, "$token.root.pid")

    private val pendingLock = Any()
    private val pending = StringBuilder()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /**
     * Single-writer input queue. Keystrokes arrive on the platform thread; writing the
     * pipe from there (or from a pool of IO threads) could interleave bytes or reorder
     * two fast calls. One writer coroutine drains this in order instead.
     */
    private val inputQueue = Channel<String>(Channel.UNLIMITED)
    private val readerFinished = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()

    private val stateLock = Any()
    private val finished = AtomicBoolean(false)
    private val chrootActive = AtomicBoolean(false)
    private val resizeBusy = AtomicBoolean(false)

    @Volatile private var state = State.Idle
    @Volatile private var process: Process? = null
    @Volatile private var mode: Mode? = null
    @Volatile private var plan: EngineManager.ShellPlan? = null
    @Volatile private var guestEnv: Map<String, String> = emptyMap()

    /**
     * Starts the shell and returns once the process exists. Failure is thrown so the
     * `terminal.start` call can report `ok:false`; nothing is left running in that case.
     */
    suspend fun start(plan: EngineManager.ShellPlan, rows: Int, cols: Int) =
        withContext(Dispatchers.IO) {
            check(state == State.Idle) { "终端会话已在运行" }
            val safeRows = clampRows(rows)
            val safeCols = clampCols(cols)
            this@TerminalSession.plan = plan
            try {
                val (started, startedMode) = when {
                    plan.useProot || plan.prootViaRoot -> launchProot(plan, safeRows, safeCols)
                    plan.useRoot && plan.useChroot -> launchChroot(plan, safeRows, safeCols)
                    else -> launchDirect()
                }
                process = started
                mode = startedMode
                state = State.Running
                // Start the only pipe writer before `started` reaches the UI, so the
                // first keystrokes already land in an ordered queue.
                scope.launch { writeLoop(started) }
                emit(mapOf("type" to "started"))
                scope.launch { runSession(started) }
            } catch (e: CancellationException) {
                runCatching { process?.destroyForcibly() }
                finishSession()
                throw e
            } catch (e: Exception) {
                // A session that failed to start must not leave a half-written script or
                // a chroot counter behind; `finishSession` is idempotent.
                runCatching { process?.destroyForcibly() }
                finishSession()
                emitError(e.message ?: "终端无法启动")
                throw IllegalStateException("终端无法启动：${e.message ?: "未知错误"}", e)
            }
        }

    /** Suspends until the shell exited (naturally or through [stop]) and cleanup ran. */
    suspend fun awaitTermination() = terminated.await()

    /**
     * Queues raw keyboard input. Never blocks: ordering is preserved by [inputQueue]
     * and the single [writeLoop] writer, not by the caller's dispatch context.
     */
    fun write(data: String) {
        if (state != State.Running) return
        val clipped = clampInput(data)
        if (clipped.isEmpty()) return
        inputQueue.trySend(clipped)
    }

    /**
     * Best-effort PTY resize. Never throws and never blocks the caller: the probe is a
     * short-lived `stty -F <tty>` process, and only one may run at a time (a burst of
     * rotation/ime events would otherwise spawn a su process per event).
     */
    fun resize(rows: Int, cols: Int) {
        val currentMode = mode ?: return
        if (currentMode == Mode.Direct || state != State.Running) return
        val safeRows = clampRows(rows)
        val safeCols = clampCols(cols)
        if (!resizeBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                resizeOnce(currentMode, safeRows, safeCols)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A missed resize is repaired by the next one; this path has no UI.
            } finally {
                resizeBusy.set(false)
            }
        }
    }

    /**
     * Ends the session once: close stdin, signal the process group we recorded for root
     * launches, then destroy. `destroyForcibly` is the 800 ms backstop.
     *
     * Exactly one of `exit` / `stopped` is emitted for a session: the natural-exit path
     * transitions Running -> Exited under [stateLock]; this method takes Running ->
     * Stopped first, so whichever loses the race stays silent.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        val p = process ?: return@withContext
        val owner = synchronized(stateLock) {
            if (state == State.Running) {
                state = State.Stopped
                true
            } else {
                false
            }
        }
        if (!owner) {
            // A natural exit is already finishing (or finished); it emits `exit`.
            withTimeoutOrNull(STOP_GRACE_MS) { terminated.await() }
            return@withContext
        }
        try {
            inputQueue.close()
            runCatching { p.outputStream.close() }
            when (mode) {
                // The shell inside the PTY is the process-group leader (script(1) makes
                // it the session leader); the negative pid reaches script/chroot too.
                Mode.Chroot -> killGroup(pidHost)
                // The recorded pid is PRoot itself (the wrapper execs in place). A plain
                // `destroy()` would only signal `su` and strand PRoot.
                Mode.ProotRoot -> killRootPid(rootPidHost)
                else -> Unit
            }
            runCatching { p.destroy() }
            if (!runCatching { p.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                runCatching { p.destroyForcibly() }
                runCatching { p.waitFor(FAST_WAIT_MS, TimeUnit.MILLISECONDS) }
            }
            // If PRoot had to be SIGKILLed, --kill-on-exit never ran and the traced
            // shell/apt can survive as orphans. The guest shell has the app uid in PRoot
            // mode, so a same-uid TERM to its recorded process group works without su.
            if (mode == Mode.Proot) killDirect(pidHost)
            runCatching { p.inputStream.close() }
            // Let the pump deliver what the shell wrote before it died, then report the
            // stop. The reader is also unblocked by the pipe close above.
            withTimeoutOrNull(DRAIN_MS) { readerFinished.await() }
            emit(mapOf("type" to "stopped"))
        } finally {
            finishSession()
        }
    }

    // ----------------------------------------------------------------- launching

    private fun launchProot(
        plan: EngineManager.ShellPlan,
        rows: Int,
        cols: Int,
    ): Pair<Process, Mode> {
        val shell = guestShell(plan)
        val env = TerminalRunner.baseEnv(guest = true, home = "/root", tmp = "/tmp", shell = shell)
        guestEnv = env
        writeInner(rows, cols, shell)
        val outer = outerScript(
            guestCwd = WORK_DIR,
            shell = shell,
            innerFile = guestPath(innerHost),
            exitFile = guestPath(exitHost),
        )
        val spec = ProotRunner(ctx).shellSpec(
            installation = plan.installation,
            workDir = WORK_DIR,
            script = outer,
            baseEnv = env,
            workspace = workspace,
        )
        return if (plan.prootViaRoot) {
            // Root + no chroot(1): PRoot under su. The wrapper records PRoot's pid so
            // stop can deliver SIGTERM (and PRoot's --kill-on-exit tears down the shell).
            val wrapper = TurnScripts.rootExec(spec.env, spec.argv, pidFile = rootPidHost.absolutePath)
            val script = writeEngineScript(wrapper)
            startSu(script) to Mode.ProotRoot
        } else {
            startWithEnv(spec.argv, spec.env, spec.cwd) to Mode.Proot
        }
    }

    private fun launchChroot(
        plan: EngineManager.ShellPlan,
        rows: Int,
        cols: Int,
    ): Pair<Process, Mode> {
        // A previous chroot may have left its binds behind; sweep them before opening a
        // new one. Skipped while another chroot session is alive.
        root.unmountJail(plan.installation.jailPath)
        // Count before the process exists: as soon as startSu returns, a concurrent chat
        // turn that ends can call unmountJail; with the counter still at 0 it would
        // detach the binds this session is about to create. finishSession's CAS releases
        // the count if startSu throws.
        root.chrootBegin()
        chrootActive.set(true)
        val shell = guestShell(plan)
        val env = TerminalRunner.baseEnv(guest = true, home = "/root", tmp = "/tmp", shell = shell)
        guestEnv = env
        writeInner(rows, cols, shell)
        val outer = outerScript(
            guestCwd = WORK_DIR,
            shell = shell,
            innerFile = guestPath(innerHost),
            exitFile = guestPath(exitHost),
        )
        val wrapper = TurnScripts.chrootShell(
            env = env,
            jail = plan.installation.jailPath,
            workspace = workspace.absolutePath,
            home = home.absolutePath,
            chrootTool = root.busybox() ?: "chroot",
            guestScript = outer,
            pidFile = rootPidHost.absolutePath,
        )
        val script = writeEngineScript(wrapper)
        return startSu(script) to Mode.Chroot
    }

    /** Last resort: an app-side interactive shell. Persistent, but no PTY (no full-screen apps). */
    private fun launchDirect(): Pair<Process, Mode> {
        val env = TerminalRunner.baseEnv(
            guest = false,
            home = home.absolutePath,
            tmp = ctx.cacheDir.absolutePath,
            shell = DIRECT_SHELL,
        )
        guestEnv = env
        val process = startWithEnv(listOf(DIRECT_SHELL, "-i"), env, workspace)
        return process to Mode.Direct
    }

    private fun guestShell(plan: EngineManager.ShellPlan): String =
        if (plan.installation.hasBash) "/bin/bash" else "/bin/sh"

    private fun writeInner(rows: Int, cols: Int, shell: String) {
        innerHost.writeText(
            innerScript(
                pidFile = guestPath(pidHost),
                ttyFile = guestPath(ttyHost),
                rows = rows,
                cols = cols,
                shell = shell,
            ),
        )
    }

    private fun startWithEnv(
        argv: List<String>,
        env: Map<String, String>,
        cwd: File,
    ): Process = ProcessBuilder(argv).directory(cwd).apply {
        environment().clear()
        environment().putAll(env)
        // One pane: stderr is part of the terminal stream.
        redirectErrorStream(true)
    }.start()

    private fun startSu(script: File): Process = startWithEnv(
        listOf(root.suBinary() ?: "su", "-c", "/system/bin/sh ${TurnScripts.quote(script.absolutePath)}"),
        mapOf("PATH" to "/system/bin:/system/xbin", "HOME" to "/"),
        ctx.filesDir,
    )

    private fun writeEngineScript(body: String): File =
        File(engineDir, "$token.sh").apply { writeText(body) }

    // -------------------------------------------------------------- input writer

    /**
     * Sole writer to the shell's stdin. One coroutine draining [inputQueue] is what
     * guarantees keystroke order; a write failure means the shell is gone (the session
     * end path owns the exit/stopped event), so close the queue and stop buffering.
     */
    private suspend fun writeLoop(p: Process) {
        for (chunk in inputQueue) {
            try {
                p.outputStream.write(chunk.toByteArray(Charsets.UTF_8))
                p.outputStream.flush()
            } catch (_: IOException) {
                inputQueue.close()
                return
            }
        }
    }

    // -------------------------------------------------------------- output pump

    private suspend fun runSession(p: Process) {
        val readerJob = scope.launch { readLoop(p) }
        val flusherJob = scope.launch { flushLoop() }
        try {
            p.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // A grandchild may inherit stdout and keep the pipe open after the shell exits;
        // give the reader a moment to drain, then close the pipe so it cannot leak.
        withTimeoutOrNull(DRAIN_MS) { readerFinished.await() }
        runCatching { p.inputStream.close() }
        withTimeoutOrNull(DRAIN_MS) { readerJob.join() }
        wake.trySend(Unit)
        withTimeoutOrNull(DRAIN_MS) { flusherJob.join() }
        if (flusherJob.isActive) flusherJob.cancel()

        val exitCode = resolveExitCode(p)
        val natural = synchronized(stateLock) {
            if (state == State.Running) {
                state = State.Exited
                true
            } else {
                false
            }
        }
        if (natural) emit(mapOf("type" to "exit", "exitCode" to exitCode))
        finishSession()
    }

    /**
     * Accumulates decoded text; the flusher below is the only emitter, so chunk order is
     * the order the characters were read. InputStreamReader decodes UTF-8, which keeps a
     * multi-byte character from being split across chunks.
     */
    private suspend fun readLoop(p: Process) {
        try {
            InputStreamReader(p.inputStream, Charsets.UTF_8).use { input ->
                val buffer = CharArray(READ_BUFFER_CHARS)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    val size = synchronized(pendingLock) {
                        pending.append(buffer, 0, n)
                        pending.length
                    }
                    if (size >= FLUSH_BYTES) wake.trySend(Unit)
                }
            }
        } catch (e: IOException) {
            // The pipe is closed by stop() and by the end of the shell; both report
            // their own event. Only a failure while the shell is still alive is real.
            if (state == State.Running) emitError("读取终端输出失败：${e.message ?: "未知错误"}")
        } catch (e: CancellationException) {
            throw e
        } finally {
            readerFinished.complete(Unit)
            wake.trySend(Unit)
        }
    }

    /**
     * At most one `chunk` per [FLUSH_INTERVAL_MS] (or one per [FLUSH_BYTES] once the
     * buffer grows), instead of one platform-channel hop per byte. A burst from `top`
     * therefore becomes a handful of frames rather than hundreds of setState calls.
     */
    private suspend fun flushLoop() {
        while (!readerFinished.isCompleted) {
            withTimeoutOrNull(FLUSH_INTERVAL_MS) { wake.receive() }
            flushPending()
        }
        flushPending()
    }

    private suspend fun flushPending() {
        val text = synchronized(pendingLock) {
            if (pending.isEmpty()) {
                ""
            } else {
                pending.toString().also { pending.setLength(0) }
            }
        }
        if (text.isEmpty()) return
        emit(mapOf("type" to "chunk", "text" to text))
    }

    // ------------------------------------------------------------------ resize

    private fun resizeOnce(currentMode: Mode, rows: Int, cols: Int) {
        val tty = parseTty(readSmall(ttyHost)) ?: return
        val plan = plan ?: return
        val args = listOf("/usr/bin/stty", "-F", tty, "rows", rows.toString(), "cols", cols.toString())
        when (currentMode) {
            Mode.Proot -> {
                val spec = ProotRunner(ctx).runSpec(
                    installation = plan.installation,
                    entry = args,
                    baseEnv = guestEnv,
                    workDir = "/root",
                )
                runShort(spec.argv, spec.env, spec.cwd)
            }
            Mode.ProotRoot -> {
                val spec = ProotRunner(ctx).runSpec(
                    installation = plan.installation,
                    entry = args,
                    baseEnv = guestEnv,
                    workDir = "/root",
                )
                runRootScript(TurnScripts.rootExec(spec.env, spec.argv, pidFile = null))
            }
            Mode.Chroot -> {
                val wrapper = TurnScripts.chrootShell(
                    env = guestEnv,
                    jail = plan.installation.jailPath,
                    workspace = workspace.absolutePath,
                    home = home.absolutePath,
                    chrootTool = root.busybox() ?: "chroot",
                    guestScript = resizeScript(tty, rows, cols),
                    pidFile = null,
                )
                runRootScript(wrapper)
            }
            Mode.Direct -> Unit
        }
    }

    /** Runs a host-side probe with a hard timeout; stdout is discarded. */
    private fun runShort(argv: List<String>, env: Map<String, String>, cwd: File) {
        val p = startWithEnv(argv, env, cwd)
        try {
            if (!p.waitFor(RESIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        } finally {
            runCatching { p.inputStream.close() }
            runCatching { p.outputStream.close() }
            if (p.isAlive) runCatching { p.destroyForcibly() }
        }
    }

    /** Runs a generated root script; `su` is bounded by [RESIZE_TIMEOUT_MS]. */
    private fun runRootScript(body: String) {
        val script = File(engineDir, "$token.resize.sh")
        try {
            script.writeText(body)
            root.execute(
                listOf(root.suBinary() ?: "su", "-c", "/system/bin/sh ${TurnScripts.quote(script.absolutePath)}"),
                RESIZE_TIMEOUT_MS,
            )
        } finally {
            runCatching { script.delete() }
        }
    }

    // ------------------------------------------------------------------ cleanup

    /**
     * Ends everything exactly once: closes the pipes, releases the chroot session (and
     * sweeps its binds when no other chroot is alive), deletes this token's control
     * files and lets [awaitTermination] return.
     */
    private fun finishSession() {
        if (!finished.compareAndSet(false, true)) return
        // The writer drains the closed queue and exits; scope.cancel() below is the
        // backstop for a coroutine blocked inside a pipe write.
        inputQueue.close()
        runCatching {
            process?.let {
                it.inputStream.close()
                it.outputStream.close()
            }
        }
        if (chrootActive.compareAndSet(true, false)) {
            runCatching { root.chrootEnd() }
            plan?.installation?.jailPath?.let { runCatching { root.unmountJail(it) } }
        }
        cleanupFiles()
        terminated.complete(Unit)
        scope.cancel()
    }

    private fun cleanupFiles() {
        listOf(home, engineDir).forEach { dir ->
            dir.listFiles { file -> file.name.startsWith("$token.") }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun resolveExitCode(p: Process): Int {
        parseExitCode(readSmall(exitHost))?.let { return it }
        // The direct shell has no status file; a killed process may not have written one.
        return runCatching { p.exitValue() }.getOrDefault(-1)
    }

    /** Reads a control file written by the guest through the home bind. */
    private fun readSmall(file: File): String? = runCatching {
        if (file.isFile) TerminalRunner.readCapped(file, MAX_CONTROL_BYTES) else null
    }.getOrNull()

    private fun readPid(file: File): Int? = parsePid(readSmall(file))

    /** TERM the process group led by [pid]; the kernel does not signal group 0/-1 by accident. */
    private fun killGroup(file: File) {
        val pid = readPid(file) ?: return
        suKill("-TERM -$pid")
    }

    /**
     * Root-mode PRoot pid. The process-group kill is tried first, but `su` does not
     * guarantee PRoot is a process-group leader and a group kill of a non-leader fails
     * with ESRCH; fall back to the exact pid, the same shape [TerminalRunner.terminate]
     * uses.
     */
    private fun killRootPid(file: File) {
        val pid = readPid(file) ?: return
        if (!suKill("-TERM -$pid")) suKill("-TERM $pid")
    }

    /**
     * Same-uid fallback for a force-killed PRoot: `sh` supplies the kill builtin, so no
     * `su` (and no guarantee that a `/system/bin/kill` binary exists) is needed.
     */
    private fun killDirect(file: File) {
        val pid = readPid(file) ?: return
        runCatching {
            val killer = ProcessBuilder(SYSTEM_SH, "-c", "kill -TERM -$pid").start()
            killer.waitFor(FAST_WAIT_MS, TimeUnit.MILLISECONDS)
            if (killer.isAlive) killer.destroyForcibly()
        }
    }

    /** Runs `kill <args>` through su; true only when su exited successfully. */
    private fun suKill(args: String): Boolean = runCatching {
        val su = root.suBinary() ?: return@runCatching false
        val killer = ProcessBuilder(su, "-c", "kill $args").start()
        val finished = killer.waitFor(FAST_WAIT_MS, TimeUnit.MILLISECONDS)
        if (killer.isAlive) killer.destroyForcibly()
        finished && killer.exitValue() == 0
    }.getOrDefault(false)

    private suspend fun emitError(message: String) {
        runCatching { emit(mapOf("type" to "error", "message" to message)) }
    }

    private fun guestPath(file: File): String = "/root/${file.name}"

    companion object {

        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80

        /** Terminal size bounds: the UI cannot usefully display more, and `stty` rejects 0. */
        const val MIN_ROWS = 5
        const val MAX_ROWS = 300
        const val MIN_COLS = 20
        const val MAX_COLS = 500

        /** One `terminal.write` may inject at most this many UTF-8 bytes. */
        const val MAX_INPUT_BYTES = 64 * 1024

        /** Output coalescing: one event per interval, or sooner once the buffer is big. */
        internal const val FLUSH_INTERVAL_MS = 30L
        internal const val FLUSH_BYTES = 64 * 1024

        private const val WORK_DIR = "/work"
        /** Shown once when the guest cannot allocate a PTY; the shell still works. */
        internal const val PTY_FALLBACK_NOTICE =
            "[claudroid] 容器内无法创建 PTY，已回退为普通交互 shell（vim/top 等全屏程序不可用）。"
        private const val DIRECT_SHELL = "/system/bin/sh"
        private const val SYSTEM_SH = "/system/bin/sh"
        private const val READ_BUFFER_CHARS = 8192
        private const val MAX_CONTROL_BYTES = 4096
        private const val DRAIN_MS = 500L
        private const val STOP_GRACE_MS = 800L
        private const val FAST_WAIT_MS = 1_000L
        private const val RESIZE_TIMEOUT_MS = 5_000L

        internal fun clampRows(rows: Int): Int = rows.coerceIn(MIN_ROWS, MAX_ROWS)

        internal fun clampCols(cols: Int): Int = cols.coerceIn(MIN_COLS, MAX_COLS)

        /**
         * Guest launcher. `script(1)` allocates the PTY and runs the inner script inside
         * it; `-e` forwards the shell's exit status, `-f` flushes every write so `top`
         * is not held in a userspace buffer.
         *
         * A preflight run is required: `script` may exist while the guest has no working
         * devpts (e.g. `/dev/pts` was not mounted), in which case every start would die
         * with "failed to create pseudo-terminal". If the probe fails, run the inner
         * script directly: the shell stays interactive, only full-screen programs are
         * unavailable. The probe deliberately omits `-e` for busybox compatibility.
         *
         * The exit status is written after the PTY closes so natural exits can report the
         * shell's own code (an `exit 3` in the terminal must not look like success).
         */
        internal fun outerScript(
            guestCwd: String,
            shell: String,
            innerFile: String,
            exitFile: String,
        ): String = buildString {
            appendLine(
                "cd ${TurnScripts.quote(guestCwd)} 2>/dev/null || cd ${TurnScripts.quote(WORK_DIR)}",
            )
            appendLine(
                "if command -v script >/dev/null 2>&1 && " +
                    "script -q -c true /dev/null >/dev/null 2>&1; then",
            )
            appendLine("  script -q -f -e -c ${TurnScripts.quote("$shell $innerFile")} /dev/null")
            appendLine("else")
            appendLine("  printf '%s\\n' ${TurnScripts.quote(PTY_FALLBACK_NOTICE)} >&2")
            appendLine("  ${TurnScripts.quote(shell)} ${TurnScripts.quote(innerFile)}")
            appendLine("fi")
            appendLine("__claudroid_exit=\$?")
            appendLine("printf '%s\\n' \"\$__claudroid_exit\" > ${TurnScripts.quote(exitFile)}")
        }

        /**
         * Runs inside the PTY: report the shell pid (stop uses it as the process-group
         * id; `exec` keeps the pid), publish the slave device for resize probes, apply
         * the initial window size and become the interactive shell.
         *
         * `tty`/`stty` are guarded: on the no-PTY fallback stdin is a pipe, and their
         * errors would otherwise be printed into the terminal output.
         */
        internal fun innerScript(
            pidFile: String,
            ttyFile: String,
            rows: Int,
            cols: Int,
            shell: String,
        ): String = buildString {
            appendLine("echo \$\$ > ${TurnScripts.quote(pidFile)}")
            appendLine("if [ -t 0 ]; then")
            appendLine("  tty > ${TurnScripts.quote(ttyFile)} 2>/dev/null")
            appendLine("  stty rows ${clampRows(rows)} cols ${clampCols(cols)} 2>/dev/null")
            appendLine("fi")
            appendLine("exec ${TurnScripts.quote(shell)} -i")
        }

        /** `stty -F` needs the slave device path; only paths under `/dev` are trusted. */
        internal fun parseTty(text: String?): String? {
            val value = text?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: return null
            if (!value.startsWith("/dev/")) return null
            if (value.contains('\u0000')) return null
            return value
        }

        /**
         * Exit code from the status file. Empty, non-numeric, negative and >255 values
         * are all "unknown" so the caller can fall back to the process status.
         */
        internal fun parseExitCode(text: String?): Int? {
            val value = text?.trim()?.toIntOrNull() ?: return null
            return value.takeIf { it in 0..255 }
        }

        /** First line of a pid file, as a pid we are willing to signal. */
        internal fun parsePid(text: String?): Int? = text?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 1 }

        /**
         * Truncates one write to [MAX_INPUT_BYTES] UTF-8 bytes without splitting a
         * multi-byte character (a broken sequence would show up as U+FFFD in the shell).
         */
        internal fun clampInput(data: String): String {
            val bytes = data.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_INPUT_BYTES) return data
            var end = MAX_INPUT_BYTES
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return String(bytes, 0, end, Charsets.UTF_8)
        }

        internal fun resizeScript(tty: String, rows: Int, cols: Int): String =
            "exec /usr/bin/stty -F ${TurnScripts.quote(tty)} " +
                "rows ${clampRows(rows)} cols ${clampCols(cols)}"
    }
}

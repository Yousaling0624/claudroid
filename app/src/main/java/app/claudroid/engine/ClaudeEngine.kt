package app.claudroid.engine

import android.content.Context
import app.claudroid.core.Installation
import app.claudroid.core.Logx
import app.claudroid.core.Paths
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the CLI child process for one conversation turn and turns its stdout into
 * [EngineEvent]s. One process per turn: the CLI is started with `-p` plus
 * `--resume <session-id>` so upstream keeps the context across turns, and we never have
 * to keep a fragile interactive PTY alive in the background.
 */
class ClaudeEngine(private val ctx: Context, private val root: RootExec) {

    data class LaunchPlan(
        val installation: Installation,
        val useRoot: Boolean,
        val useChroot: Boolean,
        /** No root: run the same jail through Termux PRoot. */
        val useProot: Boolean = false,
        /** When set, Claude Code talks to the in-app OpenAI-compatible proxy instead of upstream. */
        val proxyBaseUrl: String? = null,
    ) {
        val label: String
            get() = when {
                useProot -> "proot（免 root）"
                useRoot && useChroot -> "root + chroot"
                useRoot -> "root（加载器直启）"
                else -> "应用目录直启"
            }
    }

    @Volatile private var process: Process? = null
    @Volatile private var cancelled = false
    /** Pid file written by the launch wrapper: it holds the real CLI pid after `exec`. */
    @Volatile private var turnPidFile: File? = null
    @Volatile private var turnPidNeedsRoot = false
    private val turnLock = java.util.concurrent.atomic.AtomicBoolean(false)

    val isRunning: Boolean get() = process?.isAlive == true || turnLock.get()

    /** Clears a stop left over from the previous turn; a new user turn starts un-cancelled. */
    fun resetCancel() {
        // Never clear the stop of a turn that is still winding down: another chat
        // controller may send while this one is being stopped.
        if (turnLock.get() || process != null) return
        cancelled = false
    }

    fun cancel() {
        cancelled = true
        // The process is set before the cancelled check after start, so either this sees
        // it, or runTurn sees the flag and kills the process itself. No lost stops.
        val p = process ?: return
        reapAsync(p, turnPidFile, turnPidNeedsRoot)
    }

    private fun reapAsync(p: Process, pidFile: File?, viaRoot: Boolean) {
        Thread({ killTurn(p, pidFile, viaRoot) }, "claudroid-cancel").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Ends one turn: the direct child is `su`/proot in most modes, and killing only that
     * leaves the actual CLI alive with the stdout pipe still open. Signal the pid the
     * wrapper recorded as well, then fall back to force-killing the wrapper.
     */
    private fun killTurn(p: Process, pidFile: File?, viaRoot: Boolean) {
        runCatching {
            var pid = readPid(pidFile)
            pid?.let { signalPid(it, viaRoot, "TERM") }
            runCatching { p.destroy() }
            // The wrapper may still be starting and about to publish its pid; give it a
            // moment instead of assuming it never will.
            if (pidFile != null) {
                val deadline = nowMs() + 1_200
                while (pid == null && nowMs() < deadline) {
                    Thread.sleep(50)
                    pid = readPid(pidFile)
                }
            }
            if (!p.waitFor(700, TimeUnit.MILLISECONDS)) {
                runCatching { p.destroyForcibly() }
            }
            // Harmless when the pid is already gone; required when TERM was ignored.
            pid?.let { signalPid(it, viaRoot, "KILL") }
            p.waitFor(2_000, TimeUnit.MILLISECONDS)
        }
    }

    private fun readPid(file: File?): Int? = runCatching {
        file?.takeIf { it.isFile }
            ?.readText()
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.toIntOrNull()
    }.getOrNull()?.takeIf { it > 1 && it != android.os.Process.myPid() }

    private fun signalPid(pid: Int, viaRoot: Boolean, signal: String) {
        runCatching {
            val command = if (viaRoot) {
                val su = root.suBinary() ?: return
                listOf(su, "-c", "kill -s $signal $pid")
            } else {
                // Proot tracees and direct launches run with our uid, so no su needed.
                listOf("/system/bin/kill", "-s", signal, pid.toString())
            }
            val killer = ProcessBuilder(command).start()
            if (!killer.waitFor(5, TimeUnit.SECONDS)) killer.destroyForcibly()
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** Pid files are tiny; old ones only matter to a cancel worker that is already done. */
    private fun cleanStalePidFiles() {
        val cutoff = System.currentTimeMillis() - 10 * 60_000
        listOf(Paths.engine(ctx), Paths.home(ctx)).forEach { dir ->
            dir.listFiles { f -> f.name.startsWith("turn-") && f.name.endsWith(".pid") }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    /** Blocks until the turn finishes. Returns the exit code, or -2 when the user stopped it. */
    fun runTurn(
        prompt: String,
        resumeSessionId: String?,
        settings: AppSettings,
        plan: LaunchPlan,
        onEvent: (EngineEvent) -> Unit,
    ): Int {
        if (!turnLock.compareAndSet(false, true)) {
            onEvent(EngineEvent.Failed("已有任务正在执行。请先停止当前任务，或等它结束。"))
            return -3
        }
        try {
            // A stop that arrived before this call (while settings/proxy were prepared)
            // must win; resetCancel() is called by the controller before a new turn.
            if (cancelled) return -2
            val inst = plan.installation
            val binary = inst.binary()
            val loader = inst.loader()
            val args = buildArgs(prompt, resumeSessionId, settings, inst.version)
            val env = buildEnv(settings, plan)
            val workspace = Paths.workspace(ctx)
            val home = Paths.home(ctx)

            cleanStalePidFiles()
            val pidName = "turn-${System.nanoTime()}.pid"
            val pidFile: File? = when {
                plan.useProot -> File(home, pidName)
                plan.useRoot -> File(Paths.engine(ctx), pidName)
                else -> null
            }
            pidFile?.delete()
            turnPidFile = pidFile
            turnPidNeedsRoot = plan.useRoot

            var cwd = workspace
            val command: List<String>
            val processEnv = env.toMutableMap()
            val script = File(ctx.filesDir, "engine/turn.sh").apply { parentFile?.mkdirs() }

            when {
                plan.useProot -> {
                    // The wrapper writes its pid to /root (bound to filesDir/home) and
                    // execs the CLI, so Stop can kill the real process inside proot.
                    val spec = ProotRunner(ctx).spec(inst, args, env, pidFile = "/root/$pidName")
                    command = spec.argv
                    cwd = spec.cwd
                    processEnv.clear()
                    processEnv.putAll(spec.env)
                }
                plan.useRoot && plan.useChroot -> {
                    script.writeText(
                        TurnScripts.chroot(
                            env = env,
                            jail = inst.jailPath,
                            workspace = workspace.absolutePath,
                            home = home.absolutePath,
                            chrootTool = root.busybox() ?: "${inst.jailPath}/bin/busybox",
                            loaderName = "/lib/ld-musl-${inst.arch}.so.1",
                            binary = "/opt/claude/${inst.version}/claude",
                            args = args,
                            hasBash = inst.hasBash,
                            pidFile = pidFile?.absolutePath,
                        )
                    )
                    command = listOf(root.suBinary() ?: "su", "-c", "sh ${script.absolutePath}")
                    processEnv["HOME"] = "/root"
                    processEnv["TMPDIR"] = "/tmp"
                }
                plan.useRoot -> {
                    script.writeText(
                        TurnScripts.rootDirect(
                            env = env,
                            cwd = workspace.absolutePath,
                            loader = loader.absolutePath,
                            binary = binary.absolutePath,
                            args = args,
                            pidFile = pidFile?.absolutePath,
                        )
                    )
                    command = listOf(root.suBinary() ?: "su", "-c", "sh ${script.absolutePath}")
                    processEnv["HOME"] = home.absolutePath
                    processEnv["TMPDIR"] = ctx.cacheDir.absolutePath
                }
                else -> {
                    ShellSupport.ensureDirectShell(ctx)
                    command = listOf(loader.absolutePath, binary.absolutePath) + args
                    processEnv["HOME"] = home.absolutePath
                    processEnv["TMPDIR"] = ctx.cacheDir.absolutePath
                }
            }
            if (!plan.useProot) {
                processEnv["SHELL"] = if (plan.useRoot && plan.useChroot) {
                    // Inside the jail: real bash when bundled, otherwise busybox ash.
                    if (inst.hasBash) "/bin/bash" else "/bin/sh"
                } else {
                    // Host-side launch: there is no /bin/bash on Android. The wrapper in
                    // filesDir/bin forwards to mksh so the Bash tool at least finds a shell.
                    File(ShellSupport.ensureDirectShell(ctx), "bash").absolutePath
                }
            }

            Logx.i(
                "启动 CLI（${plan.label}, model=${settings.effectiveModelArg().ifBlank { "默认" }}, " +
                    "resume=$resumeSessionId）",
                ctx,
            )

            val pb = ProcessBuilder(command).directory(cwd)
            processEnv.forEach { (k, v) -> pb.environment()[k] = v }

            val p = runCatching { pb.start() }.getOrElse {
                onEvent(EngineEvent.Failed("CLI 无法启动：${it.message}"))
                return -1
            }
            process = p
            // Print mode never reads stdin. Closing it avoids the CLI's 3 s "no stdin data
            // received" grace period before the first request (and a warning on stderr).
            runCatching { p.outputStream.close() }
            if (cancelled) {
                // Stop landed while the process was starting: cancel() saw no process, so
                // this side owns the kill.
                killTurn(p, pidFile, plan.useRoot)
                return -2
            }

            val stderrThread = Thread {
                runCatching {
                    p.errorStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank() && !cancelled) {
                            onEvent(EngineEvent.Log(line, isError = true))
                        }
                    }
                }
            }.also { it.isDaemon = true; it.start() }

            val parser = StreamParser()
            TurnOutputPump(
                stream = p.inputStream,
                isCancelled = { cancelled },
                isProcessAlive = { p.isAlive },
                onLine = { line -> parser.parse(line).forEach(onEvent) },
                onFailure = { t -> onEvent(EngineEvent.Failed("读取输出失败：${t.message}")) },
            ).run()

            if (cancelled) {
                // cancel()'s worker is already killing the pid/wrapper; wait a bounded
                // amount so a new turn cannot start on top of a still-running CLI.
                runCatching { p.waitFor(2_500, TimeUnit.MILLISECONDS) }
            } else {
                runCatching { p.waitFor() }
            }
            stderrThread.join(1_500)
            val code = if (cancelled) -2 else runCatching { p.exitValue() }.getOrDefault(-1)
            onEvent(EngineEvent.Exited(code))
            return code
        } finally {
            // Safety net: whatever ended this turn (including an unexpected throw), never
            // leave the CLI behind while a new turn can start. Normal exits have already
            // reaped it, so this only fires on an abnormal path.
            val p = process
            if (p != null && p.isAlive) reapAsync(p, turnPidFile, turnPidNeedsRoot)
            process = null
            turnLock.set(false)
        }
    }

    private fun buildEnv(s: AppSettings, plan: LaunchPlan): Map<String, String> {
        val env = linkedMapOf<String, String>()
        env["PATH"] = when {
            plan.useProot || plan.useChroot ->
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin"
            else -> "/system/bin:/system/xbin:${File(ctx.filesDir, "bin").absolutePath}"
        }
        env["LANG"] = "C.UTF-8"
        env["TERM"] = "xterm-256color"
        val proxy = plan.proxyBaseUrl
        env["ANTHROPIC_BASE_URL"] = proxy ?: s.normalizedBaseUrl()
        if (proxy != null) {
            // The real key stays inside the proxy; the CLI only needs a non-empty token.
            env["ANTHROPIC_AUTH_TOKEN"] = "claudroid-local-proxy"
        } else {
            // A local Anthropic-compatible server often runs without a key, but the CLI
            // refuses to start with an empty credential.
            val key = s.apiKey.ifBlank { if (s.isLocalEndpoint()) "local-no-key" else "" }
            when (s.authStyle) {
                AuthStyle.BEARER -> env["ANTHROPIC_AUTH_TOKEN"] = key
                AuthStyle.API_KEY -> env["ANTHROPIC_API_KEY"] = key
                AuthStyle.BOTH -> {
                    env["ANTHROPIC_AUTH_TOKEN"] = key
                    env["ANTHROPIC_API_KEY"] = key
                }
            }
        }
        EngineEnv.applyModel(env, s)
        EngineEnv.applyFeatures(env, s)
        // The app owns upgrades and there is no telemetry endpoint we want to reach.
        env["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"
        env["DISABLE_TELEMETRY"] = "1"
        env["DISABLE_ERROR_REPORTING"] = "1"
        env["DISABLE_AUTOUPDATER"] = "1"
        env["CLAUDE_CODE_DISABLE_UPDATE_CHECK"] = "1"
        // Required: with uid 0 the CLI refuses --permission-mode bypassPermissions unless
        // it believes it is sandboxed. Upstream's own escape hatch (see claude-code#3490).
        env["IS_SANDBOX"] = "1"
        if (s.maxTurns > 0) env["CLAUDE_CODE_MAX_TURNS"] = s.maxTurns.toString()
        // user supplied KEY=VALUE lines
        s.extraEnv.lineSequence().map { it.trim() }.filter { it.contains("=") && !it.startsWith("#") }
            .forEach { line ->
                val k = line.substringBefore("=").trim()
                val v = line.substringAfter("=").trim()
                if (k.isNotEmpty()) env[k] = v
            }
        return env
    }

    /**
     * Argument assembly is pure (it reads only [s] and the passed values), so it lives in
     * the companion object: the same code serves the real turn and a unit test that must
     * not spin up a CLI process or need an Android Context. `internal` keeps it out of the
     * public API while allowing `ClaudeEngine.buildArgs(...)` from tests.
     */
    companion object {
        internal fun buildArgs(
            prompt: String,
            resume: String?,
            s: AppSettings,
            cliVersion: String,
        ): List<String> {
            val args = mutableListOf(
                "-p", prompt,
                "--output-format", "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--permission-mode", s.permissionMode.cli,
            )
            // With a tier active this is the CLI alias (opus/sonnet/...), which lets the
            // ANTHROPIC_DEFAULT_*_MODEL mappings resolve it; otherwise it stays the raw model.
            val model = s.effectiveModelArg()
            if (model.isNotBlank()) args += listOf("--model", model)
            if (resume != null) args += listOf("--resume", resume)
            if (s.extraSystemPrompt.isNotBlank()) {
                args += listOf("--append-system-prompt", s.extraSystemPrompt)
                // A resumed conversation reuses the system prompt captured on its first
                // request. Without this, editing the setting would silently have no effect
                // (flag exists since 2.1.257).
                if (versionAtLeast(cliVersion, 2, 1, 257)) {
                    args += listOf("--system-prompt-snapshot", "off")
                }
            }
            if (s.maxTurns > 0) args += listOf("--max-turns", s.maxTurns.toString())
            if (s.effort.cli.isNotBlank()) args += listOf("--effort", s.effort.cli)
            return args
        }

        /** Compares the numeric major.minor.patch prefix of a CLI version string. */
        private fun versionAtLeast(version: String, major: Int, minor: Int, patch: Int): Boolean {
            val parts = Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }
                .take(3).toList()
            if (parts.size < 3) return false
            val want = listOf(major, minor, patch)
            for (i in 0 until 3) {
                val cmp = parts[i].compareTo(want[i])
                if (cmp != 0) return cmp > 0
            }
            return true
        }
    }
}

/**
 * Model env shared by the per-turn launch and the self-test. One function keeps both
 * paths resolving tier aliases identically, so a self-test failure cannot come from a
 * different model configuration than the real turn.
 */
internal object EngineEnv {
    /**
     * `--model` and `ANTHROPIC_MODEL` carry the same value: with a tier active both are
     * the CLI alias, so the `ANTHROPIC_DEFAULT_*_MODEL` entries from
     * [AppSettings.modelEnv] resolve it to the configured backend. Callers merge
     * `extraEnv` after this block, so a user override still wins.
     */
    fun applyModel(env: MutableMap<String, String>, settings: AppSettings) {
        // Auto deliberately leaves ANTHROPIC_MODEL unset: the CLI then resolves its
        // official default instead of being pinned to a model id.
        val model = settings.effectiveModelArg()
        if (model.isNotBlank()) env["ANTHROPIC_MODEL"] = model
        settings.subagentModel.trim().takeIf { it.isNotBlank() }
            ?.let { env["CLAUDE_CODE_SUBAGENT_MODEL"] = it }
        settings.autoModeModel.trim().takeIf { it.isNotBlank() }
            ?.let { env["CLAUDE_CODE_AUTO_MODE_MODEL"] = it }
        env.putAll(settings.modelEnv())
    }

    /**
     * Experimental feature switches. `extraEnv` is merged after this block, so a user
     * can always override a switch by setting the same variable explicitly.
     */
    fun applyFeatures(env: MutableMap<String, String>, settings: AppSettings) {
        // Agent teams are opt-in upstream; "0" keeps the feature off explicitly.
        env["CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"] = if (settings.agentTeams) "1" else "0"
        if (!settings.workflows) env["CLAUDE_CODE_DISABLE_WORKFLOWS"] = "1"
        if (!settings.autoMemory) env["CLAUDE_CODE_DISABLE_AUTO_MEMORY"] = "1"
        if (!settings.backgroundTasks) env["CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"] = "1"
        if (!settings.extendedThinking) env["CLAUDE_CODE_DISABLE_THINKING"] = "1"
    }
}

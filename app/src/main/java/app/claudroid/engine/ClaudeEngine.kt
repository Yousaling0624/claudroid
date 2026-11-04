package app.claudroid.engine

import android.content.Context
import app.claudroid.core.Installation
import app.claudroid.core.Logx
import app.claudroid.core.Paths
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import java.io.File

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
    private val turnLock = java.util.concurrent.atomic.AtomicBoolean(false)

    val isRunning: Boolean get() = process?.isAlive == true || turnLock.get()

    fun cancel() {
        cancelled = true
        val p = process ?: return
        runCatching { p.destroy() }
        Thread {
            Thread.sleep(700)
            runCatching { if (p.isAlive) p.destroyForcibly() }
        }.start()
    }

    /** Blocks until the turn finishes. Returns the CLI exit code. */
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
            cancelled = false
            val inst = plan.installation
            val binary = inst.binary()
            val loader = inst.loader()
            val args = buildArgs(prompt, resumeSessionId, settings, inst.version)
            val env = buildEnv(settings, plan)
            val workspace = Paths.workspace(ctx)
            val home = Paths.home(ctx)

            var cwd = workspace
            val command: List<String>
            val processEnv = env.toMutableMap()
            val script = File(ctx.filesDir, "engine/turn.sh").apply { parentFile?.mkdirs() }

            when {
                plan.useProot -> {
                    val spec = ProotRunner(ctx).spec(inst, args, env)
                    command = spec.argv
                    cwd = spec.cwd
                    processEnv.clear()
                    processEnv.putAll(spec.env)
                }
                plan.useRoot && plan.useChroot -> {
                    script.writeText(chrootScript(inst, args, env))
                    command = listOf(root.suBinary() ?: "su", "-c", "sh ${script.absolutePath}")
                    processEnv["HOME"] = "/root"
                    processEnv["TMPDIR"] = "/tmp"
                }
                plan.useRoot -> {
                    script.writeText(
                        buildString {
                            appendLine("#!/system/bin/sh")
                            env.forEach { (k, v) -> appendLine("export $k=${root.q(v)}") }
                            appendLine("cd ${root.q(workspace.absolutePath)}")
                            appendLine(
                                "exec ${root.q(loader.absolutePath)} ${root.q(binary.absolutePath)} " +
                                    args.joinToString(" ") { root.q(it) }
                            )
                        }
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
                "启动 CLI（${plan.label}, model=${settings.model}, resume=$resumeSessionId）",
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
                runCatching { p.destroyForcibly() }
            }

            val stderrThread = Thread {
                runCatching {
                    p.errorStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) onEvent(EngineEvent.Log(line, isError = true))
                    }
                }
            }.also { it.isDaemon = true; it.start() }

            val parser = StreamParser()
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    parser.parse(line).forEach(onEvent)
                }
            }.onFailure {
                if (!cancelled) onEvent(EngineEvent.Failed("读取输出失败：${it.message}"))
            }

            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            stderrThread.join(1500)
            process = null
            onEvent(EngineEvent.Exited(if (cancelled) -2 else code))
            return code
        } finally {
            turnLock.set(false)
        }
    }

    private fun buildArgs(prompt: String, resume: String?, s: AppSettings, cliVersion: String): List<String> {
        val args = mutableListOf(
            "-p", prompt,
            "--output-format", "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--permission-mode", s.permissionMode.cli,
        )
        if (s.model.isNotBlank()) args += listOf("--model", s.model)
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
        if (s.model.isNotBlank()) env["ANTHROPIC_MODEL"] = s.model
        if (s.smallModel.isNotBlank()) env["ANTHROPIC_SMALL_FAST_MODEL"] = s.smallModel
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

    private fun chrootScript(inst: Installation, args: List<String>, env: Map<String, String>): String {
        val q: (String) -> String = { root.q(it) }
        val jail = inst.jailPath
        val ws = Paths.workspace(ctx).absolutePath
        val chrootTool = root.busybox() ?: "${inst.jailPath}/bin/busybox"
        val jailBinary = "/opt/claude/${inst.version}/claude"
        val jailLoader = "/lib/ld-musl-${inst.arch}.so.1"
        val home = Paths.home(ctx).absolutePath
        val binds = listOf(
            "/proc" to "proc", "/dev" to "dev", "/sys" to "sys", "/system" to "system",
            "/data" to "data", "/storage/emulated/0" to "sdcard",
        )
        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("R=${q(jail)}")
            env.forEach { (k, v) -> appendLine("export $k=${q(v)}") }
            appendLine("export HOME=/root")
            appendLine("export TMPDIR=/tmp")
            appendLine("export SHELL=" + q(if (inst.hasBash) "/bin/bash" else "/bin/sh"))
            listOf("work", "proc", "dev", "sys", "system", "data", "sdcard", "tmp", "root").forEach {
                appendLine("mkdir -p \"\$R/$it\" 2>/dev/null")
            }
            appendLine("bind_mount() { grep -qs \" \$2 \" /proc/mounts || mount --bind \"\$1\" \"\$2\" 2>/dev/null; }")
            binds.forEach { (src, dst) -> appendLine("bind_mount $src \"\$R/$dst\"") }
            appendLine("bind_mount ${q(ws)} \"\$R/work\"")
            appendLine("bind_mount ${q(home)} \"\$R/root\"")
            appendLine("cd \"\$R/work\" 2>/dev/null || cd \"\$R\"")
            appendLine("exec ${q(chrootTool)} chroot \"\$R\" $jailLoader $jailBinary " + args.joinToString(" ") { q(it) })
        }
    }
}

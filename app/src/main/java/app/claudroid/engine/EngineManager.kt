package app.claudroid.engine

import android.content.Context
import app.claudroid.core.Installation
import app.claudroid.core.Logx
import app.claudroid.core.Paths
import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import app.claudroid.data.EngineMode
import app.claudroid.data.SettingsRepository
import app.claudroid.engine.openai.OpenAiProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates the backend lifecycle: provision the CLI, detect root / PRoot, and pick how
 * a turn should be launched. UI state lives here so the settings screen can render it.
 */
class EngineManager(
    private val ctx: Context,
    private val settingsRepo: SettingsRepository,
) {

    enum class Phase { Idle, Working, Ready, Failed }

    data class State(
        val phase: Phase = Phase.Idle,
        val stage: String = "",
        val detail: String = "",
        val fraction: Float = 0f,
        val installedVersion: String? = null,
        val latestVersion: String? = null,
        val arch: String? = null,
        val jailPath: String? = null,
        val rootMode: Boolean = false,
        val rootGranted: Boolean = false,
        val chrootAvailable: Boolean = false,
        val prootAvailable: Boolean = false,
        val error: String? = null,
        val selfTest: String? = null,
    ) {
        val ready: Boolean get() = phase == Phase.Ready && installedVersion != null

        /**
         * What the next turn will actually use, for the settings screen.
         *
         * The selected mode wins over the detected capabilities: once the user picks
         * PRoot, a granted root must not make the label claim root/chroot is in use.
         */
        fun engineLabel(mode: EngineMode): String = when {
            mode == EngineMode.PROOT && rootMode -> "PRoot（root 目录引擎，需重装）"
            mode == EngineMode.PROOT ->
                if (prootAvailable) "PRoot（免 root）" else "PRoot 组件缺失"
            mode == EngineMode.ROOT && !rootGranted -> "Root（未授权）"
            // AUTO + a root-mode installation: PRoot cannot read that jail, so don't
            // let the capability fallback below claim "PRoot（免 root）".
            rootMode && !rootGranted -> "root 目录中的引擎（当前无 root）"
            rootGranted && chrootAvailable -> "root + chroot"
            // Debian jail plus root but no chroot(1): PRoot via su is the preferred
            // fallback (a real container instead of a host-side loader launch).
            rootGranted && prootAvailable -> "PRoot（root）"
            rootGranted -> "root（加载器直启）"
            prootAvailable -> "PRoot（免 root）"
            else -> "应用目录直启"
        }
    }

    /** How the workspace terminal will launch one command. */
    data class ShellPlan(
        val installation: Installation,
        val useRoot: Boolean,
        val useChroot: Boolean,
        val useProot: Boolean,
        val prootViaRoot: Boolean = false,
    ) {
        val label: String
            get() = when {
                prootViaRoot -> "PRoot（root）"
                useProot -> "PRoot（免 root）"
                useRoot && useChroot -> "root + chroot"
                useRoot -> "root（加载器直启）"
                else -> "应用目录直启"
            }
    }

    @Volatile private var proxy: OpenAiProxy? = null
    @Volatile private var proxySignature: String = ""
    private val installLock = java.util.concurrent.atomic.AtomicBoolean(false)

    val rootExec = RootExec(ctx)
    val toolchain = Toolchain(ctx, rootExec)
    val engine = ClaudeEngine(ctx, rootExec)
    private val proot = ProotRunner(ctx)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun installation(): Installation? = Paths.installation(ctx)

    /** Called once from Application.onCreate. */
    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        val target = Registry.target()
        _state.update { it.copy(arch = target?.abi, prootAvailable = proot.available()) }
        Logx.i(
            "启动：ABI=${target?.abi ?: "不受支持"}, PRoot=${if (proot.available()) "可用" else "缺失"}",
            ctx,
        )
        if (target == null) {
            _state.update {
                it.copy(phase = Phase.Failed, error = "当前 CPU 架构不受支持（需要 arm64-v8a 或 x86_64）")
            }
            return@withContext
        }

        val settings = settingsRepo.flow.first()
        probeRoot()
        val existing = installation()
        _state.update {
            it.copy(
                installedVersion = existing?.version,
                jailPath = existing?.jailPath,
                rootMode = existing?.rootMode ?: false,
                arch = target.abi,
            )
        }

        val wantRoot = wantRoot(settings)
        val needsInstall = existing == null || existing.rootMode != wantRoot || !verifyInstalled(existing)
        if (needsInstall) {
            // install.json written by the Alpine-era app has no rootfs fields; treating
            // it as unhealthy above is exactly the migration trigger. Tell the user why
            // a ~1 GB download starts on first launch after the update.
            if (existing != null && existing.rootfsVersion.isBlank()) {
                Logx.i("检测到旧版 Alpine 引擎，将自动重装为 Debian 容器", ctx)
                _state.update {
                    it.copy(detail = "检测到旧版 Alpine 引擎，正在迁移到 Debian 容器（需重新下载）")
                }
            }
            install(force = existing != null)
        } else {
            // Existing installs never re-run configureJail, so the container's DNS config
            // is refreshed here on every start (IPv4-first resolv.conf + gai.conf).
            // Best-effort: a failure only logs, a stale resolver must not block startup.
            runCatching { toolchain.refreshNetworkConfig(existing) }
                .onFailure { Logx.e("刷新容器 DNS 配置失败：${it.message}", ctx) }
            refreshChroot(existing)
            _state.update { it.copy(phase = Phase.Ready, stage = "就绪", fraction = 1f) }
            if (settings.autoUpdate) checkForUpdate(quiet = true)
        }
    }

    /** Whether a turn should run through `su` rather than PRoot. */
    private fun wantRoot(settings: AppSettings): Boolean = when (settings.engineMode) {
        EngineMode.ROOT, EngineMode.AUTO -> _state.value.rootGranted
        EngineMode.PROOT -> false
    }

    /**
     * Same health definition as the installer. In particular a legacy Alpine install
     * (no `rootfsVersion`, no `/etc/debian_version`) is *unhealthy*, which is what drives
     * the automatic reinstall/migration on first launch after this update.
     */
    private fun verifyInstalled(existing: Installation?): Boolean {
        if (existing == null) return false
        return toolchain.verify(existing, existing.rootMode)
    }

    suspend fun install(force: Boolean = false) = withContext(Dispatchers.IO) {
        // Bootstrap and the settings button can both fire; a second 300 MB download is worse
        // than simply ignoring the second request.
        if (!installLock.compareAndSet(false, true)) return@withContext
        try {
            engine.cancel()
            _state.update { it.copy(phase = Phase.Working, error = null, stage = "准备中", fraction = 0f) }
            runCatching {
                val settings = settingsRepo.flow.first()
                if (!_state.value.rootGranted) probeRoot()
                val useRoot = when (settings.engineMode) {
                    EngineMode.ROOT -> {
                        check(_state.value.rootGranted) {
                            "已选择 Root 模式，但当前没有 root 授权。请先在 Magisk / KernelSU 中授权，或把引擎模式改为「Proot」或「自动」。"
                        }
                        true
                    }
                    EngineMode.PROOT -> false
                    EngineMode.AUTO -> _state.value.rootGranted
                }
                if (!useRoot && !proot.available()) {
                    Logx.e("未打包 PRoot 组件：免 root 模式只能退回应用目录直启，部分设备会拒绝执行", ctx)
                }
                val installation = toolchain.ensure(force = force, useRoot = useRoot) { p ->
                    _state.update { s -> s.copy(stage = p.stage, detail = p.detail, fraction = p.fraction) }
                }
                // The install swapped the jail into its official name; any su/busybox/
                // chroot probe from an earlier attempt was for a different path.
                rootExec.resetCache()
                // An in-place CLI upgrade reuses the existing rootfs, so configureJail did
                // not run; refresh the resolver config here as well (no-op when correct).
                runCatching { toolchain.refreshNetworkConfig(installation) }
                    .onFailure { Logx.e("刷新容器 DNS 配置失败：${it.message}", ctx) }
                refreshChroot(installation)
                _state.update {
                    it.copy(
                        phase = Phase.Ready,
                        installedVersion = installation.version,
                        jailPath = installation.jailPath,
                        rootMode = installation.rootMode,
                        prootAvailable = proot.available(),
                        stage = "就绪",
                        fraction = 1f,
                    )
                }
            }.onFailure { e ->
                Logx.e("安装失败：${e.message}", ctx)
                _state.update { it.copy(phase = Phase.Failed, error = e.message ?: "安装失败") }
            }
        } finally {
            installLock.set(false)
        }
    }

    suspend fun checkForUpdate(quiet: Boolean = false) = withContext(Dispatchers.IO) {
        runCatching {
            if (!quiet) _state.update { it.copy(stage = "检查更新", phase = Phase.Working) }
            val latest = Registry.latestCliVersion()
            _state.update { it.copy(latestVersion = latest) }
            if (!quiet) {
                _state.update {
                    it.copy(
                        phase = if (it.ready) Phase.Ready else it.phase,
                        stage = "就绪",
                        detail = if (it.installedVersion == latest) "已是最新 $latest" else "可升级到 $latest",
                    )
                }
            }
            latest
        }.onFailure { e ->
            if (!quiet) _state.update { it.copy(phase = Phase.Ready, error = "检查更新失败：${e.message}") }
        }
    }

    fun probeRoot() {
        val granted = runCatching { rootExec.probeRoot() }.getOrDefault(false)
        _state.update { it.copy(rootGranted = granted) }
        if (granted) {
            // A previous "no su / no busybox / no chroot" answer may have been cached
            // before the user approved the su dialog; re-probe with the new grant.
            rootExec.resetCache()
            // No DNS refresh here: callers reach this from the UI thread and a root-mode
            // rewrite is a blocking `su` round trip. Bootstrap and install already refresh
            // the config, which is the path that matters for a turn.
            installation()?.let { refreshChroot(it) }
        }
    }

    private fun refreshChroot(installation: Installation) {
        if (!_state.value.rootGranted) {
            _state.update { it.copy(chrootAvailable = false) }
            return
        }
        val ok = runCatching { rootExec.chrootReady(installation) }.getOrDefault(false)
        _state.update { it.copy(chrootAvailable = ok) }
        if (!ok) {
            Logx.e(
                "jail 里没有可用的 chroot(1)：将改用 " +
                    (if (proot.available()) "PRoot（root）" else "加载器直启") + " 方式",
                ctx,
            )
        }
    }

    /**
     * A human-readable reason why a turn cannot start, or null. Checked before every turn so
     * the UI shows the real problem instead of a child-process crash.
     */
    fun startIssue(settings: AppSettings): String? {
        val inst = installation() ?: return "尚未安装 Claude Code，请先在设置中安装引擎。"
        val root = _state.value.rootGranted
        val prootReady = proot.available()
        if (inst.rootMode && !root) {
            return "引擎安装在 root 目录中，但当前没有 root 授权。请重新授权，或改用 Proot 模式重装。"
        }
        if (inst.rootMode && settings.engineMode == EngineMode.PROOT) {
            return "引擎安装在 root 目录中，Proot 无法读取。请把模式改成「自动」，或选 Proot 后点「升级 / 重装」。"
        }
        if (settings.engineMode == EngineMode.ROOT && !root) {
            return "已选择 Root 模式，但当前没有 root 授权。请授权 root，或把引擎模式改为「Proot」/「自动」。"
        }
        if (!root && !prootReady) {
            return "PRoot 组件缺失，且没有 root。请安装包含 PRoot 的正式 APK（构建时会自动打包）。"
        }
        return null
    }

    /** Decide how to launch the CLI for the current settings. */
    fun plan(settings: AppSettings): ClaudeEngine.LaunchPlan? {
        val installation = installation() ?: return null
        val root = wantRoot(settings)
        val useChroot = root && _state.value.chrootAvailable
        val useProot = !root && proot.available() && !installation.rootMode
        return ClaudeEngine.LaunchPlan(
            installation = installation,
            useRoot = root,
            useChroot = useChroot,
            useProot = useProot,
            prootViaRoot = root && !useChroot && proot.available(),
            proxyBaseUrl = ensureProxy(settings),
        )
    }

    /**
     * Launch plan for a workspace terminal command.
     *
     * Deliberately side-effect free: unlike [plan] it never starts the OpenAI proxy and
     * never updates [state]. The terminal does not talk to the model, so paying for a
     * proxy restart just to run `ls` would be wrong.
     */
    fun shellPlan(settings: AppSettings): ShellPlan? {
        val installation = installation() ?: return null
        val root = wantRoot(settings)
        val useChroot = root && _state.value.chrootAvailable
        val useProot = !root && proot.available() && !installation.rootMode
        return ShellPlan(
            installation = installation,
            useRoot = root,
            useChroot = useChroot,
            useProot = useProot,
            prootViaRoot = root && !useChroot && proot.available(),
        )
    }

    /**
     * Starts (or restarts) the local OpenAI-compatible bridge when that flavour is selected.
     * Returns the loopback base URL Claude Code should use, or null for native Anthropic.
     */
    private fun ensureProxy(settings: AppSettings): String? {
        if (settings.apiFlavor != ApiFlavor.OPENAI) {
            proxy?.let { runCatching { it.stop() } }
            proxy = null
            proxySignature = ""
            return null
        }
        val signature = listOf(
            settings.baseUrl, settings.apiKey, settings.authStyle.name, settings.model,
            // The proxy resolves tier aliases from a settings snapshot taken at start, so
            // every field that changes that resolution must force a rebuild. Subagent /
            // classifier models are forwarded verbatim by that same snapshot.
            settings.activeTier, settings.opusModel, settings.fableModel,
            settings.sonnetModel, settings.haikuModel, settings.smallModel,
            settings.subagentModel, settings.autoModeModel,
        ).joinToString("|")
        val current = proxy
        if (current != null && proxySignature == signature) {
            return current.baseUrl
        }
        runCatching { current?.stop() }
        val created = OpenAiProxy(settings)
        val started = runCatching { created.start() }.isSuccess
        if (!started) {
            Logx.e("OpenAI 兼容代理启动失败", ctx)
            return null
        }
        proxy = created
        proxySignature = signature
        Logx.i("OpenAI 兼容代理 " + created.baseUrl + " -> " + OpenAiProxy.chatCompletionsUrl(settings), ctx)
        return created.baseUrl
    }

    /** Runs `claude --version` through the same launch path to prove the binary executes. */
    suspend fun selfTest(): String = withContext(Dispatchers.IO) {
        val settings = settingsRepo.flow.first()
        val issue = startIssue(settings)
        if (issue != null) return@withContext issue
        val plan = plan(settings) ?: return@withContext "尚未安装 CLI，无法自检"
        val inst = plan.installation
        val q = rootExec::q
        val guest = plan.useProot || plan.useChroot || plan.prootViaRoot
        val env = baseEnv(settings, plan.proxyBaseUrl, guest)

        val size = if (plan.useRoot) {
            rootExec.run("stat -c %s ${q(inst.binary().absolutePath)} 2>/dev/null || echo 0")
                .out.trim().substringBefore('\n')
        } else {
            inst.binary().length().toString()
        }
        val workspace = Paths.workspace(ctx)
        val home = Paths.home(ctx)
        val guestBinary = "/opt/claude/${inst.version}/claude"

        fun runRootScript(body: String): RootExec.Result {
            val f = File(ctx.filesDir, "engine/selftest.sh").apply { parentFile?.mkdirs() }
            f.writeText(body)
            return rootExec.execute(
                listOf(rootExec.suBinary() ?: "su", "-c", "sh ${f.absolutePath}"),
                120_000,
            )
        }

        val result: RootExec.Result = when {
            plan.prootViaRoot -> {
                val spec = ProotRunner(ctx).runSpec(inst, listOf(guestBinary, "--version"), env)
                runRootScript(TurnScripts.rootExec(spec.env, spec.argv, pidFile = null))
            }
            plan.useProot -> {
                val spec = ProotRunner(ctx).spec(inst, listOf("--version"), env)
                runProcess(spec.argv, spec.env, spec.cwd, 120_000)
            }
            plan.useRoot && plan.useChroot -> {
                // The self-test chroot binds workspace/home like a real turn; clear them
                // afterwards so they never outlive the process (see RootExec.deleteJail).
                rootExec.unmountJail(inst.jailPath)
                rootExec.chrootBegin()
                try {
                    runRootScript(
                        TurnScripts.chroot(
                            env = env,
                            jail = inst.jailPath,
                            workspace = workspace.absolutePath,
                            home = home.absolutePath,
                            chrootTool = rootExec.busybox() ?: "chroot",
                            binary = guestBinary,
                            args = listOf("--version"),
                            hasBash = inst.hasBash,
                            pidFile = null,
                        )
                    )
                } finally {
                    rootExec.chrootEnd()
                    rootExec.unmountJail(inst.jailPath)
                }
            }
            plan.useRoot -> runRootScript(
                TurnScripts.rootDirect(
                    env = env,
                    cwd = workspace.absolutePath,
                    binary = inst.loader().absolutePath,
                    args = ClaudeEngine.loaderOptions(inst.libraryPath()) +
                        inst.binary().absolutePath + "--version",
                    pidFile = null,
                )
            )
            else -> runProcess(
                ClaudeEngine.directArgv(
                    inst.loader(), inst.libraryPath(), inst.binary(), listOf("--version"),
                ),
                env,
                workspace,
                120_000,
            )
        }
        var text = result.combined.ifBlank { "（无输出，退出码 ${result.code}）" }

        // A crash inside the jail is worth one more data point: run the same binary with
        // the jail's loader on the host. It distinguishes "jail is broken" from "binary is
        // broken".
        if (plan.useRoot && plan.useChroot && text.contains("Bus error", ignoreCase = true)) {
            val direct = runRootScript(
                TurnScripts.rootDirect(
                    env = env,
                    cwd = workspace.absolutePath,
                    binary = inst.loader().absolutePath,
                    args = ClaudeEngine.loaderOptions(inst.libraryPath()) +
                        inst.binary().absolutePath + "--version",
                    pidFile = null,
                )
            )
            text += "\n— 直启模式（不经 chroot）—\n" +
                direct.combined.ifBlank { "（无输出，退出码 ${direct.code}）" }
        }
        text = "启动方式: ${plan.label}\nbinary: $size 字节\n$text"
        Logx.i("自检结果：$text", ctx)
        _state.update { it.copy(selfTest = text) }
        text
    }

    private fun runProcess(
        cmd: List<String>,
        env: Map<String, String>,
        cwd: File,
        timeoutMs: Long,
    ): RootExec.Result = runCatching {
        val pb = ProcessBuilder(cmd).directory(cwd)
        pb.environment().clear()
        pb.environment().putAll(env)
        val p = pb.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }
        val t2 = Thread { p.errorStream.bufferedReader().forEachLine { synchronized(err) { err.appendLine(it) } } }
        t1.start(); t2.start()
        val done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroyForcibly()
            t1.join(2000); t2.join(2000)
            return RootExec.Result(-1, out.toString(), "命令超时")
        }
        t1.join(3000); t2.join(3000)
        RootExec.Result(p.exitValue(), out.toString().trim(), err.toString().trim())
    }.getOrElse { RootExec.Result(-2, "", it.message ?: "执行失败") }

    data class StorageUsage(val jailBytes: Long, val workspaceBytes: Long, val sessionBytes: Long) {
        val totalBytes: Long get() = jailBytes + workspaceBytes + sessionBytes
    }

    suspend fun storageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        val inst = installation()
        val jail = when {
            inst == null -> 0L
            inst.rootMode -> rootExec.run(
                "du -sk ${rootExec.q(inst.jailPath)} 2>/dev/null | awk '{print \$1}'",
            ).out.trim().toLongOrNull()?.times(1024) ?: 0L
            else -> dirSize(inst.jail())
        }
        StorageUsage(
            jailBytes = jail,
            workspaceBytes = dirSize(Paths.workspace(ctx)),
            sessionBytes = dirSize(Paths.sessions(ctx)),
        )
    }

    /** Removes the installed CLI. Sessions and the workspace survive unless asked for. */
    suspend fun uninstall(deleteWorkspace: Boolean = false) = withContext(Dispatchers.IO) {
        engine.cancel()
        // cancel() only sets a flag and starts a reaper; the turn's `finally` (which
        // releases the jail and its chroot session) has not run yet. Deleting the tree
        // now would pull the rootfs out from under a live CLI, so wait for the turn.
        if (!awaitTurnEnd(UNINSTALL_TURN_WAIT_MS)) {
            val message = "仍有任务在运行，卸载已中止；请先停止任务，稍后再试。"
            Logx.e(message, ctx)
            _state.update { it.copy(error = message) }
            return@withContext
        }

        // Every jail candidate, including the swap leftovers of an interrupted install:
        // `install.json` may be gone (a half-written install), yet a ~1 GB tree remains.
        val appTrees = listOf(File(Paths.engine(ctx), "jail").absolutePath)
            .flatMap { base -> listOf(base, base + ".new", base + ".old") }
        val rootTrees = listOf(Paths.ROOT_JAIL, Paths.ROOT_JAIL_FALLBACK)
            .flatMap { base -> listOf(base, base + ".new", base + ".old") }

        val failed = mutableListOf<String>()
        runCatching {
            // App-private trees (PRoot mode) are ours to delete; root trees are not
            // readable/writable for this uid and must go through the root script.
            appTrees.forEach { path ->
                if (!rootExec.deleteTree(path)) {
                    failed += path
                    Logx.e("删除 jail 失败：$path", ctx)
                }
            }
            // Root jails are outside app storage: unreadable without root, but they still
            // cost ~1 GB and may be orphans of an install that never finished.
            if (rootExec.isGranted()) {
                // deleteJail unmounts the chroot's workspace/home binds first; a plain
                // rm -rf would otherwise follow them into the host filesystem.
                rootTrees.forEach { path ->
                    val r = runCatching { rootExec.deleteJail(path) }.getOrNull()
                    if (r == null || !r.ok) {
                        failed += path
                        Logx.e("删除 jail 失败：$path", ctx)
                    }
                }
            }
        }.onFailure {
            failed += "卸载异常"
            Logx.e("卸载失败：${it.message}", ctx)
        }

        if (failed.isEmpty()) {
            if (deleteWorkspace) {
                Paths.workspace(ctx).deleteRecursively()
                Paths.home(ctx).deleteRecursively()
                Paths.sessions(ctx).deleteRecursively()
            }
            // Verified CLI/rootfs archives are engine cache, not user data.
            Paths.staging(ctx).deleteRecursively()
            Paths.clearInstallation(ctx)
            // The jail is gone; a cached chroot/su answer has no valid target anymore.
            rootExec.resetCache()
            _state.update {
                it.copy(
                    phase = Phase.Idle,
                    installedVersion = null,
                    latestVersion = null,
                    jailPath = null,
                    rootMode = false,
                    chrootAvailable = false,
                    stage = "",
                    detail = "",
                    fraction = 0f,
                    selfTest = null,
                    error = null,
                )
            }
        } else {
            // Keep install.json: a tree may still be on disk, and reporting "未安装" would
            // hide it. The uninstall button stays available so the user can retry.
            val message = "卸载未完成：${failed.joinToString("、")} 仍在。" +
                "如有终端任务或 chroot 会话在运行，请先关闭它们后重试卸载。"
            Logx.e(message, ctx)
            _state.update { it.copy(error = message) }
        }
    }

    /**
     * Waits for the current turn to release [ClaudeEngine.isRunning]. Polling instead of
     * a lock because a turn can start from the chat screen at any moment and there is no
     * single owner to join.
     */
    private suspend fun awaitTurnEnd(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (engine.isRunning) {
            if (System.nanoTime() >= deadline) return false
            delay(POLL_MS)
        }
        return true
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Verification that root can really reach Magisk, used by the settings screen. */
    suspend fun rootProbeReport(): String = withContext(Dispatchers.IO) {
        if (!rootExec.isGranted()) return@withContext "未获得 root 权限"
        val id = rootExec.run("id -u").combined
        val magisk = rootExec.run("command -v magisk || ls /data/adb/magisk/magisk64 2>/dev/null || true").combined
        val busybox = rootExec.run("command -v busybox || ls -l /data/adb/magisk/busybox 2>/dev/null || true").combined
        val modules = rootExec.run("ls /data/adb/modules 2>/dev/null | head -30 || true").combined
        val jail = Paths.installation(ctx)?.jailPath ?: "（尚未安装）"
        buildString {
            appendLine("uid: $id")
            appendLine("magisk: ${magisk.ifBlank { "未找到" }}")
            appendLine("busybox: ${busybox.ifBlank { "未找到" }}")
            appendLine("jail: $jail")
            appendLine("已安装模块:")
            appendLine(modules.ifBlank { "（无）" })
        }.trim()
    }

    private fun baseEnv(s: AppSettings, proxyBaseUrl: String?, guestPath: Boolean): Map<String, String> {
        val env = linkedMapOf<String, String>()
        env["PATH"] = if (guestPath) {
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        } else {
            "/system/bin:/system/xbin"
        }
        env["ANTHROPIC_BASE_URL"] = proxyBaseUrl ?: s.normalizedBaseUrl()
        if (proxyBaseUrl != null) {
            env["ANTHROPIC_AUTH_TOKEN"] = "claudroid-local-proxy"
        } else {
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
        // Keep the self-test environment identical to a real turn: feature switches can
        // change CLI startup, so omitting them here would hide failures seen in chat.
        EngineEnv.applyFeatures(env, s)
        env["DISABLE_TELEMETRY"] = "1"
        env["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"
        env["DISABLE_AUTOUPDATER"] = "1"
        env["IS_SANDBOX"] = "1"
        return env
    }

    fun cancel() = engine.cancel()

    /** Clears a stop from the previous turn before a new one starts. */
    fun resetCancel() = engine.resetCancel()

    /**
     * True while a chat turn holds the engine. The settings screen uses this to keep the
     * uninstall / reinstall buttons disabled: deleting the jail under a live CLI is the
     * exact race [uninstall] refuses to lose.
     */
    val busy: Boolean get() = engine.isRunning

    companion object {
        /** How long uninstall waits for a cancelled turn to release the jail. */
        private const val UNINSTALL_TURN_WAIT_MS = 15_000L
        private const val POLL_MS = 100L
    }
}

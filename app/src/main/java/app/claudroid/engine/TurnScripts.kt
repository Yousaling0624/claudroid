package app.claudroid.engine

/**
 * Launcher scripts for root mode, one per turn.
 *
 * They live here instead of being inline in [ClaudeEngine] because the prompt is
 * interpolated into a shell script: a quoting bug would break every turn silently, so
 * the builders are unit-tested.
 *
 * Each script writes its own pid before `exec` (`echo $$ > …`). `chroot`, the glibc
 * loader and PRoot all exec in place (PRoot forks the tracee but keeps the pid of the
 * process it watches), so that pid still identifies the turn afterwards. Stop needs it
 * because the direct child we own is only `su`; killing that leaves the real CLI — and
 * the stdout pipe it holds — alive.
 */
internal object TurnScripts {

    /** Single-quote a value for /system/bin/sh. Mirrors `RootExec.q`. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Writes the shell pid that `exec` keeps, so the app can kill the CLI later. */
    fun pidLine(pidFile: String): String = "echo \$\$ > ${quote(pidFile)}"

    /**
     * Runs an arbitrary argv as root (PRoot path for root-without-chroot, and
     * provisioning). Unlike [rootDirect] this does not assume a loader: PRoot's argv
     * already contains everything it needs.
     *
     * PRoot is started with `--kill-on-exit`, so killing the pid we record tears down
     * the traced CLI as well.
     */
    fun rootExec(
        env: Map<String, String>,
        argv: List<String>,
        pidFile: String?,
        cwd: String? = null,
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        pidFile?.let { appendLine(pidLine(it)) }
        EnvKeys.safeEntries(env).forEach { (k, v) -> appendLine("export $k=${quote(v)}") }
        cwd?.let { appendLine("cd ${quote(it)} 2>/dev/null") }
        appendLine("exec " + argv.joinToString(" ") { quote(it) })
    }

    /**
     * Debian has no separate loader invocation: the CLI's PT_INTERP is the glibc loader
     * inside the jail (`/lib/ld-linux-*.so.2`) and glibc finds libs via the default
     * paths, so chroot can exec the binary directly.
     *
     * [env] is exported verbatim; the caller is responsible for not interpolating a
     * secret into an argument list (arguments are [args], never shell text).
     */
    fun rootDirect(
        env: Map<String, String>,
        cwd: String,
        binary: String,
        args: List<String>,
        pidFile: String?,
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        pidFile?.let { appendLine(pidLine(it)) }
        EnvKeys.safeEntries(env).forEach { (k, v) -> appendLine("export $k=${quote(v)}") }
        appendLine("cd ${quote(cwd)}")
        appendLine("exec ${quote(binary)} " + args.joinToString(" ") { quote(it) })
    }

    fun chroot(
        env: Map<String, String>,
        jail: String,
        workspace: String,
        home: String,
        chrootTool: String,
        binary: String,
        args: List<String>,
        hasBash: Boolean,
        pidFile: String?,
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("R=${quote(jail)}")
        pidFile?.let { appendLine(pidLine(it)) }
        EnvKeys.safeEntries(env).forEach { (k, v) -> appendLine("export $k=${quote(v)}") }
        appendLine("export HOME=/root")
        appendLine("export TMPDIR=/tmp")
        appendLine("export SHELL=" + quote(if (hasBash) "/bin/bash" else "/bin/sh"))
        appendMounts(this, workspace, home)
        appendLine("cd \"\$R/work\" 2>/dev/null || cd \"\$R\"")
        appendLine(
            "exec ${quote(chrootTool)} chroot \"\$R\" ${quote(binary)} " +
                args.joinToString(" ") { quote(it) }
        )
    }

    /**
     * Chroot wrapper for the workspace terminal: same mounts as [chroot], but the
     * program is the guest's `/bin/sh -c <guestScript>` instead of the CLI.
     *
     * The status file lives under the home bind (`/root/...`), so it is visible on
     * the host without exposing control files in the workspace.
     */
    fun chrootShell(
        env: Map<String, String>,
        jail: String,
        workspace: String,
        home: String,
        chrootTool: String,
        guestScript: String,
        pidFile: String?,
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("R=${quote(jail)}")
        pidFile?.let { appendLine(pidLine(it)) }
        EnvKeys.safeEntries(env).forEach { (k, v) -> appendLine("export $k=${quote(v)}") }
        appendMounts(this, workspace, home)
        appendLine("exec ${quote(chrootTool)} chroot \"\$R\" /bin/sh -c ${quote(guestScript)}")
    }

    /** Shared jail mount setup; order is stable so the generated scripts stay diffable. */
    private fun appendMounts(out: StringBuilder, workspace: String, home: String) {
        listOf("work", "proc", "dev", "sys", "system", "data", "sdcard", "tmp", "root").forEach {
            out.appendLine("mkdir -p \"\$R/$it\" 2>/dev/null")
        }
        out.appendLine("bind_mount() { grep -qs \" \$2 \" /proc/mounts || mount --bind \"\$1\" \"\$2\" 2>/dev/null; }")
        listOf(
            "/proc" to "proc", "/dev" to "dev", "/sys" to "sys", "/system" to "system",
            "/data" to "data", "/storage/emulated/0" to "sdcard",
        ).forEach { (src, dst) -> out.appendLine("bind_mount $src \"\$R/$dst\"") }
        out.appendLine("bind_mount ${quote(workspace)} \"\$R/work\"")
        out.appendLine("bind_mount ${quote(home)} \"\$R/root\"")
    }
}

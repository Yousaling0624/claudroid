package app.claudroid.engine

/**
 * Launcher scripts for root mode, one per turn.
 *
 * They live here instead of being inline in [ClaudeEngine] because the prompt is
 * interpolated into a shell script: a quoting bug would break every turn silently, so
 * the builders are unit-tested.
 *
 * Each script writes its own pid before `exec` (`echo $$ > …`). Both `chroot` and the
 * musl loader exec in place, so that pid still names the CLI afterwards. Stop needs it
 * because the direct child we own is only `su`; killing that leaves the real CLI — and
 * the stdout pipe it holds — alive.
 */
internal object TurnScripts {

    /** Single-quote a value for /system/bin/sh. Mirrors `RootExec.q`. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Writes the shell pid that `exec` keeps, so the app can kill the CLI later. */
    fun pidLine(pidFile: String): String = "echo \$\$ > ${quote(pidFile)}"

    fun rootDirect(
        env: Map<String, String>,
        cwd: String,
        loader: String,
        binary: String,
        args: List<String>,
        pidFile: String?,
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        pidFile?.let { appendLine(pidLine(it)) }
        env.forEach { (k, v) -> appendLine("export $k=${quote(v)}") }
        appendLine("cd ${quote(cwd)}")
        appendLine("exec ${quote(loader)} ${quote(binary)} " + args.joinToString(" ") { quote(it) })
    }

    fun chroot(
        env: Map<String, String>,
        jail: String,
        workspace: String,
        home: String,
        chrootTool: String,
        loaderName: String,
        binary: String,
        args: List<String>,
        hasBash: Boolean,
        pidFile: String?,
    ): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("R=${quote(jail)}")
        pidFile?.let { appendLine(pidLine(it)) }
        env.forEach { (k, v) -> appendLine("export $k=${quote(v)}") }
        appendLine("export HOME=/root")
        appendLine("export TMPDIR=/tmp")
        appendLine("export SHELL=" + quote(if (hasBash) "/bin/bash" else "/bin/sh"))
        appendMounts(this, workspace, home)
        appendLine("cd \"\$R/work\" 2>/dev/null || cd \"\$R\"")
        appendLine(
            "exec ${quote(chrootTool)} chroot \"\$R\" ${quote(loaderName)} ${quote(binary)} " +
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
        env.forEach { (k, v) -> appendLine("export $k=${quote(v)}") }
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

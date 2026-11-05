package app.claudroid.engine

/**
 * Pure helpers for the one destructive operation we cannot afford to get wrong: deleting
 * a jail tree.
 *
 * A chroot session bind-mounts host paths (`/data`, `/system`, `/sdcard`, `/proc`, …)
 * into the jail. Those mounts belong to the root shell's mount namespace and survive the
 * chroot process, so a later `rm -rf <jail>` walks through every mountpoint and deletes
 * the *host* directory behind it. That is how a reinstall wiped `/data` on a real device.
 *
 * The matching rules therefore live here as plain functions with unit tests: what counts
 * as "inside the jail", in which order mounts must be removed, and which paths are even
 * allowed to be deleted.
 */
internal object MountTable {

    /** Exactly the jail and its two swap siblings; anything else is refused. */
    private val SWAP_SUFFIXES = listOf(".new", ".old")

    /**
     * Parses one `/proc/mounts` line's mountpoint field.
     *
     * The kernel octal-escapes whitespace and backslashes in mountpoints (`\040` for a
     * space), so a raw text comparison would miss a jail whose path contains one.
     */
    fun decodeField(field: String): String {
        if (!field.contains('\\')) return field
        val out = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val c = field[i]
            val octal = c == '\\' && i + 3 < field.length &&
                field[i + 1] in '0'..'7' && field[i + 2] in '0'..'7' && field[i + 3] in '0'..'7'
            if (octal) {
                val value = (field[i + 1] - '0') * 64 + (field[i + 2] - '0') * 8 + (field[i + 3] - '0')
                out.append(value.toChar())
                i += 4
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** All mountpoints in a `/proc/mounts` dump, decoded. Malformed lines are skipped. */
    fun mountPoints(mountsText: String): List<String> = mountsText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val fields = line.split(' ', '\t').filter { it.isNotEmpty() }
            // "src mountpoint fstype opts dump pass"; anything shorter is not a mount.
            if (fields.size < 3) return@mapNotNull null
            // A real mountpoint is always absolute. Without this check a stray log line
            // ("not a mount line") parses as mountpoint "a" and could be umounted.
            val mountPoint = fields[1]
            if (!mountPoint.startsWith("/")) null else decodeField(mountPoint)
        }
        .toList()

    /**
     * Mountpoints at or below [jailPath], deepest first.
     *
     * Deepest-first matters: a mount nested inside another one cannot be removed while
     * its parent is still mounted. The prefix test is `jail + "/"`, so a sibling such as
     * `/data/adb/claudroid2` is never matched.
     */
    fun mountsUnder(mountsText: String, jailPath: String): List<String> {
        val jail = normalize(jailPath)
        if (jail.isEmpty()) return emptyList()
        return mountPoints(mountsText)
            .filter { it == jail || it.startsWith("$jail/") }
            .distinct()
            .sortedWith(compareByDescending<String> { it.count { c -> c == '/' } }.thenByDescending { it.length })
    }

    /**
     * Whitelist for [RootExec.deleteJail]. [exact] may also carry the `.new` / `.old`
     * swap siblings; [prefix] is the app-private engine directory, whose trees are only
     * reachable by this uid.
     */
    fun isAllowedJail(path: String, exact: Collection<String>, prefix: String): Boolean {
        val target = normalize(path)
        if (target.isEmpty() || target.contains('\u0000')) return false
        if (target.split('/').any { it == ".." }) return false
        val roots = exact.map { normalize(it) }.filter { it.isNotEmpty() }
        if (roots.any { target == it || SWAP_SUFFIXES.any { s -> target == it + s } }) return true
        val base = normalize(prefix)
        return base.isNotEmpty() && target.startsWith("$base/")
    }

    // ------------------------------------------------------------ root-side scripts

    /**
     * `/proc/mounts` escapes whitespace and backslashes as octal (`\040`, `\011`, `\012`,
     * `\134`). Matching a raw table line therefore needs the escaped form of the jail
     * path, not the readable one.
     */
    fun escapeMountPath(path: String): String = buildString(path.length) {
        for (c in path) {
            when (c) {
                '\\' -> append("\\134")
                ' ' -> append("\\040")
                '\t' -> append("\\011")
                '\n' -> append("\\012")
                else -> append(c)
            }
        }
    }

    /**
     * Script that detaches every mount at or below [jailPath].
     *
     * Must run *inside* one root shell: the binds a chroot session leaves behind belong
     * to the root shell's mount namespace, and `/proc/mounts` is per namespace, so an
     * app-side read can legitimately miss them and an app-uid `umount` gets EPERM.
     *
     * Ordering: the longest matching mountpoint is removed first, re-reading the table
     * each round. A mount nested under another is always longer than its parent, so this
     * is children-before-parents without a sort that may be missing from busybox. If the
     * longest path does not disappear after an `umount -l`, the loop stops instead of
     * spinning; the caller's re-check then refuses the delete.
     */
    fun unmountScript(jailPath: String, umountBinary: String): String = buildString {
        append(collectAndUnmount(jailPath, umountBinary))
        appendLine("echo \"JAIL_MOUNTS_LEFT=\$(count_mounts)\"")
        // Names, not just a count: a leftover mount is the one fact that explains why a
        // later delete was refused, and the app log is the only place to see it.
        appendLine("list_mounts")
        appendLine("echo UNMOUNT_DONE")
    }

    /**
     * Script that unmounts, then deletes [jailPath] and asserts it is gone.
     *
     * The mount re-check between unmount and `rm -rf` is deliberate: if a bind survived,
     * `rm -rf` would walk through the mountpoint into the *host* directory behind it.
     * That is what wiped `/data` on a real device, so a leftover mount aborts the delete
     * with exit 4 instead of proceeding.
     */
    fun deleteScript(jailPath: String, umountBinary: String): String = buildString {
        append(collectAndUnmount(jailPath, umountBinary))
        // `umount -l` detaches lazily; give the kernel a moment before trusting the table.
        appendLine("sleep 1")
        appendLine("LEFT=\$(count_mounts)")
        appendLine("echo \"JAIL_MOUNTS_LEFT=\$LEFT\"")
        appendLine("if [ \"\$LEFT\" -gt 0 ]; then")
        appendLine("  echo \"MOUNTS_LEFT=\$LEFT\"")
        appendLine("  list_mounts")
        appendLine("  exit 4")
        appendLine("fi")
        appendLine("rm -rf -- \"\$T\"")
        appendLine("if [ -e \"\$T\" ]; then")
        appendLine("  echo STILL_THERE")
        appendLine("  exit 3")
        appendLine("fi")
        appendLine("echo GONE")
    }

    /**
     * Script that removes every child of [versionsPath] except [keepPath] (the CLI version
     * just installed), skipping any child that still has a mount at or below it.
     *
     * `opt/claude/<version>` is normally mount-free, but it lives inside the jail, and a
     * chroot session could have left a bind there. A plain `rm -rf` would then descend
     * through that mountpoint into the *host* directory — the same failure mode as
     * [deleteScript]. Checking inside this one root shell is required for the same reason:
     * `/proc/mounts` is per mount namespace, so an app-side read can miss those binds.
     *
     * Reuses the field split and octal decoding of the other scripts (`${l#* }` / `%% *`,
     * `printf '%b'`) instead of a second parser; matching is `path` or `path/…`, never a
     * bare prefix, so `2.1.0` cannot shadow `2.1.00`. Every skip is named
     * (`SKIP_MOUNTED=<path>`) because the app log is the only place to see why a stale
     * version survived.
     */
    fun pruneScript(versionsPath: String, keepPath: String): String {
        val versions = normalize(versionsPath)
        val keep = normalize(keepPath)
        return buildString {
            appendLine("set +e")
            appendLine("P=${TurnScripts.quote(versions)}")
            appendLine("K=${TurnScripts.quote(keep)}")
            // An unreadable table would make every directory look mount-free; refuse the
            // whole prune instead (exit 6 = no mount table, distinct from deleteJail's 4).
            appendLine("if [ ! -r /proc/mounts ]; then")
            appendLine("  echo NO_MOUNT_TABLE")
            appendLine("  exit 6")
            appendLine("fi")
            // $1 is a real path, so the table's escaped fields are decoded before comparing.
            appendLine("mounted_under() {")
            appendLine("  while IFS= read -r pu_l; do")
            appendLine("    pu_mp=\${pu_l#* }")
            appendLine("    pu_mp=\${pu_mp%% *}")
            appendLine("    case \"\$pu_mp\" in /*) ;; *) continue ;; esac")
            appendLine("    pu_p=\$(printf '%b' \"\$pu_mp\")")
            appendLine("    case \"\$pu_p\" in \"\$1\"|\"\$1\"/*) return 0 ;; esac")
            appendLine("  done < /proc/mounts")
            appendLine("  return 1")
            appendLine("}")
            appendLine("for d in \"\$P\"/*; do")
            // No children: the glob stays literal, and `-e` rejects it.
            appendLine("  [ -e \"\$d\" ] || continue")
            appendLine("  [ \"\$d\" = \"\$K\" ] && continue")
            appendLine("  if mounted_under \"\$d\"; then")
            appendLine("    echo \"SKIP_MOUNTED=\$d\"")
            appendLine("    continue")
            appendLine("  fi")
            appendLine("  rm -rf -- \"\$d\"")
            appendLine("done")
            appendLine("echo PRUNE_DONE")
        }
    }

    /** Shared read → match → longest-first `umount -l` half of both scripts. */
    private fun collectAndUnmount(jailPath: String, umountBinary: String): String {
        val target = normalize(jailPath)
        val escaped = escapeMountPath(target)
        return buildString {
            appendLine("set +e")
            appendLine("T=${TurnScripts.quote(target)}")
            appendLine("E=${TurnScripts.quote(escaped)}")
            appendLine("UMOUNT=${TurnScripts.quote(umountBinary)}")
            // Matches "$E" itself or "$E"/… — never a bare prefix test, or a sibling
            // such as /data/adb/claudroid2 would be unmounted too.
            appendLine("count_mounts() {")
            appendLine("  cm_n=0")
            appendLine("  while IFS= read -r cm_l; do")
            appendLine("    cm_mp=\${cm_l#* }")
            appendLine("    cm_mp=\${cm_mp%% *}")
            appendLine("    case \"\$cm_mp\" in \"\$E\"|\"\$E\"/*) cm_n=\$((cm_n + 1)) ;; esac")
            appendLine("  done < /proc/mounts")
            appendLine("  echo \$cm_n")
            appendLine("}")
            // One `MOUNT_LEFT=<path>` line per remaining mountpoint, so the app log can
            // name exactly which bind blocked the delete.
            appendLine("list_mounts() {")
            appendLine("  while IFS= read -r lm_l; do")
            appendLine("    lm_mp=\${lm_l#* }")
            appendLine("    lm_mp=\${lm_mp%% *}")
            appendLine("    case \"\$lm_mp\" in \"\$E\"|\"\$E\"/*) echo \"MOUNT_LEFT=\$lm_mp\" ;; esac")
            appendLine("  done < /proc/mounts")
            appendLine("}")
            appendLine("longest_mount() {")
            appendLine("  lm_b=''")
            appendLine("  while IFS= read -r lm_l; do")
            appendLine("    lm_mp=\${lm_l#* }")
            appendLine("    lm_mp=\${lm_mp%% *}")
            appendLine("    case \"\$lm_mp\" in")
            appendLine("      \"\$E\"|\"\$E\"/*)")
            appendLine("        if [ \${#lm_mp} -gt \${#lm_b} ]; then lm_b=\$lm_mp; fi")
            appendLine("        ;;")
            appendLine("    esac")
            appendLine("  done < /proc/mounts")
            appendLine("  echo \"\$lm_b\"")
            appendLine("}")
            appendLine("echo \"JAIL_MOUNTS=\$(count_mounts)\"")
            appendLine("PREV=''")
            appendLine("while :; do")
            appendLine("  BEST=\$(longest_mount)")
            appendLine("  [ -n \"\$BEST\" ] || break")
            // Stuck mount: the same path survived an `umount -l`; stop and let the
            // caller's re-check refuse the delete.
            appendLine("  [ \"\$BEST\" = \"\$PREV\" ] && break")
            appendLine("  PREV=\$BEST")
            // The table stores octal escapes; umount needs the real path.
            appendLine("  P=\$(printf '%b' \"\$BEST\")")
            appendLine("  \"\$UMOUNT\" -l \"\$P\" 2>/dev/null || true")
            appendLine("done")
        }
    }

    private fun normalize(path: String): String = path.trim().trimEnd('/')
}

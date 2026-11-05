package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules behind the guarded jail deletion.
 *
 * These are the only thing standing between a reinstall and another `rm -rf` that walks
 * through a leftover chroot bind into the host filesystem, so every branch is pinned here
 * rather than only on a device.
 */
class MountTableTest {

    private val jail = "/data/adb/claudroid"
    private val exact = listOf("/data/adb/claudroid", "/data/local/tmp/claudroid")
    private val prefix = "/data/user/0/app/files/engine"

    private fun mounts(vararg lines: String): String = lines.joinToString("\n")

    @Test
    fun `decodes octal escaped mountpoints`() {
        assertEquals("/data/adb/my jail", MountTable.decodeField("/data/adb/my\\040jail"))
        assertEquals("/a\tb", MountTable.decodeField("/a\\011b"))
        assertEquals("/a\\b", MountTable.decodeField("/a\\134b"))
        // Plain paths are returned untouched.
        assertEquals("/data/adb/claudroid", MountTable.decodeField("/data/adb/claudroid"))
    }

    @Test
    fun `parses mountpoints and skips malformed lines`() {
        val text = mounts(
            "rootfs / rootfs rw 0 0",
            "",
            "not a mount line",
            // Four fields, but the second one is not absolute: a log line, not a mount.
            "tmpfs relative/path tmpfs rw 0 0",
            "/dev/block/x /data ext4 rw 0 0",
        )
        assertEquals(listOf("/", "/data"), MountTable.mountPoints(text))
    }

    @Test
    fun `no mounts below a jail yields an empty list`() {
        val text = mounts(
            "/dev/block/by-name/userdata /data ext4 rw,seclabel 0 0",
            "/dev/block/by-name/system /system ext4 ro,seclabel 0 0",
        )
        assertEquals(emptyList<String>(), MountTable.mountsUnder(text, jail))
    }

    @Test
    fun `mounts below a jail are returned deepest first`() {
        val text = mounts(
            "tmpfs $jail/work tmpfs rw 0 0",
            "tmpfs $jail tmpfs rw 0 0",
            "tmpfs $jail/work/nested/deeper tmpfs rw 0 0",
            "tmpfs $jail/root tmpfs rw 0 0",
            "tmpfs /data tmpfs rw 0 0",
        )
        assertEquals(
            listOf("$jail/work/nested/deeper", "$jail/work", "$jail/root", jail),
            MountTable.mountsUnder(text, jail),
        )
    }

    @Test
    fun `the jail mountpoint itself and a nested workspace bind are both matched`() {
        val text = mounts(
            "tmpfs $jail tmpfs rw 0 0",
            // Bind line is "<source> <mountpoint> …": the host workspace is the source,
            // the mountpoint inside the jail is what must be swept before a delete.
            "/data/user/0/app/files/workspace $jail/work tmpfs rw 0 0",
        )
        assertEquals(listOf("$jail/work", jail), MountTable.mountsUnder(text, jail))
    }

    @Test
    fun `a sibling whose name merely starts with the jail is not matched`() {
        val text = mounts(
            "tmpfs /data/adb/claudroid2 tmpfs rw 0 0",
            "tmpfs /data/adb/claudroid2/work tmpfs rw 0 0",
            "tmpfs /data/adb/claudroid-old tmpfs rw 0 0",
        )
        assertEquals(emptyList<String>(), MountTable.mountsUnder(text, jail))
    }

    @Test
    fun `escaped spaces in a jail path still match`() {
        val text = "tmpfs /data/adb/my\\040jail/work tmpfs rw 0 0"
        assertEquals(
            listOf("/data/adb/my jail/work"),
            MountTable.mountsUnder(text, "/data/adb/my jail"),
        )
    }

    @Test
    fun `whitelist accepts the root jails and their swap siblings`() {
        for (base in exact) {
            assertTrue(base, MountTable.isAllowedJail(base, exact, prefix))
            assertTrue("$base.new", MountTable.isAllowedJail("$base.new", exact, prefix))
            assertTrue("$base.old", MountTable.isAllowedJail("$base.old", exact, prefix))
            // Trailing slash from a File.absolutePath must not change the verdict.
            assertTrue("$base/", MountTable.isAllowedJail("$base/", exact, prefix))
        }
    }

    @Test
    fun `whitelist accepts app-private engine trees`() {
        assertTrue(MountTable.isAllowedJail("$prefix/jail", exact, prefix))
        assertTrue(MountTable.isAllowedJail("$prefix/jail.new", exact, prefix))
        assertTrue(MountTable.isAllowedJail("$prefix/jail.old", exact, prefix))
    }

    @Test
    fun `whitelist refuses hosts paths, empties and traversal`() {
        for (bad in listOf(
            "/data",
            "/data/adb",
            "/data/adb/claudroid2",
            "/data/local/tmp",
            "/system",
            "/storage/emulated/0",
            "",
            "   ",
            "/data/adb/claudroid/../../..",
            "$prefix/../jail",
            "$prefix2/jail",
        )) {
            assertFalse(bad, MountTable.isAllowedJail(bad, exact, prefix))
        }
    }

    @Test
    fun `whitelist refuses a path with a NUL byte`() {
        assertFalse(MountTable.isAllowedJail("/data/adb/claudroid\u0000/x", exact, prefix))
    }

    @Test
    fun `mount path escaping round-trips the kernel octal form`() {
        assertEquals("/data/adb/claudroid", MountTable.escapeMountPath(jail))
        assertEquals("/data/adb/my\\040jail", MountTable.escapeMountPath("/data/adb/my jail"))
        assertEquals("/a\\011b", MountTable.escapeMountPath("/a\tb"))
        assertEquals("/a\\134b", MountTable.escapeMountPath("/a\\b"))
    }

    @Test
    fun `delete script quotes the target and only matches it and its children`() {
        val script = MountTable.deleteScript(jail, "/system/bin/umount")
        // The target is a single quoted word, so a path with a space cannot re-split.
        assertTrue(script.contains("T='$jail'"))
        // Exact match plus "$E"/* — a bare prefix would also hit /data/adb/claudroid2.
        assertTrue(script.contains("\"\$E\"|\"\$E\"/*"))
        assertFalse(script.contains("case \"\$mp\" in \"\$E\"*"))
        // The escaped form is what /proc/mounts stores.
        assertTrue(script.contains("E='$jail'"))
    }

    @Test
    fun `delete script unmounts longest path first and re-reads the table`() {
        val script = MountTable.deleteScript(jail, "/system/bin/umount")
        val longest = script.indexOf("longest_mount() {")
        val pick = script.indexOf("if [ \${#lm_mp} -gt \${#lm_b} ]")
        val umountAt = script.indexOf("\"\$UMOUNT\" -l \"\$P\"")
        assertTrue("no longest-path selection:\n$script", longest >= 0 && pick > longest)
        // The loop must re-read the table between removals, not use a stale list.
        assertTrue("umount must come after a fresh longest_mount:\n$script", umountAt > pick)
        assertTrue(script.contains("BEST=\$(longest_mount)"))
        assertTrue(script.contains("done < /proc/mounts"))
    }

    @Test
    fun `delete script refuses to rm while a mount remains`() {
        val script = MountTable.deleteScript(jail, "/system/bin/umount")
        val leftAt = script.indexOf("LEFT=\$(count_mounts)")
        // Anchored on the guard's own echo: "MOUNTS_LEFT=" also occurs inside the
        // "JAIL_MOUNTS_LEFT=" report line, which runs before the umount loop.
        val guardAt = script.indexOf("echo \"MOUNTS_LEFT=")
        val rmAt = script.indexOf("rm -rf -- \"\$T\"")
        assertTrue("missing post-unmount re-check:\n$script", leftAt >= 0)
        // umount -l detaches lazily; the script must settle before trusting the table.
        assertTrue("no settle delay before the re-check:\n$script", script.contains("sleep 1"))
        assertTrue("guard must run before rm -rf:\n$script", guardAt > leftAt && guardAt < rmAt)
        assertTrue("rm must use -- before the target:\n$script", rmAt >= 0)
        assertTrue("guard must abort with exit 4:\n$script", script.contains("exit 4"))
        // Deletion only after the guard; existence assertion comes last.
        assertTrue(script.indexOf("echo STILL_THERE") > rmAt)
        assertTrue(script.indexOf("echo GONE") > rmAt)
    }

    @Test
    fun `delete script names the mounts that blocked the delete`() {
        val script = MountTable.deleteScript(jail, "/system/bin/umount")
        // The coordinator asked for MOUNTS_LEFT <paths>: a bare count in the log is not
        // enough to tell which bind survived and where it pointed.
        assertTrue(script.contains("list_mounts() {"))
        assertTrue(script.contains("echo \"MOUNT_LEFT=\$lm_mp\""))
        val guardAt = script.indexOf("echo \"MOUNTS_LEFT=")
        assertTrue(script.indexOf("  list_mounts") > guardAt)
        assertTrue(script.indexOf("  exit 4") > script.indexOf("  list_mounts"))
    }

    @Test
    fun `delete script reports the mount count it saw inside the root shell`() {
        val script = MountTable.deleteScript(jail, "/system/bin/umount")
        assertTrue(script.contains("echo \"JAIL_MOUNTS=\$(count_mounts)\""))
        assertTrue(script.contains("echo \"JAIL_MOUNTS_LEFT=\$LEFT\""))
    }

    @Test
    fun `unmount script reports what is left instead of claiming success`() {
        val script = MountTable.unmountScript(jail, "umount")
        assertTrue(script.contains("\"\$UMOUNT\" -l \"\$P\""))
        assertTrue(script.contains("echo \"JAIL_MOUNTS_LEFT=\$(count_mounts)\""))
        assertTrue(script.contains("echo UNMOUNT_DONE"))
        // No deletion in the sweep-only script.
        assertFalse(script.contains("rm -rf"))
    }

    @Test
    fun `scripts tolerate a missing umount binary and a stuck mount`() {
        val script = MountTable.deleteScript(jail, "umount")
        assertTrue(script.contains("2>/dev/null || true"))
        // A mount that survives an umount -l must stop the loop, not spin forever.
        assertTrue(script.contains("[ \"\$BEST\" = \"\$PREV\" ] && break"))
        assertTrue(script.contains("set +e"))
    }

    private val prefix2 = "/data/user/0/app/files/engine2"

    /**
     * The CLI version directories live *inside* the jail, so they are normally mount-free
     * — but a chroot session could leave a bind there, and `rm -rf` through that
     * mountpoint deletes the host directory behind it. The old inline script pruned
     * versions with a bare `rm -rf`, the only recursive delete in the repo that skipped
     * this guard. (Kotlin block comments nest, hence the wording above: a literal
     * glob star-slash in here would open a second comment.)
     */
    @Test
    fun `prune script checks the mount table before every recursive delete`() {
        val versions = "/data/adb/claudroid/opt/claude"
        val script = MountTable.pruneScript(versions, "$versions/2.1.0")
        // The check must precede the delete for each candidate directory.
        val checkAt = script.indexOf("if mounted_under \"\$d\"; then")
        val skipAt = script.indexOf("echo \"SKIP_MOUNTED=\$d\"")
        val deleteAt = script.indexOf("rm -rf -- \"\$d\"")
        assertTrue(checkAt >= 0)
        assertTrue(skipAt > checkAt)
        assertTrue(deleteAt > skipAt)
        // The version just installed is kept, and the glob is never dereferenced blindly.
        assertTrue(script.contains("P=${TurnScripts.quote(versions)}"))
        assertTrue(script.contains("K=${TurnScripts.quote("$versions/2.1.0")}"))
        assertTrue(script.contains("[ \"\$d\" = \"\$K\" ] && continue"))
        assertTrue(script.contains("[ -e \"\$d\" ] || continue"))
        assertTrue(script.contains("echo PRUNE_DONE"))
    }

    @Test
    fun `prune script matches mounts with the same field split and octal decoding`() {
        val versions = "/data/adb/claudroid/opt/claude"
        val script = MountTable.pruneScript(versions, "$versions/2.1.0")
        // Same parse as count_mounts/list_mounts: second whitespace field, then %b.
        assertTrue(script.contains("pu_mp=\${pu_l#* }"))
        assertTrue(script.contains("pu_mp=\${pu_mp%% *}"))
        assertTrue(script.contains("pu_p=\$(printf '%b' \"\$pu_mp\")"))
        // Only absolute mountpoints count, and a sibling such as /opt/claude2 must not
        // match: the prefix test is the path itself or path/…, never a bare startsWith.
        assertTrue(script.contains("case \"\$pu_mp\" in /*) ;; *) continue ;; esac"))
        assertTrue(script.contains("case \"\$pu_p\" in \"\$1\"|\"\$1\"/*) return 0 ;; esac"))
        // A stuck/unreadable table must not turn into a silent success.
        assertTrue(script.contains("set +e"))
        assertTrue(script.contains("done < /proc/mounts"))
        // No table at all: refuse everything rather than treating every dir as unmounted.
        val tableGuardAt = script.indexOf("if [ ! -r /proc/mounts ]; then")
        val loopAt = script.indexOf("for d in")
        assertTrue(tableGuardAt >= 0)
        assertTrue(loopAt > tableGuardAt)
        assertTrue(script.contains("echo NO_MOUNT_TABLE"))
        assertTrue(script.contains("exit 6"))
    }

    @Test
    fun `prune script keeps a version whose name is a prefix of the new one`() {
        // "2.1.0" and "2.1.00" are different directories; comparing by prefix would
        // delete the wrong one, so the comparison is on the full path.
        val versions = "/data/adb/claudroid/opt/claude"
        val script = MountTable.pruneScript(versions, "$versions/2.1.0")
        assertFalse(script.contains("[ \"\$d\" = ${TurnScripts.quote(versions)}*"))
        assertTrue(script.contains("[ \"\$d\" = \"\$K\" ] && continue"))
    }
}

package app.claudroid.engine

import android.content.Context
import app.claudroid.core.Installation
import app.claudroid.core.Paths
import java.io.File

/**
 * Runs the same jail through Termux's PRoot when the device has no root.
 *
 * PRoot itself cannot live in filesDir: Android 10+ refuses exec() there for
 * targetSdk >= 29, and an app cannot chmod filesDir to make an exception. The
 * binary, its loader and shared libraries are therefore shipped as `lib*.so`
 * in jniLibs, so the package manager extracts them into nativeLibraryDir.
 * `tools/fetch-proot.py` stages them at build time.
 *
 * The guest rootfs (musl loader + busybox + bash + the official CLI) stays in
 * filesDir. targetSdk 28 in app/build.gradle.kts keeps exec() allowed there —
 * the same trade-off Termux and the DSH-style Android harnesses make.
 */
class ProotRunner(private val ctx: Context) {

    data class Bundle(val proot: File, val loader: File, val libDir: File) {
        val available: Boolean
            get() = proot.isFile && proot.canExecute() && loader.isFile && loader.length() > 10_000
    }

    fun bundle(): Bundle {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        return Bundle(
            proot = File(dir, "libproot.so"),
            loader = File(dir, "libproot-loader.so"),
            libDir = dir,
        )
    }

    fun available(): Boolean = bundle().available

    /**
     * Everything PRoot needs for one turn. Returned command is fully quoted-free (argv).
     *
     * When [pidFile] is set, the CLI is launched through a tiny wrapper in the jail's
     * /tmp that records its pid first. Stop uses that pid to kill the actual CLI;
     * killing PRoot alone is not guaranteed to take the CLI down with it.
     */
    fun spec(
        installation: Installation,
        args: List<String>,
        baseEnv: Map<String, String>,
        pidFile: String? = null,
    ): Spec {
        val bundle = bundle()
        check(bundle.available) {
            "proot 组件缺失：请用 tools/fetch-proot.py 重新构建 APK（nativeLibraryDir=${bundle.libDir}）"
        }
        val workspace = Paths.workspace(ctx)
        val home = Paths.home(ctx)
        val tmp = File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }
        val binds = hostBinds(workspace, home)
        val guestScript = pidFile?.let { guestPid ->
            // The jail's /tmp already exists and is writable by the app. The workspace
            // bind is visible in the UI (and to the agent), so do not put control files
            // there.
            val host = File(installation.jail(), "tmp/$TURN_SCRIPT_NAME")
            host.parentFile?.mkdirs()
            host.writeText(wrapperScript(installation.version, guestPid))
            GUEST_TURN_SCRIPT
        }
        return build(
            proot = bundle.proot,
            loader = bundle.loader,
            jail = File(installation.jailPath),
            workspace = workspace,
            home = home,
            version = installation.version,
            hasBash = installation.hasBash,
            args = args,
            baseEnv = baseEnv,
            binds = binds,
            tmpDir = tmp,
            turnScript = guestScript,
        )
    }

    data class Spec(val argv: List<String>, val env: Map<String, String>, val cwd: File)

    companion object {

        const val TURN_SCRIPT_NAME = ".claudroid-turn.sh"
        const val GUEST_TURN_SCRIPT = "/tmp/$TURN_SCRIPT_NAME"

        /**
         * Wrapper run inside the guest: record the pid (kept by `exec` below) and forward
         * every argument verbatim. Stop kills that pid when PRoot's own cleanup is not
         * enough.
         */
        fun wrapperScript(version: String, guestPidFile: String): String =
            "#!/bin/sh\n" +
                "echo \$\$ > $guestPidFile\n" +
                "exec /opt/claude/$version/claude \"\$@\"\n"

        /**
         * Pure builder (unit-testable). Binds only host paths that exist, because
         * PRoot exits when a `-b` source is missing.
         */
        fun build(
            proot: File,
            loader: File,
            jail: File,
            workspace: File,
            home: File,
            version: String,
            hasBash: Boolean,
            args: List<String>,
            baseEnv: Map<String, String>,
            binds: List<Pair<String, String>>,
            tmpDir: File,
            turnScript: String? = null,
        ): Spec {
            val argv = mutableListOf<String>()
            argv += proot.absolutePath
            argv += "-0"
            argv += "-r"
            argv += jail.absolutePath
            argv += "-w"
            argv += "/work"
            binds.forEach { (host, guest) ->
                argv += "-b"
                argv += "$host:$guest"
            }
            argv += "--link2symlink"
            argv += "--kill-on-exit"
            // Exec the CLI directly; do not prepend the musl loader. PRoot already
            // injects PROOT_LOADER to resolve the binary's PT_INTERP. Invoking the
            // loader as a program fails for ET_EXEC binaries ("Not a valid dynamic
            // program") because the loader must map the program at its fixed address
            // (0x200000), which PRoot's own loader mapping already occupies.
            //
            // A wrapper is only used to report the CLI pid for Stop. It execs the CLI
            // in place, so the pid it writes stays valid.
            if (turnScript != null) {
                argv += "/bin/sh"
                argv += turnScript
            } else {
                argv += "/opt/claude/$version/claude"
            }
            argv += args

            val env = linkedMapOf<String, String>()
            env.putAll(baseEnv)
            env["HOME"] = "/root"
            env["TMPDIR"] = "/tmp"
            env["SHELL"] = if (hasBash) "/bin/bash" else "/bin/sh"
            // libproot.so links against libtalloc.so / libandroid-shmem.so, which live next
            // to it in nativeLibraryDir. bionic does not search the executable's directory.
            proot.parentFile?.absolutePath?.let { env["LD_LIBRARY_PATH"] = it }
            // Some Android 10+ kernels filter the seccomp/ptrace calls PRoot needs.
            env["PROOT_NO_SECCOMP"] = "1"
            env["PROOT_TMP_DIR"] = tmpDir.absolutePath
            env["PROOT_LOADER"] = loader.absolutePath
            return Spec(argv, env, workspace)
        }

        /** Host directories exposed to the guest. Non-existent paths are skipped. */
        fun hostBinds(workspace: File, home: File): List<Pair<String, String>> {
            val binds = linkedMapOf<String, String>()
            fun add(host: String, guest: String) {
                val file = File(host)
                if (host.contains(':') || !file.exists() || !file.canRead()) return
                binds[host] = guest
            }
            add(workspace.absolutePath, "/work")
            add(home.absolutePath, "/root")
            listOf("/dev", "/proc", "/sys", "/system", "/apex", "/vendor", "/odm", "/product")
                .forEach { add(it, it) }
            // Shared storage is optional; without the runtime permission the bind is skipped
            // instead of aborting the whole turn.
            add("/storage/emulated/0", "/sdcard")
            return binds.map { (host, guest) -> host to guest }
        }
    }
}

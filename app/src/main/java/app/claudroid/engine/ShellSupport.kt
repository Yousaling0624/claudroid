package app.claudroid.engine

import android.content.Context
import java.io.File

/**
 * The CLI's Bash tool wants a `bash` on PATH even when it only needs `-c`.
 * Direct (non-chroot) execution has no /bin/bash, so provide a tiny wrapper that
 * forwards to Android's mksh. Proot/chroot get a real GNU bash in the jail; this
 * wrapper is only the last-resort direct path.
 */
object ShellSupport {

    fun ensureDirectShell(ctx: Context): File {
        val bin = File(ctx.filesDir, "bin").apply { mkdirs() }
        val wrapper = File(bin, "bash")
        val body = "#!/system/bin/sh\nexec /system/bin/sh \"\$@\"\n"
        if (!wrapper.isFile || wrapper.readText() != body) {
            wrapper.writeText(body)
        }
        wrapper.setExecutable(true, false)
        // Some tools look for `sh` next to bash before consulting PATH.
        val sh = File(bin, "sh")
        if (!sh.exists()) {
            runCatching { sh.createNewFile() }
            sh.writeText(body)
            sh.setExecutable(true, false)
        }
        return bin
    }
}

package app.claudroid.core

import java.io.File

/**
 * Resolves the chat turn's Claude working directory.
 *
 * The setting stores a path relative to the active workspace, never a host absolute
 * path: the jail always binds the workspace to guest `/work`, so a relative value is
 * portable across workspaces and survives a workspace switch. `..` segments and
 * symlinks are rejected here rather than in the UI, because they would let the CLI's
 * cwd escape `/work` and undermine the bind-mount boundary.
 */
internal object ClaudeWorkDir {

    /**
     * Normalizes a workspace-relative path to `a/b/c`: backslashes become `/`, empty
     * and `.` segments are dropped. Any `..` segment or absolute path is invalid and
     * maps to `""`, which callers treat as the workspace root.
     */
    fun sanitize(relative: String): String {
        val value = relative.trim().replace('\\', '/')
        if (value.isEmpty()) return ""
        // POSIX absolute paths and Windows drive paths are both unusable as a guest
        // relative path; accepting them would make `File(workspace, value)` ignore the
        // workspace on one platform or the other.
        if (value.startsWith("/") || DRIVE_PREFIX.containsMatchIn(value)) return ""
        val segments = value.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return ""
        return segments.joinToString("/")
    }

    /**
     * Canonicalizes `workspace/<relative>` and falls back to [workspace] unless the
     * result is an existing directory that is still inside the workspace. The
     * canonical path comparison is what stops a symlink inside the workspace from
     * pointing the CLI at an unrelated host directory.
     */
    fun resolve(workspace: File, relative: String): File {
        val clean = sanitize(relative)
        if (clean.isEmpty()) return workspace
        val root = realPath(workspace)
        val candidate = runCatching { realPath(File(workspace, clean)) }.getOrElse { return root }
        val inside = candidate == root || candidate.toPath().startsWith(root.toPath())
        return if (inside && candidate.isDirectory) candidate else root
    }

    /**
     * Guest-side path of a resolved directory: `/work` for the workspace root,
     * otherwise `/work/<dir relative to the workspace>`. The relative part is computed
     * from the resolved paths, so a directory reached through a symlink still gets a
     * path that exists inside the `/work` bind.
     */
    fun guestPath(workspace: File, resolved: File): String {
        val root = realPath(workspace)
        val dir = realPath(resolved)
        val rel = runCatching { root.toPath().relativize(dir.toPath()) }.getOrNull()
            ?: return WORKSPACE_GUEST_PATH
        val name = rel.toString().replace(File.separatorChar, '/')
        if (name.isEmpty() || name == "." || name == ".." || name.startsWith("../")) {
            return WORKSPACE_GUEST_PATH
        }
        return "$WORKSPACE_GUEST_PATH/$name"
    }

    /**
     * Canonicalizes and resolves symlinks. `File.canonicalFile` is not enough: on
     * Windows it leaves reparse points in place, and the escape check must compare the
     * real target. Falls back to the canonical/absolute form when the path does not
     * exist, so callers can still decide on a safe default.
     */
    private fun realPath(file: File): File = runCatching { file.toPath().toRealPath().toFile() }
        .recoverCatching { file.canonicalFile }
        .getOrElse { file.absoluteFile }

    /** Guest mount point of the active workspace (see [WorkspaceStore]). */
    const val WORKSPACE_GUEST_PATH = "/work"

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
}

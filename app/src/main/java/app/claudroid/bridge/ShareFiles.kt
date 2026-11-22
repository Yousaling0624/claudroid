package app.claudroid.bridge

import java.io.File

/**
 * Pure JVM half of the share bridge: naming and retention for the PNGs that
 * `share.image` stages in `cacheDir/share/`.
 *
 * The bridge cannot delete a shared file right after `startActivity`: the target app
 * may read the content URI later (or the system may recreate the chooser after a
 * configuration change). Keeping a short window (newest 10 files, at most 24 h) keeps
 * those reads working without letting the cache grow forever.
 *
 * No Android APIs here on purpose, so the pruning rules can be pinned by unit tests.
 */
object ShareFiles {

    /** Same cap the Dart exporter enforces; larger images are refused before writing. */
    const val MAX_IMAGE_BYTES = 24 * 1024 * 1024

    /** A single share sheet entry; long replies must be shared in parts. */
    const val MAX_TEXT_CHARS = 200_000

    private const val PREFIX = "claudroid-"
    private const val SUFFIX = ".png"

    fun newImageFile(dir: File, now: Long): File = File(dir, "$PREFIX$now$SUFFIX")

    /**
     * Deletes share images older than [maxAgeMs] and keeps only the newest [keep]
     * of the rest. Files not named `claudroid-*.png` belong to someone else and are
     * never touched; a missing directory is simply a no-op.
     */
    fun prune(
        dir: File,
        now: Long,
        keep: Int = 10,
        maxAgeMs: Long = 24L * 60 * 60 * 1000,
    ) {
        val shares = dir.listFiles().orEmpty().filter { file ->
            file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX)
        }
        val (expired, fresh) = shares.partition { it.lastModified() < now - maxAgeMs }
        expired.forEach { it.delete() }
        fresh
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop(keep.coerceAtLeast(0))
            .forEach { it.delete() }
    }
}

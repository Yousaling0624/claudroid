package app.claudroid.bridge

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure JVM half of `attachments.capture`: where the camera writes its shot and
 * how long those temporary files may live.
 *
 * The camera app writes through a FileProvider URI into `cacheDir/capture/`; the
 * bridge can only delete the file after `Attachments.import` has copied it into the
 * workspace. A crash or a cancelled capture could otherwise leave a JPEG behind, so
 * every new shot starts by pruning old ones (newest 10, at most 24 h) — the same
 * window `ShareFiles` uses.
 *
 * No Android APIs here on purpose, so the naming and pruning rules can be pinned
 * by unit tests.
 */
object CaptureFiles {

    private const val PREFIX = "IMG_"
    private const val SUFFIX = ".jpg"
    private const val NAME_PATTERN = "yyyyMMdd_HHmmss"

    /**
     * The file the camera should fill, named from the wall clock. [zone] is
     * injectable so tests can pin the timestamp instead of depending on the host
     * timezone.
     */
    fun newPhotoFile(
        dir: File,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): File {
        val stamp = SimpleDateFormat(NAME_PATTERN, Locale.US).apply {
            timeZone = zone
        }.format(Date(now))
        return File(dir, "$PREFIX$stamp$SUFFIX")
    }

    /**
     * Deletes shots older than [maxAgeMs] and keeps only the newest [keep] of the
     * rest. Files not named `IMG_*.jpg` belong to someone else and are never
     * touched; a missing directory is simply a no-op.
     */
    fun prune(
        dir: File,
        now: Long,
        keep: Int = 10,
        maxAgeMs: Long = 24L * 60 * 60 * 1000,
    ) {
        val shots = dir.listFiles().orEmpty().filter { file ->
            file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX)
        }
        val (expired, fresh) = shots.partition { it.lastModified() < now - maxAgeMs }
        expired.forEach { it.delete() }
        fresh
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop(keep.coerceAtLeast(0))
            .forEach { it.delete() }
    }
}

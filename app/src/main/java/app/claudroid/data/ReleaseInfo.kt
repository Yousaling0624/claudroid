package app.claudroid.data

import app.claudroid.engine.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The latest published GitHub Release, used by 设置 → 关于. */
data class AppRelease(
    val tag: String,
    val url: String,
    val publishedAt: String? = null,
)

enum class ReleaseStatus { UP_TO_DATE, UPDATE_AVAILABLE, DEV_BUILD }

/**
 * Version display cannot rely on the checked-in Gradle baseline: release builds are
 * `0.1.<run_number>` while a local build used to claim `1.1.0`, which exists nowhere.
 * Asking GitHub for the latest tag makes the two numbers comparable on screen.
 */
object ReleaseCheck {

    const val REPO = "Yousaling0624/claudroid"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Payload(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        @SerialName("published_at") val publishedAt: String? = null,
    )

    fun parse(raw: String): AppRelease? {
        val payload = runCatching { json.decodeFromString(Payload.serializer(), raw) }.getOrNull()
            ?: return null
        val tag = payload.tagName.trim()
        if (!tag.matches(Regex("v?\\d+(\\.\\d+){1,3}(-[0-9A-Za-z.]+)?"))) return null
        val url = payload.htmlUrl.trim().ifBlank { "https://github.com/$REPO/releases" }
        return AppRelease(tag = tag.removePrefix("v"), url = url, publishedAt = payload.publishedAt)
    }

    suspend fun fetch(): Result<AppRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = HttpUtil.getText(
                API,
                mapOf("Accept" to "application/vnd.github+json"),
            )
            parse(raw) ?: error("GitHub 返回的发布信息无法识别")
        }
    }

    /** Compares the numeric core only; pre-release/build suffixes do not participate. */
    fun compareVersions(left: String, right: String): Int {
        fun core(value: String): List<Int> = value.trim()
            .removePrefix("v")
            .substringBefore('+')
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
        val a = core(left)
        val b = core(right)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun status(installed: String, latest: String): ReleaseStatus {
        val current = installed.trim().removePrefix("v")
        val released = latest.trim().removePrefix("v")
        return when {
            compareVersions(current, released) < 0 -> ReleaseStatus.UPDATE_AVAILABLE
            // Same core but a local "-dev.N" build, or a local baseline ahead of the
            // newest release: it is not something a user can download, so say so.
            compareVersions(current, released) > 0 -> ReleaseStatus.DEV_BUILD
            current.contains('-') -> ReleaseStatus.DEV_BUILD
            else -> ReleaseStatus.UP_TO_DATE
        }
    }
}

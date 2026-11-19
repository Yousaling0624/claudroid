package app.claudroid.engine

import android.content.Context
import app.claudroid.core.Logx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * App-owned defaults merged into the CLI's user settings.
 *
 * Before fetching a page, WebFetch asks `https://api.anthropic.com/api/web/domain_info`
 * whether the host may be fetched, and refuses the fetch when that call fails. On the
 * networks this app targets that host is often unreachable (or intercepted by a proxy
 * with an untrusted certificate), so every WebFetch dies *before* the page is
 * requested — the page itself may be perfectly reachable. The CLI's own escape hatch
 * is the `skipWebFetchPreflight` boolean; it also stops every fetched hostname from
 * being sent to Anthropic.
 *
 * `HOME` is bound to `filesDir/home` for PRoot, chroot and direct launches alike, so a
 * single file under the app's home reaches every launch mode.
 */
internal object CliSettings {

    internal const val SKIP_WEB_FETCH_PREFLIGHT = "skipWebFetchPreflight"

    private val pretty = Json { prettyPrint = true }

    /** Merges [SKIP_WEB_FETCH_PREFLIGHT] into `<home>/.claude/settings.json`. */
    fun ensure(home: File, ctx: Context? = null) {
        val file = File(File(home, ".claude"), "settings.json")
        val existing = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        val merged = merge(existing)
        if (merged == null) {
            // Never clobber a hand-edited file we cannot parse; the CLI would lose it.
            Logx.e("CLI settings.json 不是 JSON 对象，跳过 WebFetch 预检设置：${file.path}", ctx)
            return
        }
        if (merged == existing) return
        runCatching {
            file.parentFile?.mkdirs()
            // Same-directory rename: a killed turn must not leave a half-written file.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(merged)
            if (!tmp.renameTo(file)) {
                file.writeText(merged)
                tmp.delete()
            }
        }.onFailure { Logx.e("写入 CLI 设置失败：${it.message}", ctx) }
    }

    /**
     * Pure merge used by tests. Returns the text to write, [existing] unchanged when the
     * flag is already set, or null when the existing text is not a JSON object.
     */
    internal fun merge(existing: String?): String? {
        val root: JsonObject = if (existing.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { Json.parseToJsonElement(existing).jsonObject }.getOrNull()
                ?: return null
        }
        if (root[SKIP_WEB_FETCH_PREFLIGHT] == JsonPrimitive(true)) return existing
        val updated = buildJsonObject {
            for ((key, value) in root) put(key, value)
            put(SKIP_WEB_FETCH_PREFLIGHT, JsonPrimitive(true))
        }
        return pretty.encodeToString(JsonObject.serializer(), updated)
    }
}

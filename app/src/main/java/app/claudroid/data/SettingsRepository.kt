package app.claudroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "claudroid_settings")

class SettingsRepository(private val context: Context) {

    private object K {
        val apiFlavor = stringPreferencesKey("api_flavor")
        val baseUrl = stringPreferencesKey("base_url")
        val apiKey = stringPreferencesKey("api_key")
        val authStyle = stringPreferencesKey("auth_style")
        val model = stringPreferencesKey("model")
        val smallModel = stringPreferencesKey("small_model")
        val activeTier = stringPreferencesKey("active_tier")
        val opusModel = stringPreferencesKey("opus_model")
        val fableModel = stringPreferencesKey("fable_model")
        val sonnetModel = stringPreferencesKey("sonnet_model")
        val haikuModel = stringPreferencesKey("haiku_model")
        val permissionMode = stringPreferencesKey("permission_mode")
        val engineMode = stringPreferencesKey("engine_mode")
        // Replaced by engine_mode; read only to migrate existing installs.
        val rootEnabled = booleanPreferencesKey("root_enabled")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val maxTurns = intPreferencesKey("max_turns")
        val themeMode = intPreferencesKey("theme_mode")
        val extraSystemPrompt = stringPreferencesKey("extra_system_prompt")
        val extraEnv = stringPreferencesKey("extra_env")
    }

    val flow: Flow<AppSettings> = context.settingsStore.data.map { p -> decode(p) }

    suspend fun current(): AppSettings = flow.first()

    private fun decode(p: androidx.datastore.preferences.core.Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            apiFlavor = p[K.apiFlavor]?.let { runCatching { ApiFlavor.valueOf(it) }.getOrNull() } ?: d.apiFlavor,
            baseUrl = p[K.baseUrl] ?: d.baseUrl,
            apiKey = p[K.apiKey] ?: d.apiKey,
            authStyle = p[K.authStyle]?.let { runCatching { AuthStyle.valueOf(it) }.getOrNull() } ?: d.authStyle,
            model = p[K.model] ?: d.model,
            smallModel = p[K.smallModel] ?: d.smallModel,
            // Missing keys are normal for installs created before tier mapping existed.
            activeTier = p[K.activeTier] ?: d.activeTier,
            opusModel = p[K.opusModel] ?: d.opusModel,
            fableModel = p[K.fableModel] ?: d.fableModel,
            sonnetModel = p[K.sonnetModel] ?: d.sonnetModel,
            haikuModel = p[K.haikuModel] ?: d.haikuModel,
            permissionMode = p[K.permissionMode]?.let { runCatching { PermissionMode.valueOf(it) }.getOrNull() }
                ?: d.permissionMode,
            engineMode = p[K.engineMode]?.let { runCatching { EngineMode.valueOf(it) }.getOrNull() }
                ?: legacyEngineMode(p),
            autoUpdate = p[K.autoUpdate] ?: d.autoUpdate,
            maxTurns = p[K.maxTurns] ?: d.maxTurns,
            themeMode = p[K.themeMode] ?: d.themeMode,
            extraSystemPrompt = p[K.extraSystemPrompt] ?: d.extraSystemPrompt,
            extraEnv = p[K.extraEnv] ?: d.extraEnv,
        )
    }

    /**
     * Older versions stored a boolean `root_enabled`. true meant "use root when granted",
     * which is what AUTO does; false meant "never use root", i.e. today's PRoot mode.
     */
    private fun legacyEngineMode(p: androidx.datastore.preferences.core.Preferences): EngineMode =
        when (p[K.rootEnabled]) {
            true -> EngineMode.AUTO
            false -> EngineMode.PROOT
            null -> EngineMode.AUTO
        }

    suspend fun edit(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val next = transform(decode(p))
            p[K.apiFlavor] = next.apiFlavor.name
            p[K.baseUrl] = next.baseUrl
            p[K.apiKey] = next.apiKey
            p[K.authStyle] = next.authStyle.name
            p[K.model] = next.model
            p[K.smallModel] = next.smallModel
            p[K.activeTier] = next.activeTier
            p[K.opusModel] = next.opusModel
            p[K.fableModel] = next.fableModel
            p[K.sonnetModel] = next.sonnetModel
            p[K.haikuModel] = next.haikuModel
            p[K.permissionMode] = next.permissionMode.name
            p[K.engineMode] = next.engineMode.name
            p[K.autoUpdate] = next.autoUpdate
            p[K.maxTurns] = next.maxTurns
            p[K.themeMode] = next.themeMode
            p[K.extraSystemPrompt] = next.extraSystemPrompt
            p[K.extraEnv] = next.extraEnv
        }
    }
}

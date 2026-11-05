package app.claudroid.data

import kotlinx.serialization.Serializable

/** How the API key is presented to the endpoint. Gateways differ (see README). */
enum class AuthStyle(val label: String) {
    BEARER("Authorization: Bearer"),
    API_KEY("x-api-key"),
    BOTH("两个都发"),
}

/** Which wire protocol the configured endpoint speaks. */
enum class ApiFlavor(val label: String, val hint: String) {
    ANTHROPIC("Anthropic 兼容", "直接把 Anthropic Messages 协议发给网关"),
    OPENAI("OpenAI 兼容", "App 内置本地代理，转译成 /v1/chat/completions"),
}

enum class PermissionMode(val cli: String, val label: String, val hint: String) {
    BYPASS("bypassPermissions", "完全放行", "不询问，所有工具直接执行。root 场景常用。"),
    ACCEPT_EDITS("acceptEdits", "自动改文件", "文件编辑和常见文件命令自动通过，其他命令仍会被拒绝。"),
    MANUAL("default", "默认（只读）", "无交互宿主：需要批准的工具调用会被直接拒绝，并告知模型。"),
    PLAN("plan", "只读规划", "只做计划，不落地修改。"),
}

/** How the embedded CLI is launched. */
enum class EngineMode(val label: String, val hint: String) {
    AUTO("自动", "有 root 授权时用 chroot，否则用 PRoot（免 root）"),
    PROOT("Proot", "始终使用 PRoot，不需要 root；能力受 Android 沙箱限制"),
    ROOT("Root", "始终使用 root + chroot；未授权时任务无法运行"),
}

@Serializable
data class AppSettings(
    val apiFlavor: ApiFlavor = ApiFlavor.ANTHROPIC,
    val baseUrl: String = "https://api.anthropic.com",
    val apiKey: String = "",
    // Anthropic's public API authenticates with x-api-key; most gateways want Bearer.
    val authStyle: AuthStyle = AuthStyle.API_KEY,
    val model: String = "claude-sonnet-5-5",
    val smallModel: String = "",
    /**
     * Which Claude Code tier the next turn should request. Empty keeps the legacy
     * behavior: [model] is passed to `--model` as-is. A non-empty value is an
     * [ModelTier.name] and is passed as a CLI alias so per-tier mappings apply.
     */
    val activeTier: String = "",
    /** Concrete backend model for each tier; blank means "official/CLI default". */
    val opusModel: String = "",
    val fableModel: String = "",
    val sonnetModel: String = "",
    val haikuModel: String = "",
    val permissionMode: PermissionMode = PermissionMode.BYPASS,
    val engineMode: EngineMode = EngineMode.AUTO,
    val autoUpdate: Boolean = true,
    val maxTurns: Int = 0,
    val themeMode: Int = 0, // 0 system, 1 light, 2 dark
    val extraSystemPrompt: String = "",
    val extraEnv: String = "",
) {
    /**
     * Local gateways (Ollama, LM Studio, llama.cpp) usually have no key. Requiring one
     * would make the app unusable with the most common offline setup.
     */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() || isLocalEndpoint()

    fun isLocalEndpoint(): Boolean {
        val host = runCatching { java.net.URI(normalizedBaseUrl()).host }.getOrNull()?.lowercase()
            ?: return false
        if (host == "localhost" || host == "::1" || host == "[::1]" || host == "10.0.2.2") return true
        if (host.endsWith(".local") || host.endsWith(".lan")) return true
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 127 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            else -> false
        }
    }

    /**
     * The Anthropic SDK appends `/v1/messages` to ANTHROPIC_BASE_URL, so a base URL that
     * already ends in /v1 produces /v1/v1/messages and a 404. Strip the endpoint path
     * instead of letting the user discover that the hard way.
     */
    fun normalizedBaseUrl(): String {
        var url = baseUrl.trim().trimEnd('/')
        if (url.endsWith("/v1/messages")) url = url.removeSuffix("/v1/messages")
        else if (url.endsWith("/messages")) url = url.removeSuffix("/messages")
        if (url.endsWith("/v1")) url = url.removeSuffix("/v1")
        return url.ifBlank { "https://api.anthropic.com" }
    }

    /** Null means "no tier selected"; callers should fall back to [model]. */
    val resolvedActiveTier: ModelTier?
        get() = ModelTier.fromStored(activeTier)

    fun tierModel(tier: ModelTier): String = when (tier) {
        ModelTier.OPUS -> opusModel
        ModelTier.FABLE -> fableModel
        ModelTier.SONNET -> sonnetModel
        ModelTier.HAIKU -> haikuModel
    }.trim()

    /** Keeps the per-tier mapping update in one place for the settings UI. */
    fun withTierModel(tier: ModelTier, value: String): AppSettings {
        val clean = value.trim()
        return when (tier) {
            ModelTier.OPUS -> copy(opusModel = clean)
            ModelTier.FABLE -> copy(fableModel = clean)
            ModelTier.SONNET -> copy(sonnetModel = clean)
            ModelTier.HAIKU -> copy(haikuModel = clean)
        }
    }

    /**
     * A concrete model for gateways that cannot understand tier aliases. An unmapped
     * tier falls back to [model], which is also what existing installs already use.
     */
    fun modelForTier(tier: ModelTier): String = tierModel(tier).ifBlank { model.trim() }

    /**
     * True when [candidate] already is one of the concrete models configured for a tier.
     * The CLI may resolve an alias against `ANTHROPIC_DEFAULT_*_MODEL` before it reaches
     * the local OpenAI proxy, so the proxy must recognize that mapped id and forward it
     * instead of replacing it with the global default model.
     */
    fun isConfiguredTierModel(candidate: String): Boolean {
        val value = candidate.trim()
        if (value.isEmpty()) return false
        return ModelTier.entries.any { tierModel(it).equals(value, ignoreCase = true) }
    }

    /**
     * What `--model` receives. A tier alias keeps the CLI's own prompt/model routing
     * intact and is what makes the per-tier env mappings take effect.
     */
    fun effectiveModelArg(): String = resolvedActiveTier?.alias ?: model.trim()

    /**
     * Env overrides understood by Claude Code. Empty tiers are omitted so the CLI's
     * built-in defaults still apply; user-supplied `extraEnv` is merged later and wins.
     *
     * Haiku doubles as the small/fast model unless an explicit small model is set, the
     * same fallback Magpie uses for `ANTHROPIC_SMALL_FAST_MODEL`.
     */
    fun modelEnv(): Map<String, String> = buildMap {
        tierModel(ModelTier.OPUS).takeIf { it.isNotBlank() }
            ?.let { put("ANTHROPIC_DEFAULT_OPUS_MODEL", it) }
        tierModel(ModelTier.FABLE).takeIf { it.isNotBlank() }
            ?.let { put("ANTHROPIC_DEFAULT_FABLE_MODEL", it) }
        tierModel(ModelTier.SONNET).takeIf { it.isNotBlank() }
            ?.let { put("ANTHROPIC_DEFAULT_SONNET_MODEL", it) }
        tierModel(ModelTier.HAIKU).takeIf { it.isNotBlank() }
            ?.let { put("ANTHROPIC_DEFAULT_HAIKU_MODEL", it) }
        val small = smallModel.trim().ifBlank { tierModel(ModelTier.HAIKU) }
        if (small.isNotBlank()) put("ANTHROPIC_SMALL_FAST_MODEL", small)
    }
}

/** One-tap provider setup. Keeps the existing API key when applied. */
data class ProviderPreset(
    val id: String,
    val label: String,
    val flavor: ApiFlavor,
    val baseUrl: String,
    val authStyle: AuthStyle,
    val model: String,
    val hint: String,
)

object Providers {
    val all = listOf(
        ProviderPreset(
            id = "anthropic",
            label = "Anthropic 官方",
            flavor = ApiFlavor.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            authStyle = AuthStyle.API_KEY,
            model = "claude-sonnet-5-5",
            hint = "官方 Messages API，需要 x-api-key；可在模型档位里映射 Opus/Fable/Sonnet/Haiku",
        ),
        ProviderPreset(
            id = "deepseek-anthropic",
            label = "DeepSeek · Anthropic 兼容",
            flavor = ApiFlavor.ANTHROPIC,
            baseUrl = "https://api.deepseek.com/anthropic",
            authStyle = AuthStyle.BEARER,
            model = "deepseek-chat",
            hint = "DeepSeek 官方 Anthropic 兼容端点，工具调用最稳定",
        ),
        ProviderPreset(
            id = "deepseek",
            label = "DeepSeek · OpenAI 兼容",
            flavor = ApiFlavor.OPENAI,
            baseUrl = "https://api.deepseek.com/v1",
            authStyle = AuthStyle.BEARER,
            model = "deepseek-chat",
            hint = "经 App 内置代理翻译；deepseek-reasoner 的思考内容会显示为思考过程",
        ),
        ProviderPreset(
            id = "openrouter",
            label = "OpenRouter",
            flavor = ApiFlavor.OPENAI,
            baseUrl = "https://openrouter.ai/api/v1",
            authStyle = AuthStyle.BEARER,
            model = "anthropic/claude-sonnet-4.5",
            hint = "一个 key 用多家模型；可在设置里拉取模型列表",
        ),
        ProviderPreset(
            id = "ollama",
            label = "本地 Ollama",
            flavor = ApiFlavor.OPENAI,
            baseUrl = "http://127.0.0.1:11434/v1",
            authStyle = AuthStyle.BEARER,
            model = "qwen2.5-coder:7b",
            hint = "手机本机或局域网模型，无需 API Key",
        ),
    )

    fun apply(settings: AppSettings, preset: ProviderPreset): AppSettings = settings.copy(
        apiFlavor = preset.flavor,
        baseUrl = preset.baseUrl,
        authStyle = preset.authStyle,
        model = preset.model,
        // Mappings name concrete models on the old backend. Carrying them over would
        // silently override the preset model (and often point at ids that do not exist).
        activeTier = "",
        smallModel = "",
        opusModel = "",
        fableModel = "",
        sonnetModel = "",
        haikuModel = "",
    )
}

package app.claudroid.data

/**
 * Claude Code asks for a model tier (opus/fable/sonnet/haiku) and resolves it to a
 * concrete backend model. Magpie-style gateways remap each tier with
 * `ANTHROPIC_DEFAULT_<TIER>_MODEL`; keeping the mapping in settings lets the app offer
 * the same "pick a tier, not a vendor model" behavior across providers.
 *
 * [alias] is the value passed to `--model`; it must stay a CLI-recognized alias rather
 * than a concrete model id, otherwise the CLI never consults the env mapping.
 */
enum class ModelTier(
    val label: String,
    val alias: String,
    val hint: String,
) {
    OPUS("Opus", "opus", "深度推理与长任务"),
    FABLE("Fable", "fable", "最强推理，适合最难的长期任务"),
    SONNET("Sonnet", "sonnet", "速度与能力均衡，日常默认"),
    HAIKU("Haiku", "haiku", "轻量快速，适合后台小任务");

    companion object {
        /** Stored as the enum name so settings survive reordering or alias changes. */
        fun fromStored(value: String?): ModelTier? {
            val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull {
                it.name.equals(candidate, ignoreCase = true) ||
                    it.alias.equals(candidate, ignoreCase = true)
            }
        }

        /**
         * Matches a bare alias or an official-looking model id such as
         * `claude-opus-5-5` or `anthropic/claude-sonnet-4.5`. Matching whole
         * delimiter-separated segments keeps unrelated names (for example
         * `deepseek-sonnet-pro`) from being mistaken for a tier only when the segment
         * itself is the alias.
         */
        fun fromAliasOrId(model: String?): ModelTier? {
            val normalized = model?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { tier ->
                normalized == tier.alias ||
                    Regex("(^|[/:_.-])${Regex.escape(tier.alias)}([/:_.-]|$)")
                        .containsMatchIn(normalized)
            }
        }
    }
}

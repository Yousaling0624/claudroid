package app.claudroid.ui.components

import app.claudroid.data.AppSettings
import app.claudroid.data.ModelTier

/**
 * Text shown on the composer chip. A selected tier leads with its label so the user
 * sees "Opus" rather than the provider's concrete model id; the mapped model is kept
 * as a short suffix because gateways can remap the same tier per install.
 */
fun composerModelLabel(settings: AppSettings): String {
    val tier = settings.resolvedActiveTier ?: return settings.model
    val mapped = settings.tierModel(tier)
    return if (mapped.isEmpty()) tier.label else "${tier.label} · $mapped"
}

/**
 * Assistant signature / chat top-bar line. Running status is handled separately by
 * ChatStatus; this is the idle label.
 */
fun chatModelLabel(settings: AppSettings): String {
    val tier = settings.resolvedActiveTier ?: return settings.model
    val mapped = settings.tierModel(tier).ifEmpty { "官方默认" }
    return "${tier.label} · $mapped"
}

/** Concrete mapping row: the configured model, or the official-default fallback. */
fun tierMappingLabel(settings: AppSettings, tier: ModelTier): String =
    settings.tierModel(tier).ifEmpty { "官方默认" }

/** One-line summary for the tier chip row's supporting copy. */
fun activeTierSummary(settings: AppSettings): String {
    val tier = settings.resolvedActiveTier ?: return "默认模型 · ${settings.model}"
    return "${tier.label} · ${tierMappingLabel(settings, tier)}"
}

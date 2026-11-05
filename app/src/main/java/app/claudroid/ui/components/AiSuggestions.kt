package app.claudroid.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.claudroid.data.AppSettings
import app.claudroid.engine.SuggestionGenerator
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Model-generated input suggestions for the landing and empty-chat screens.
 *
 * Nothing is cached or persisted: each entry into a screen asks the model again so the
 * chips match the current endpoint/model. An unconfigured endpoint returns an empty
 * state without a request, and a failed request also yields an empty list — callers
 * hide the section entirely rather than substituting canned prompts.
 */
@Immutable
data class AiSuggestionsState(
    val items: List<String> = emptyList(),
    val loading: Boolean = false,
)

/**
 * Requests three suggestions for [settings] and tracks loading state.
 *
 * [key] is the screen-level identity (e.g. the home reset counter); the settings
 * fields are keys too so switching endpoint or model regenerates instead of showing
 * suggestions produced by the previous configuration.
 */
@Composable
fun rememberAiSuggestions(settings: AppSettings, key: Any?): AiSuggestionsState {
    var state by remember(key) { mutableStateOf(AiSuggestionsState()) }
    LaunchedEffect(
        key,
        settings.isConfigured,
        settings.baseUrl,
        settings.apiKey,
        settings.apiFlavor,
        settings.model,
        settings.activeTier,
    ) {
        if (!settings.isConfigured) {
            state = AiSuggestionsState()
            return@LaunchedEffect
        }
        state = AiSuggestionsState(loading = true)
        val items = SuggestionGenerator.generate(settings)
        // A request can finish after the effect was cancelled (the generator swallows
        // CancellationException); never let it overwrite the newer configuration's state.
        coroutineContext.ensureActive()
        state = AiSuggestionsState(items = items)
    }
    return state
}

/**
 * Shared breathing alpha for the loading skeletons. One timing for both screens keeps
 * the placeholder from feeling like two different components. Returned as state so the
 * caller can read it inside a `graphicsLayer` block without recomposing every frame.
 */
@Composable
internal fun rememberSuggestionSkeletonAlpha(): State<Float> {
    val transition = rememberInfiniteTransition(label = "suggestion-skeleton")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = Motion.EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "suggestion-skeleton-alpha",
    )
}

package app.claudroid.engine.openai

import app.claudroid.data.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Claude Code asks for a tier, the gateway speaks concrete model ids. These tests feed
 * real Messages request bodies to [OpenAiProxy.upstreamModel] so the routing rules stay
 * in one place (Magpie's ANTHROPIC_DEFAULT_<TIER>_MODEL semantics).
 */
class UpstreamModelRoutingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun request(model: String): JsonObject = json.parseToJsonElement(
        """
        {"model":"$model","max_tokens":1024,"stream":true,
         "messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}
        """.trimIndent()
    ).jsonObject

    private val mapped = AppSettings(
        model = "gateway-fallback",
        opusModel = "gateway-opus",
        fableModel = "gateway-fable",
        sonnetModel = "gateway-sonnet",
        haikuModel = "gateway-haiku",
    )

    @Test
    fun `bare tier aliases resolve to their per-tier model`() {
        listOf(
            "opus" to "gateway-opus",
            "fable" to "gateway-fable",
            "sonnet" to "gateway-sonnet",
            "haiku" to "gateway-haiku",
        ).forEach { (alias, expected) ->
            assertEquals(alias, expected, OpenAiProxy.upstreamModel(request(alias), mapped))
        }
    }

    @Test
    fun `official model ids resolve through their tier segment`() {
        // Shapes Claude Code actually sends: Anthropic ids and OpenRouter ids.
        assertEquals(
            "gateway-opus",
            OpenAiProxy.upstreamModel(request("claude-opus-5-5"), mapped),
        )
        assertEquals(
            "gateway-sonnet",
            OpenAiProxy.upstreamModel(request("anthropic/claude-sonnet-4.5"), mapped),
        )
        assertEquals(
            "gateway-haiku",
            OpenAiProxy.upstreamModel(request("claude-3-5-haiku-20241022"), mapped),
        )
    }

    @Test
    fun `tier mapping wins over the single configured model`() {
        // mapped.model is non-blank, but a tier request must use the tier mapping.
        assertEquals(
            "gateway-opus",
            OpenAiProxy.upstreamModel(request("claude-opus-5-5"), mapped),
        )
    }

    @Test
    fun `unmapped tier falls back to the configured model`() {
        val settings = mapped.copy(opusModel = "")
        assertEquals(
            "gateway-fallback",
            OpenAiProxy.upstreamModel(request("claude-opus-5-5"), settings),
        )
    }

    @Test
    fun `an explicit small model is forwarded instead of the default`() {
        val settings = mapped.copy(model = "gateway-fallback", smallModel = "gpt-4o-mini")
        assertEquals(
            "gpt-4o-mini",
            OpenAiProxy.upstreamModel(request("gpt-4o-mini"), settings),
        )
        // A blank small model must not swallow the ordinary-model fallback.
        assertEquals(
            "gateway-fallback",
            OpenAiProxy.upstreamModel(request("deepseek-chat"), settings.copy(smallModel = "")),
        )
    }

    @Test
    fun `ordinary models keep the existing override behavior`() {
        assertEquals(
            "gateway-fallback",
            OpenAiProxy.upstreamModel(request("deepseek-chat"), mapped),
        )
        // With no configured model the request's own model is forwarded, not an empty one.
        assertEquals(
            "deepseek-chat",
            OpenAiProxy.upstreamModel(request("deepseek-chat"), mapped.copy(model = "")),
        )
    }

    @Test
    fun `empty configured model never produces an empty upstream model`() {
        val empty = AppSettings(model = "", opusModel = "", sonnetModel = "")
        // Tier request, no tier mapping and no fallback model: echo the requested id.
        assertEquals(
            "claude-sonnet-4-5",
            OpenAiProxy.upstreamModel(request("claude-sonnet-4-5"), empty),
        )
        assertEquals("opus", OpenAiProxy.upstreamModel(request("opus"), empty))
    }

    @Test
    fun `a concrete mapped model resolved by the CLI is forwarded instead of the default`() {
        // Claude Code may resolve `opus` -> ANTHROPIC_DEFAULT_OPUS_MODEL before it
        // reaches the proxy. That id has no tier segment, but it is still the user's
        // chosen tier and must not be replaced by the global model.
        val settings = mapped.copy(model = "gateway-fallback", opusModel = "deepseek-reasoner")
        assertEquals(
            "deepseek-reasoner",
            OpenAiProxy.upstreamModel(request("deepseek-reasoner"), settings),
        )
    }

    @Test
    fun `unrelated model names are not treated as a tier`() {
        val settings = mapped.copy(model = "gateway-fallback")
        assertEquals(
            "gateway-fallback",
            OpenAiProxy.upstreamModel(request("mysonnet-pro"), settings),
        )
    }
}

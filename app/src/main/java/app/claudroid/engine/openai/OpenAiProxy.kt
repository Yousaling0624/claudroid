package app.claudroid.engine.openai

import app.claudroid.core.Logx
import app.claudroid.data.AppSettings
import app.claudroid.data.AuthStyle
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Local loopback bridge: speaks the Anthropic Messages API to Claude Code and forwards to
 * an OpenAI-compatible upstream (`/v1/chat/completions`), translating both directions.
 *
 * Why a proxy instead of just changing the base URL: the CLI implements the Anthropic wire
 * protocol (content blocks, tool_use/tool_result, its own SSE event names). OpenAI-compatible
 * servers use a different schema and a different streaming shape, so something has to
 * translate. Doing it in-process means no extra app, no extra download, and the key never
 * leaves the device except to the upstream you configured.
 */
class OpenAiProxy(
    private val settings: AppSettings,
) : NanoHTTPD("127.0.0.1", 0) {

    private val json = Json { ignoreUnknownKeys = true }

    val baseUrl: String get() = "http://127.0.0.1:$listeningPort"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.substringBefore('?')
        return try {
            when {
                session.method == Method.POST && uri == "/v1/messages" -> handleMessages(session)
                session.method == Method.POST && uri == "/v1/messages/count_tokens" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"input_tokens":${estimateTokens(session)}}""",
                    )
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    errorJson("not_found_error", "unsupported path $uri"),
                )
            }
        } catch (t: Throwable) {
            Logx.e("OpenAI 代理错误: ${t.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                errorJson("api_error", t.message ?: "proxy failure"),
            )
        }
    }

    // ------------------------------------------------------------------ messages

    private fun handleMessages(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                errorJson("invalid_request_error", "body is not JSON"),
            )
        }
        val wantsStream = (request["stream"] as? JsonPrimitive)?.booleanOrNull != false
        val payload = OpenAiMapping.toChatRequest(
            request,
            settings.model.ifBlank { null },
            stream = wantsStream,
        ).toString()

        val connection = openUpstream(payload, stream = wantsStream)
        val status = connection.responseCode
        if (status !in 200..299) {
            val detail = runCatching {
                connection.errorStream?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            connection.disconnect()
            val type = when (status) {
                401, 403 -> "authentication_error"
                429 -> "rate_limit_error"
                in 400..499 -> "invalid_request_error"
                else -> "api_error"
            }
            return newFixedLengthResponse(
                httpStatus(status),
                "application/json",
                errorJson(type, "上游返回 $status：${detail.take(500)}"),
            )
        }

        return if (wantsStream) {
            val pipeIn = PipedInputStream(1 shl 20)
            val pipeOut = PipedOutputStream(pipeIn)
            kotlin.concurrent.thread(name = "openai-proxy-stream", isDaemon = true) {
                try {
                    BufferedWriter(OutputStreamWriter(pipeOut, Charsets.UTF_8)).use { writer ->
                        relayStream(connection, writer)
                    }
                } catch (t: Throwable) {
                    Logx.e("OpenAI 流式转发中断: ${t.message}")
                    runCatching {
                        pipeOut.write(
                            OpenAiMapping.frame(
                                "error",
                                buildJsonObject {
                                    put("type", "error")
                                    put("error", buildJsonObject {
                                        put("type", "api_error")
                                        put("message", t.message ?: "stream aborted")
                                    })
                                },
                            ).toByteArray()
                        )
                    }
                } finally {
                    runCatching { pipeOut.close() }
                    runCatching { connection.disconnect() }
                }
            }

            newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
        } else {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val mapped = runCatching {
                OpenAiMapping.nonStreamResponse(json.parseToJsonElement(text).jsonObject, settings.model)
            }.getOrNull()
            if (mapped == null) {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    errorJson("api_error", "上游返回了无法解析的响应体"),
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    json.encodeToString(JsonObject.serializer(), mapped),
                )
            }
        }
    }

    private fun relayStream(connection: HttpURLConnection, writer: BufferedWriter) {
        val translator = OpenAiMapping.StreamTranslator(settings.model)
        writer.write(translator.start())
        writer.flush()

        // A few gateways ignore `stream: true` and answer with one JSON body. Treat that
        // as a single chunk instead of emitting an empty message.
        if (!connection.contentType.orEmpty().contains("text/event-stream", ignoreCase = true)) {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (root != null) {
                translator.onChunk(streamChunkFromMessage(root)).forEach { writer.write(it) }
            }
            translator.finish().forEach { writer.write(it) }
            writer.flush()
            return
        }

        connection.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                translator.onChunk(chunk).forEach { writer.write(it) }
                writer.flush()
            }
        }
        translator.finish().forEach { writer.write(it) }
        writer.flush()
    }

    /** Turns a complete OpenAI response into the chunk shape the translator consumes. */
    private fun streamChunkFromMessage(root: JsonObject): JsonObject {
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val message = choice?.get("message") as? JsonObject
        val delta = buildJsonObject {
            message?.get("content")?.let { put("content", it) }
            message?.get("reasoning_content")?.let { put("reasoning_content", it) }
            message?.get("reasoning")?.let { put("reasoning", it) }
            (message?.get("tool_calls") as? JsonArray)?.let { put("tool_calls", it) }
        }
        return buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", delta)
                    choice?.get("finish_reason")?.let { put("finish_reason", it) }
                })
            })
            (root["usage"] as? JsonObject)?.let { put("usage", it) }
        }
    }

    private fun openUpstream(payload: String, stream: Boolean): HttpURLConnection {
        val url = chatCompletionsUrl(settings)
        // A device-wide HTTP proxy (corporate Wi-Fi, emulator setups) otherwise receives
        // requests for 127.0.0.1 too and resets them, so a local Ollama/LM Studio never
        // works. Local endpoints must ignore the system proxy.
        val target = URL(url)
        val connection = if (settings.isLocalEndpoint()) {
            target.openConnection(Proxy.NO_PROXY)
        } else {
            target.openConnection()
        }
        val conn = (connection as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = if (stream) 600_000 else 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
            // Local servers (Ollama, llama.cpp) usually run without a key; sending an empty
            // `Authorization: Bearer ` makes some of them reject the request.
            if (settings.apiKey.isNotBlank()) {
                when (settings.authStyle) {
                    AuthStyle.BEARER -> setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                    AuthStyle.API_KEY -> setRequestProperty("x-api-key", settings.apiKey)
                    AuthStyle.BOTH -> {
                        setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                        setRequestProperty("x-api-key", settings.apiKey)
                    }
                }
            }
        }
        conn.outputStream.use { it.write(payload.toByteArray()) }
        return conn
    }

    private fun readBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun estimateTokens(session: IHTTPSession): Int {
        val body = runCatching { readBody(session) }.getOrDefault("")
        return (body.length / 4).coerceAtLeast(1)
    }

    private fun errorJson(type: String, message: String): String =
        """{"type":"error","error":{"type":"$type","message":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(message))}}}"""

    private fun httpStatus(code: Int): Response.IStatus = when (code) {
        401 -> Response.Status.UNAUTHORIZED
        403 -> Response.Status.FORBIDDEN
        404 -> Response.Status.NOT_FOUND
        429 -> Response.Status.TOO_MANY_REQUESTS
        in 400..499 -> Response.Status.BAD_REQUEST
        else -> Response.Status.INTERNAL_ERROR
    }

    companion object {
        /** OpenAI 的 base 通常已经带 /v1；不带就补上。 */
        fun chatCompletionsUrl(settings: AppSettings): String {
            val base = settings.baseUrl.trim().trimEnd('/')
            if (base.endsWith("/chat/completions")) return base
            if (base.endsWith("/v1")) return "$base/chat/completions"
            return "$base/v1/chat/completions"
        }
    }
}

package com.vesper.flipper.ai

import android.util.Log
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.security.InputValidator
import com.vesper.flipper.security.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic Claude API client.
 * Implements [AiClient] using the Anthropic Messages API directly (not via OpenRouter).
 *
 * Key API differences vs OpenRouter/OpenAI:
 *  - System prompt is a top-level field, not a message
 *  - Tool schemas use `input_schema` instead of `parameters`
 *  - Tool results are sent as `user` messages with `tool_result` content blocks
 *  - Assistant messages with tool calls use `tool_use` content blocks
 *  - Images use Anthropic's native `image` content block format
 *  - Auth header is `x-api-key`, not `Authorization: Bearer`
 */
@Singleton
class ClaudeClient @Inject constructor(
    private val settingsStore: SettingsStore,
    private val openRouterClient: OpenRouterClient
) : AiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val rateLimiter = RateLimiter(maxRequests = 30, windowMs = 60_000)

    // ── Tool definition (Anthropic format uses input_schema, not parameters) ──

    private val executeCommandTool = JsonObject(mapOf(
        "name" to JsonPrimitive("execute_command"),
        "description" to JsonPrimitive(
            "Execute a Flipper Zero action. The 'action' enum lists all supported operations. Use 'args' for action-specific parameters."
        ),
        "input_schema" to JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "action" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "enum" to JsonArray(SUPPORTED_ACTIONS.map { JsonPrimitive(it) }),
                    "description" to JsonPrimitive("The action to perform on the Flipper Zero (request_photo requires smart glasses)")
                )),
                "args" to JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "description" to JsonPrimitive("Action-specific arguments. See system prompt for per-action schema.")
                )),
                "justification" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "description" to JsonPrimitive("One sentence explaining why this action is needed.")
                )),
                "expected_effect" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "description" to JsonPrimitive("What the user will see or what will change after this action.")
                ))
            )),
            "required" to JsonArray(listOf(JsonPrimitive("action"), JsonPrimitive("args")))
        ))
    ))

    private fun executeCommandToolWithoutGlasses() = JsonObject(
        executeCommandTool.toMutableMap().apply {
            val schema = (this["input_schema"] as JsonObject).toMutableMap()
            val props = (schema["properties"] as JsonObject).toMutableMap()
            val actionProp = (props["action"] as JsonObject).toMutableMap()
            actionProp["enum"] = JsonArray(
                SUPPORTED_ACTIONS.filter { it != "request_photo" }.map { JsonPrimitive(it) }
            )
            props["action"] = JsonObject(actionProp)
            schema["properties"] = JsonObject(props)
            this["input_schema"] = JsonObject(schema)
        }
    )

    // ── AiClient implementation ───────────────────────────────────────────────

    override suspend fun chat(
        messages: List<ChatMessage>,
        sessionId: String
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        if (!rateLimiter.tryAcquire()) {
            val waitSec = rateLimiter.timeUntilReset() / 1000
            return@withContext ChatCompletionResult.Error(
                "Rate limit exceeded. Please wait ${waitSec}s before trying again."
            )
        }

        val apiKey = settingsStore.claudeApiKey.first()
            ?: return@withContext ChatCompletionResult.Error("Anthropic API key not configured")
        if (!isValidClaudeApiKey(apiKey)) {
            return@withContext ChatCompletionResult.Error("Invalid Anthropic API key format")
        }

        val model = settingsStore.claudeModel.first()
        val glassesEnabled = settingsStore.glassesEnabled.first()

        val systemPrompt = if (glassesEnabled) {
            VesperPrompts.SYSTEM_PROMPT + "\n\n" + VesperPrompts.SMARTGLASSES_ADDENDUM
        } else {
            VesperPrompts.SYSTEM_PROMPT
        }

        val tool = if (glassesEnabled) executeCommandTool else executeCommandToolWithoutGlasses()
        val anthropicMessages = buildAnthropicMessages(messages)

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", TOOL_CALL_MAX_TOKENS)
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", systemPrompt)
                    put("cache_control", buildJsonObject { put("type", "ephemeral") })
                })
            }
            putJsonArray("tools") {
                add(JsonObject(tool.toMutableMap().also {
                    it["cache_control"] = buildJsonObject { put("type", "ephemeral") }
                }))
            }
            put("tool_choice", buildJsonObject { put("type", "auto") })
            put("messages", JsonArray(anthropicMessages))
        }

        executeWithRetry(apiKey, requestBody)
    }

    override fun chatStream(messages: List<ChatMessage>, sessionId: String): Flow<ChatStreamEvent> = flow {
        if (!rateLimiter.tryAcquire()) {
            emit(ChatStreamEvent.StreamError("Rate limit exceeded"))
            return@flow
        }
        val apiKey = settingsStore.claudeApiKey.first() ?: run {
            emit(ChatStreamEvent.StreamError("Anthropic API key not configured"))
            return@flow
        }
        if (!isValidClaudeApiKey(apiKey)) {
            emit(ChatStreamEvent.StreamError("Invalid Anthropic API key format"))
            return@flow
        }

        val model = settingsStore.claudeModel.first()
        val glassesEnabled = settingsStore.glassesEnabled.first()
        val systemPrompt = if (glassesEnabled) {
            VesperPrompts.SYSTEM_PROMPT + "\n\n" + VesperPrompts.SMARTGLASSES_ADDENDUM
        } else {
            VesperPrompts.SYSTEM_PROMPT
        }
        val tool = if (glassesEnabled) executeCommandTool else executeCommandToolWithoutGlasses()
        val anthropicMessages = buildAnthropicMessages(messages)

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", TOOL_CALL_MAX_TOKENS)
            put("stream", true)
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", systemPrompt)
                    put("cache_control", buildJsonObject { put("type", "ephemeral") })
                })
            }
            putJsonArray("tools") {
                add(JsonObject(tool.toMutableMap().also {
                    it["cache_control"] = buildJsonObject { put("type", "ephemeral") }
                }))
            }
            put("tool_choice", buildJsonObject { put("type", "auto") })
            put("messages", JsonArray(anthropicMessages))
        }

        val body = json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(CLAUDE_API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("anthropic-beta", ANTHROPIC_BETA_CACHE)
            .addHeader("content-type", "application/json")
            .addHeader("accept", "text/event-stream")
            .post(body)
            .build()

        var streamCompleted = false
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = runCatching { response.body?.string() }.getOrNull() ?: "unknown error"
                    emit(ChatStreamEvent.StreamError("Anthropic API error ${response.code}: ${errBody.take(200)}"))
                    return@use
                }
                val reader = response.body?.charStream()?.buffered() ?: run {
                    emit(ChatStreamEvent.StreamError("Empty response body"))
                    return@use
                }

                val activeBlocks = mutableMapOf<Int, SseBlock>()
                var streamModel = model
                var inputTokens = 0
                var outputTokens = 0

                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data.isNotBlank()) {
                            val obj = runCatching {
                                json.parseToJsonElement(data).jsonObject
                            }.getOrNull()
                            if (obj != null) {
                                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                                    "message_start" -> {
                                        val msg = obj["message"]?.jsonObject
                                        streamModel = msg?.get("model")?.jsonPrimitive?.contentOrNull ?: model
                                        val usage = msg?.get("usage")?.jsonObject
                                        inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                        val cacheRead = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                        val cacheCreation = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                        if (cacheRead > 0 || cacheCreation > 0) {
                                            Log.d(TAG, "Stream cache: read=$cacheRead created=$cacheCreation")
                                        }
                                    }
                                    "content_block_start" -> {
                                        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: -1
                                        val block = obj["content_block"]?.jsonObject
                                        val blockType = block?.get("type")?.jsonPrimitive?.contentOrNull
                                        if (index >= 0 && blockType != null) {
                                            activeBlocks[index] = SseBlock(
                                                type = blockType,
                                                id = block?.get("id")?.jsonPrimitive?.contentOrNull ?: "",
                                                name = block?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                                            )
                                        }
                                    }
                                    "content_block_delta" -> {
                                        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: -1
                                        val delta = obj["delta"]?.jsonObject
                                        when (delta?.get("type")?.jsonPrimitive?.contentOrNull) {
                                            "text_delta" -> {
                                                val text = delta?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                                                if (text.isNotEmpty()) emit(ChatStreamEvent.TextDelta(text))
                                            }
                                            "input_json_delta" -> {
                                                val partial = delta?.get("partial_json")?.jsonPrimitive?.contentOrNull ?: ""
                                                activeBlocks[index]?.inputJson?.append(partial)
                                            }
                                        }
                                    }
                                    "content_block_stop" -> {
                                        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: -1
                                        val block = activeBlocks.remove(index)
                                        if (block?.type == "tool_use" && block.id.isNotBlank() && block.name.isNotBlank()) {
                                            emit(ChatStreamEvent.ToolCallComplete(
                                                ToolCall(id = block.id, name = block.name, arguments = block.inputJson.toString())
                                            ))
                                        }
                                    }
                                    "message_delta" -> {
                                        outputTokens = obj["usage"]?.jsonObject
                                            ?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: outputTokens
                                    }
                                    "message_stop" -> {
                                        streamCompleted = true
                                        emit(ChatStreamEvent.Done(
                                            model = streamModel,
                                            inputTokens = inputTokens,
                                            outputTokens = outputTokens
                                        ))
                                    }
                                }
                            }
                        }
                    }
                    line = reader.readLine()
                }

                if (!streamCompleted) {
                    streamCompleted = true
                    emit(ChatStreamEvent.Done(model = streamModel, inputTokens = inputTokens, outputTokens = outputTokens))
                }
            }
        } catch (e: java.io.IOException) {
            if (!streamCompleted) emit(ChatStreamEvent.StreamError("Network error: ${e.message}"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!streamCompleted) emit(ChatStreamEvent.StreamError("Streaming error: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    // Claude natively handles images — return messages unchanged so chat() receives them.
    override suspend fun preprocessImagesAsText(
        messages: List<ChatMessage>,
        apiKey: String
    ): List<ChatMessage> = messages

    override suspend fun chatSimple(prompt: String): String? = withContext(Dispatchers.IO) {
        if (!rateLimiter.tryAcquire()) return@withContext null
        val apiKey = settingsStore.claudeApiKey.first() ?: return@withContext null
        if (!isValidClaudeApiKey(apiKey)) return@withContext null

        val model = settingsStore.claudeModel.first()
        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", SIMPLE_MAX_TOKENS)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            }
        }
        when (val result = executeWithRetry(apiKey, requestBody)) {
            is ChatCompletionResult.Success -> result.content.takeIf { it.isNotBlank() }
            is ChatCompletionResult.Error -> null
        }
    }

    override suspend fun sendMessage(
        message: String,
        conversationHistory: List<ChatMessage>,
        customSystemPrompt: String?
    ): Result<String> {
        val messages = conversationHistory + ChatMessage(role = MessageRole.USER, content = message)
        return sendMessagesWithoutTools(messages, customSystemPrompt)
    }

    override suspend fun sendMessagesWithoutTools(
        messages: List<ChatMessage>,
        customSystemPrompt: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!rateLimiter.tryAcquire()) {
            return@withContext Result.failure(Exception("Rate limit exceeded"))
        }
        val apiKey = settingsStore.claudeApiKey.first()
            ?: return@withContext Result.failure(Exception("Anthropic API key not configured"))
        if (!isValidClaudeApiKey(apiKey)) {
            return@withContext Result.failure(Exception("Invalid Anthropic API key format"))
        }

        val model = settingsStore.claudeModel.first()
        val system = customSystemPrompt ?: VesperPrompts.SYSTEM_PROMPT
        val anthropicMessages = buildAnthropicMessages(messages)

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", DEFAULT_MAX_TOKENS)
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", system)
                    put("cache_control", buildJsonObject { put("type", "ephemeral") })
                })
            }
            put("messages", JsonArray(anthropicMessages))
        }

        when (val result = executeWithRetry(apiKey, requestBody)) {
            is ChatCompletionResult.Success -> Result.success(result.content)
            is ChatCompletionResult.Error -> Result.failure(Exception(result.message))
        }
    }

    // Delegate JSON-parsing utilities to OpenRouterClient — they're provider-agnostic.
    override fun parseCommandDetailed(arguments: String): OpenRouterClient.ParsedCommand =
        openRouterClient.parseCommandDetailed(arguments)

    override fun formatResult(result: CommandResult): String =
        openRouterClient.formatResult(result)

    override suspend fun describeImageForAgent(
        attachment: ImageAttachment,
        prompt: String
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = settingsStore.claudeApiKey.first() ?: run {
            Log.w(TAG, "describeImageForAgent: no Claude API key configured")
            return@withContext null
        }
        val model = settingsStore.claudeModel.first()

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", 500)
            put("system", VISION_SYSTEM_PROMPT)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", attachment.mimeType)
                                put("data", attachment.base64Data)
                            })
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                    }
                })
            }
        }

        when (val result = executeWithRetry(apiKey, requestBody)) {
            is ChatCompletionResult.Success -> result.content.takeIf { it.isNotBlank() }
            is ChatCompletionResult.Error -> {
                Log.w(TAG, "describeImageForAgent failed: ${result.message}")
                null
            }
        }
    }

    // ── Message conversion ────────────────────────────────────────────────────

    /**
     * Convert domain [ChatMessage] list to Anthropic Messages API format.
     *
     * Anthropic requires:
     *  - Messages must alternate user/assistant
     *  - Tool results are sent as user messages with tool_result content blocks
     *  - Tool calls in assistant turns use tool_use content blocks
     *  - No system messages in the array (system is a top-level field)
     */
    private fun buildAnthropicMessages(messages: List<ChatMessage>): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val openToolCallIds = linkedSetOf<String>()

        fun dropUnresolvedAssistantMessages() {
            if (openToolCallIds.isEmpty()) return
            // Remove assistant messages with unresolved tool calls from the end
            while (result.isNotEmpty()) {
                val last = result.last()
                val role = last["role"]?.jsonPrimitive?.contentOrNull
                val content = last["content"]
                if (role == "assistant" && content is JsonArray &&
                    content.any { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
                ) {
                    result.removeLast()
                    break
                } else break
            }
            openToolCallIds.clear()
        }

        messages.forEach { msg ->
            when (msg.role) {
                MessageRole.SYSTEM -> Unit // handled as top-level field

                MessageRole.USER -> {
                    if (openToolCallIds.isNotEmpty()) dropUnresolvedAssistantMessages()

                    val contentParts = mutableListOf<JsonObject>()
                    msg.imageAttachments?.forEach { img ->
                        contentParts.add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", img.mimeType)
                                put("data", img.base64Data)
                            })
                        })
                    }
                    if (msg.content.isNotBlank()) {
                        contentParts.add(buildJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }

                    if (contentParts.size == 1 && contentParts[0]["type"]?.jsonPrimitive?.contentOrNull == "text") {
                        result.add(buildJsonObject {
                            put("role", "user")
                            put("content", msg.content)
                        })
                    } else if (contentParts.isNotEmpty()) {
                        result.add(buildJsonObject {
                            put("role", "user")
                            put("content", JsonArray(contentParts))
                        })
                    }
                }

                MessageRole.ASSISTANT -> {
                    val toolCalls = msg.toolCalls?.filter { it.id.isNotBlank() && it.name.isNotBlank() }
                    if (!toolCalls.isNullOrEmpty()) {
                        if (openToolCallIds.isNotEmpty()) dropUnresolvedAssistantMessages()
                        val contentParts = mutableListOf<JsonObject>()
                        if (msg.content.isNotBlank()) {
                            contentParts.add(buildJsonObject {
                                put("type", "text")
                                put("text", msg.content)
                            })
                        }
                        toolCalls.forEach { tc ->
                            // Parse arguments string back to JsonObject for Anthropic's `input` field
                            val inputJson = runCatching {
                                json.parseToJsonElement(tc.arguments) as? JsonObject
                            }.getOrNull() ?: JsonObject(emptyMap())

                            contentParts.add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", tc.id)
                                put("name", tc.name)
                                put("input", inputJson)
                            })
                        }
                        result.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", JsonArray(contentParts))
                        })
                        openToolCallIds.addAll(toolCalls.map { it.id })
                    } else if (msg.content.isNotBlank()) {
                        if (openToolCallIds.isNotEmpty()) dropUnresolvedAssistantMessages()
                        result.add(buildJsonObject {
                            put("role", "assistant")
                            put("content", msg.content)
                        })
                    }
                }

                MessageRole.TOOL -> {
                    val validResults = msg.toolResults
                        .orEmpty()
                        .filter { it.toolCallId.isNotBlank() && openToolCallIds.contains(it.toolCallId) }

                    if (validResults.isNotEmpty()) {
                        val toolResultParts = validResults.map { tr ->
                            buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", tr.toolCallId)
                                put("content", tr.content)
                                if (!tr.success) put("is_error", true)
                            }
                        }
                        result.add(buildJsonObject {
                            put("role", "user")
                            put("content", JsonArray(toolResultParts))
                        })
                        validResults.forEach { openToolCallIds.remove(it.toolCallId) }
                    }
                }
            }
        }

        if (openToolCallIds.isNotEmpty()) dropUnresolvedAssistantMessages()

        // Anthropic requires the first message to be from the user
        if (result.isNotEmpty() && result.first()["role"]?.jsonPrimitive?.contentOrNull != "user") {
            result.removeFirst()
        }

        return result
    }

    // ── HTTP execution ────────────────────────────────────────────────────────

    private suspend fun executeWithRetry(
        apiKey: String,
        requestBody: JsonObject
    ): ChatCompletionResult {
        val body = json.encodeToString(requestBody)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(CLAUDE_API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("anthropic-beta", ANTHROPIC_BETA_CACHE)
            .addHeader("content-type", "application/json")
            .post(body)
            .build()

        var lastError: Exception? = null
        var delayMs = INITIAL_RETRY_DELAY_MS

        repeat(MAX_RETRIES) { attempt ->
            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()

                    if (response.code == 429) {
                        val retryAfter = response.header("retry-after")?.toLongOrNull() ?: 60
                        delay(retryAfter * 1000)
                        return@repeat
                    }

                    if (response.code in 500..599) {
                        lastError = IOException("Server error: ${response.code}")
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                        return@repeat
                    }

                    if (!response.isSuccessful) {
                        val errBody = responseBody ?: "unknown error"
                        Log.e(TAG, "Claude API error ${response.code}: $errBody")
                        return parseErrorBody(response.code, errBody)
                    }

                    if (responseBody == null) {
                        return ChatCompletionResult.Error("Empty response from Anthropic API")
                    }

                    return parseClaudeResponse(responseBody)
                }
            } catch (e: SocketTimeoutException) {
                lastError = e
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            } catch (e: IOException) {
                lastError = e
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            } catch (e: Exception) {
                return ChatCompletionResult.Error("Request failed: ${e.message}")
            }
        }

        return ChatCompletionResult.Error(
            "Request failed after $MAX_RETRIES attempts: ${lastError?.message}"
        )
    }

    private fun parseErrorBody(code: Int, body: String): ChatCompletionResult.Error {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val errType = root["error"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
            val errMsg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: body.take(200)
            ChatCompletionResult.Error("Anthropic API error $code ($errType): $errMsg")
        } catch (_: Exception) {
            ChatCompletionResult.Error("Anthropic API error $code: ${body.take(200)}")
        }
    }

    private fun parseClaudeResponse(responseBody: String): ChatCompletionResult {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject

            val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull
            val model = root["model"]?.jsonPrimitive?.contentOrNull ?: ""
            val usage = root["usage"]?.jsonObject
            val inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val cacheRead = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val cacheCreation = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
            if (cacheRead > 0 || cacheCreation > 0) {
                Log.d(TAG, "Prompt cache: read=$cacheRead created=$cacheCreation input=$inputTokens output=$outputTokens")
            }

            val contentArray = root["content"]?.jsonArray
                ?: return ChatCompletionResult.Error("No content in Claude response")

            var textContent = ""
            val toolCalls = mutableListOf<ToolCall>()

            contentArray.forEach { block ->
                val obj = block.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> {
                        textContent += obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    "tool_use" -> {
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        // Anthropic sends `input` as a JsonObject; convert to JSON string
                        val inputObj = obj["input"] ?: JsonObject(emptyMap())
                        val arguments = json.encodeToString(inputObj)

                        if (id.isNotBlank() && name.isNotBlank()) {
                            toolCalls.add(ToolCall(id = id, name = name, arguments = arguments))
                        } else {
                            Log.w(TAG, "Dropping malformed tool_use block: id='$id' name='$name'")
                        }
                    }
                }
            }

            ChatCompletionResult.Success(
                content = textContent,
                toolCalls = toolCalls.takeIf { it.isNotEmpty() },
                model = model,
                tokensUsed = inputTokens + outputTokens
            )
        } catch (e: Exception) {
            ChatCompletionResult.Error("Failed to parse Claude response: ${e.message}")
        }
    }

    private data class SseBlock(
        val type: String,
        val id: String = "",
        val name: String = "",
        val inputJson: StringBuilder = StringBuilder()
    )

    private fun isValidClaudeApiKey(key: String): Boolean {
        // Claude keys look like: sk-ant-api03-...  or  sk-ant-...
        return key.length in 10..500 && key.matches(Regex("^[a-zA-Z0-9_.\\-:]+\$"))
    }

    companion object {
        private const val TAG = "ClaudeClient"
        private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val ANTHROPIC_BETA_CACHE = "prompt-caching-2024-07-31"
        private const val MAX_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 700L
        private const val MAX_RETRY_DELAY_MS = 10_000L
        private const val TOOL_CALL_MAX_TOKENS = 1024
        private const val SIMPLE_MAX_TOKENS = 6144
        private const val DEFAULT_MAX_TOKENS = 720

        private const val VISION_SYSTEM_PROMPT =
            "You are a visual analysis assistant for a Flipper Zero companion app. " +
            "Describe what you see in the image in detail. Focus on: brand names, model numbers, " +
            "device types (TV, AC, car, remote control, gate, etc.), any visible text or labels, " +
            "and any details that would help identify the correct IR/RF/NFC protocol or signal. " +
            "Be specific and concise."

        val CLAUDE_MODELS = listOf(
            com.vesper.flipper.data.ModelInfo("claude-opus-4-7", "Claude Opus 4.7", "Most capable"),
            com.vesper.flipper.data.ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", "Balanced"),
            com.vesper.flipper.data.ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5", "Fastest")
        )
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-6"

        private val SUPPORTED_ACTIONS = listOf(
            "list_directory", "read_file", "write_file", "create_directory",
            "delete", "move", "rename", "copy", "get_device_info", "get_storage_info",
            "search_faphub", "install_faphub_app", "push_artifact", "execute_cli",
            "forge_payload", "search_resources", "list_vault", "run_runbook",
            "launch_app", "subghz_transmit", "ir_transmit", "nfc_emulate",
            "rfid_emulate", "ibutton_emulate", "badusb_execute", "ble_spam",
            "led_control", "vibro_control", "browse_repo", "download_resource",
            "github_search", "request_photo"
        )
    }
}

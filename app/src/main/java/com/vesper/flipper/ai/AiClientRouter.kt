package com.vesper.flipper.ai

import com.vesper.flipper.data.AiProvider
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.ChatMessage
import com.vesper.flipper.domain.model.CommandResult
import com.vesper.flipper.domain.model.ImageAttachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes AI calls to [OpenRouterClient] or [ClaudeClient] based on the user's
 * provider setting. Implement this as the single injection point everywhere
 * [OpenRouterClient] was previously injected directly.
 *
 * [parseCommandDetailed] and [formatResult] are provider-agnostic JSON utilities
 * and always delegate to [OpenRouterClient].
 */
@Singleton
class AiClientRouter @Inject constructor(
    private val openRouterClient: OpenRouterClient,
    private val claudeClient: ClaudeClient,
    private val settingsStore: SettingsStore
) : AiClient {

    private suspend fun active(): AiClient = when (settingsStore.aiProvider.first()) {
        AiProvider.CLAUDE -> claudeClient
        AiProvider.OPENROUTER -> openRouterClient
    }

    override suspend fun chat(messages: List<ChatMessage>, sessionId: String) =
        active().chat(messages, sessionId)

    override fun chatStream(messages: List<ChatMessage>, sessionId: String): Flow<ChatStreamEvent> = flow {
        emitAll(active().chatStream(messages, sessionId))
    }

    override suspend fun preprocessImagesAsText(messages: List<ChatMessage>, apiKey: String) =
        active().preprocessImagesAsText(messages, apiKey)

    override suspend fun chatSimple(prompt: String) =
        active().chatSimple(prompt)

    override suspend fun sendMessage(
        message: String,
        conversationHistory: List<ChatMessage>,
        customSystemPrompt: String?
    ) = active().sendMessage(message, conversationHistory, customSystemPrompt)

    override suspend fun sendMessagesWithoutTools(
        messages: List<ChatMessage>,
        customSystemPrompt: String?
    ) = active().sendMessagesWithoutTools(messages, customSystemPrompt)

    override fun parseCommandDetailed(arguments: String): OpenRouterClient.ParsedCommand =
        openRouterClient.parseCommandDetailed(arguments)

    override fun formatResult(result: CommandResult): String =
        openRouterClient.formatResult(result)

    override suspend fun describeImageForAgent(attachment: ImageAttachment, prompt: String) =
        active().describeImageForAgent(attachment, prompt)
}

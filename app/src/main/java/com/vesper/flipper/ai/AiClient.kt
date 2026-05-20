package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.ChatMessage
import com.vesper.flipper.domain.model.CommandResult
import com.vesper.flipper.domain.model.ImageAttachment

/**
 * Common interface for AI provider clients (OpenRouter, Claude/Anthropic, etc.).
 * All methods mirror the public surface of [OpenRouterClient].
 */
interface AiClient {
    suspend fun chat(messages: List<ChatMessage>, sessionId: String): ChatCompletionResult
    suspend fun preprocessImagesAsText(messages: List<ChatMessage>, apiKey: String): List<ChatMessage>
    suspend fun chatSimple(prompt: String): String?
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        customSystemPrompt: String? = null
    ): Result<String>
    suspend fun sendMessagesWithoutTools(
        messages: List<ChatMessage>,
        customSystemPrompt: String? = null
    ): Result<String>
    fun parseCommandDetailed(arguments: String): OpenRouterClient.ParsedCommand
    fun formatResult(result: CommandResult): String
    suspend fun describeImageForAgent(attachment: ImageAttachment, prompt: String): String?
}

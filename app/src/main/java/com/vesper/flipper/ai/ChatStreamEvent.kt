package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.ToolCall

sealed class ChatStreamEvent {
    data class TextDelta(val text: String) : ChatStreamEvent()
    data class ToolCallComplete(val toolCall: ToolCall) : ChatStreamEvent()
    data class StreamError(val message: String) : ChatStreamEvent()
    data class Done(
        val model: String = "",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0
    ) : ChatStreamEvent()
}

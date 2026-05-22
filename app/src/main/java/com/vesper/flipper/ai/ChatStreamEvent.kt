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
    /** Emitted when a thinking content block starts (adaptive thinking is active). */
    object ThinkingStarted : ChatStreamEvent()
    /** Emitted when the thinking block closes and visible text is about to begin. */
    object ThinkingDone : ChatStreamEvent()
}

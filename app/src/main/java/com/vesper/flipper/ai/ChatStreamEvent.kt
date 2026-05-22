package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.ToolCall

sealed class ChatStreamEvent {
    data class TextDelta(val text: String) : ChatStreamEvent()
    data class ToolCallComplete(val toolCall: ToolCall) : ChatStreamEvent()
    data class StreamError(val message: String) : ChatStreamEvent()
    data class Done(
        val model: String = "",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        /** JSON array string of all content blocks from the assistant turn (text + tool_use +
         *  compaction). Must be preserved in assistant message metadata and sent back verbatim
         *  on subsequent requests so the API can reconstruct compacted history. */
        val rawContentBlocksJson: String = "[]"
    ) : ChatStreamEvent()
    /** Emitted when a thinking content block starts (adaptive thinking is active). */
    object ThinkingStarted : ChatStreamEvent()
    /** Emitted when the thinking block closes and visible text is about to begin. */
    object ThinkingDone : ChatStreamEvent()
}

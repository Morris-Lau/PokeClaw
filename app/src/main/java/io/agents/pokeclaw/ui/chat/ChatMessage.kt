// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolSteps: List<ToolStep>? = null,
    val modelName: String? = null,
    val id: String = newId(),
    val isStreaming: Boolean = false
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL_GROUP }

    companion object {
        fun newId(): String = "msg_${System.currentTimeMillis()}_${ID_COUNTER.getAndIncrement()}"

        private val ID_COUNTER = java.util.concurrent.atomic.AtomicLong(0)
    }
}

data class ToolStep(
    val toolName: String,
    val summary: String,
    val success: Boolean = false
)

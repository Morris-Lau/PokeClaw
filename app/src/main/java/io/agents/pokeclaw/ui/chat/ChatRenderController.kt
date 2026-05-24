// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.os.Handler
import android.os.Looper

sealed class ChatRenderEvent {
    data class UserMessage(val text: String) : ChatRenderEvent()
    data class SystemMessage(val text: String) : ChatRenderEvent()
    data class AssistantStreamStarted(
        val streamId: String,
        val modelName: String? = null,
        val initialText: String = ""
    ) : ChatRenderEvent()
    data class AssistantStreamDelta(
        val streamId: String,
        val text: String,
        val isSnapshot: Boolean = false
    ) : ChatRenderEvent()
    data class AssistantStreamCompleted(
        val streamId: String,
        val finalText: String? = null,
        val modelName: String? = null
    ) : ChatRenderEvent()
    data class AssistantStreamFailed(
        val streamId: String,
        val error: String,
        val alreadyFormatted: Boolean = false
    ) : ChatRenderEvent()
    data class AssistantStreamCancelled(
        val streamId: String,
        val removeIfEmpty: Boolean = true,
        val removeAlways: Boolean = false
    ) : ChatRenderEvent()
}

class ChatRenderController(
    private val messages: MutableList<ChatMessage>,
    private val source: String = "chat"
) {

    fun render(event: ChatRenderEvent) {
        when (event) {
            is ChatRenderEvent.UserMessage -> addUser(event.text)
            is ChatRenderEvent.SystemMessage -> addSystem(event.text)
            is ChatRenderEvent.AssistantStreamStarted -> startAssistantStream(event)
            is ChatRenderEvent.AssistantStreamDelta -> appendAssistantStream(event)
            is ChatRenderEvent.AssistantStreamCompleted -> completeAssistantStream(event)
            is ChatRenderEvent.AssistantStreamFailed -> failAssistantStream(event)
            is ChatRenderEvent.AssistantStreamCancelled -> cancelAssistantStream(event)
        }
    }

    private fun addUser(text: String) {
        messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        val last = messages.lastOrNull()
        if (last?.role == ChatMessage.Role.SYSTEM && last.content.equals(text, ignoreCase = true)) {
            return
        }
        messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun startAssistantStream(event: ChatRenderEvent.AssistantStreamStarted) {
        val existing = activeStreamIndex(event.streamId)
        val message = ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            content = event.initialText,
            modelName = event.modelName,
            id = event.streamId,
            isStreaming = true
        )
        if (existing >= 0) {
            messages[existing] = message
        } else {
            messages.add(message)
        }
    }

    private fun appendAssistantStream(event: ChatRenderEvent.AssistantStreamDelta) {
        val idx = activeStreamIndex(event.streamId)
        if (idx < 0) {
            return
        }

        val current = messages[idx]
        val updatedText = if (event.isSnapshot) {
            event.text
        } else {
            current.content + event.text
        }
        messages[idx] = current.copy(content = updatedText)
    }

    private fun completeAssistantStream(event: ChatRenderEvent.AssistantStreamCompleted) {
        val idx = activeStreamIndex(event.streamId)
        if (idx < 0) {
            return
        }

        val current = messages[idx]
        val finalText = event.finalText ?: current.content
        messages[idx] = current.copy(
            content = finalText,
            modelName = event.modelName ?: current.modelName,
            isStreaming = false
        )
    }

    private fun failAssistantStream(event: ChatRenderEvent.AssistantStreamFailed) {
        val idx = activeStreamIndex(event.streamId)
        if (idx < 0) {
            return
        }

        val current = messages[idx]
        messages[idx] = current.copy(
            content = if (event.alreadyFormatted) event.error else "Error: ${event.error}",
            isStreaming = false
        )
    }

    private fun cancelAssistantStream(event: ChatRenderEvent.AssistantStreamCancelled) {
        val idx = activeStreamIndex(event.streamId)
        if (idx < 0) {
            return
        }

        val current = messages[idx]
        if (event.removeAlways || (event.removeIfEmpty && current.content.isBlank())) {
            messages.removeAt(idx)
        } else {
            messages[idx] = current.copy(isStreaming = false)
        }
    }

    private fun activeStreamIndex(streamId: String): Int {
        return messages.indexOfLast {
            it.role == ChatMessage.Role.ASSISTANT &&
                it.id == streamId &&
            it.isStreaming
        }
    }
}

class ChatRenderPacer(
    private val renderController: ChatRenderController,
    private val source: String,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val frameDelayMs: Long = 35L
) {

    companion object {
        private const val BASE_CHARS_PER_FRAME = 20
    }

    private data class StreamBuffer(
        val pending: StringBuilder = StringBuilder(),
        var flushScheduled: Boolean = false,
        var completion: ChatRenderEvent.AssistantStreamCompleted? = null
    )

    private val buffers = mutableMapOf<String, StreamBuffer>()

    fun render(event: ChatRenderEvent) {
        when (event) {
            is ChatRenderEvent.AssistantStreamStarted -> {
                buffers.remove(event.streamId)
                renderController.render(event)
            }
            is ChatRenderEvent.AssistantStreamDelta -> handleDelta(event)
            is ChatRenderEvent.AssistantStreamCompleted -> handleCompleted(event)
            is ChatRenderEvent.AssistantStreamFailed -> {
                buffers.remove(event.streamId)
                renderController.render(event)
            }
            is ChatRenderEvent.AssistantStreamCancelled -> {
                buffers.remove(event.streamId)
                renderController.render(event)
            }
            is ChatRenderEvent.UserMessage,
            is ChatRenderEvent.SystemMessage -> renderController.render(event)
        }
    }

    private fun handleDelta(event: ChatRenderEvent.AssistantStreamDelta) {
        if (event.text.isEmpty()) {
            renderController.render(event)
            return
        }

        if (event.isSnapshot) {
            buffers.remove(event.streamId)
            renderController.render(event)
            return
        }

        val buffer = buffers.getOrPut(event.streamId) { StreamBuffer() }
        buffer.pending.append(event.text)
        scheduleFlush(event.streamId, buffer, immediate = true)
    }

    private fun handleCompleted(event: ChatRenderEvent.AssistantStreamCompleted) {
        val buffer = buffers[event.streamId]
        if (buffer == null || buffer.pending.isEmpty()) {
            buffers.remove(event.streamId)
            renderController.render(event)
            return
        }

        buffer.completion = event
        scheduleFlush(event.streamId, buffer, immediate = true)
    }

    private fun scheduleFlush(streamId: String, buffer: StreamBuffer, immediate: Boolean = false) {
        if (buffer.flushScheduled) return
        buffer.flushScheduled = true
        val delay = if (immediate) 0L else frameDelayMs
        handler.postDelayed({ flush(streamId) }, delay)
    }

    private fun flush(streamId: String) {
        val buffer = buffers[streamId] ?: return
        buffer.flushScheduled = false

        if (buffer.pending.isNotEmpty()) {
            val chunkLen = nextChunkSize(buffer.pending.length)
            val chunk = buffer.pending.substring(0, chunkLen)
            buffer.pending.delete(0, chunkLen)
            renderController.render(
                ChatRenderEvent.AssistantStreamDelta(
                    streamId = streamId,
                    text = chunk
                )
            )
            scheduleFlush(streamId, buffer)
            return
        }

        val completion = buffer.completion
        if (completion != null) {
            buffers.remove(streamId)
            renderController.render(completion)
        } else {
            buffers.remove(streamId)
        }
    }

    private fun nextChunkSize(pendingLen: Int): Int {
        val chunk = when {
            pendingLen > 8_000 -> 120
            pendingLen > 4_000 -> 80
            pendingLen > 2_000 -> 48
            else -> BASE_CHARS_PER_FRAME
        }
        return chunk.coerceAtMost(pendingLen)
    }
}

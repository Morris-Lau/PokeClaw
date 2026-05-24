package io.agents.pokeclaw.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderControllerTest {

    @Test
    fun `stream deltas update one assistant bubble`() {
        val messages = mutableListOf<ChatMessage>()
        val controller = ChatRenderController(messages)

        controller.render(ChatRenderEvent.AssistantStreamStarted(streamId = "s1", modelName = "gpt-test"))
        controller.render(ChatRenderEvent.AssistantStreamDelta(streamId = "s1", text = "Hello"))
        controller.render(ChatRenderEvent.AssistantStreamDelta(streamId = "s1", text = ", world"))

        assertEquals(1, messages.size)
        assertEquals(ChatMessage.Role.ASSISTANT, messages[0].role)
        assertEquals("Hello, world", messages[0].content)
        assertEquals("gpt-test", messages[0].modelName)
        assertEquals("s1", messages[0].id)
        assertTrue(messages[0].isStreaming)
    }

    @Test
    fun `stream snapshots replace text instead of duplicating`() {
        val messages = mutableListOf<ChatMessage>()
        val controller = ChatRenderController(messages)

        controller.render(ChatRenderEvent.AssistantStreamStarted(streamId = "s1"))
        controller.render(ChatRenderEvent.AssistantStreamDelta(streamId = "s1", text = "Hel", isSnapshot = true))
        controller.render(ChatRenderEvent.AssistantStreamDelta(streamId = "s1", text = "Hello", isSnapshot = true))

        assertEquals("Hello", messages.single().content)
    }

    @Test
    fun `completion uses final text and clears streaming flag`() {
        val messages = mutableListOf<ChatMessage>()
        val controller = ChatRenderController(messages)

        controller.render(ChatRenderEvent.AssistantStreamStarted(streamId = "s1"))
        controller.render(ChatRenderEvent.AssistantStreamDelta(streamId = "s1", text = "partial"))
        controller.render(ChatRenderEvent.AssistantStreamCompleted(streamId = "s1", finalText = "final", modelName = "gpt-final"))

        assertEquals("final", messages.single().content)
        assertEquals("gpt-final", messages.single().modelName)
        assertFalse(messages.single().isStreaming)
    }

    @Test
    fun `failure replaces active stream with visible error`() {
        val messages = mutableListOf<ChatMessage>()
        val controller = ChatRenderController(messages)

        controller.render(ChatRenderEvent.AssistantStreamStarted(streamId = "s1"))
        controller.render(ChatRenderEvent.AssistantStreamDelta(streamId = "s1", text = "partial"))
        controller.render(ChatRenderEvent.AssistantStreamFailed(streamId = "s1", error = "network failed"))

        assertEquals("Error: network failed", messages.single().content)
        assertFalse(messages.single().isStreaming)
    }

    @Test
    fun `cancel removes an empty active stream and stale deltas are ignored`() {
        val messages = mutableListOf<ChatMessage>()
        val controller = ChatRenderController(messages)

        controller.render(ChatRenderEvent.AssistantStreamStarted(streamId = "s1"))
        controller.render(ChatRenderEvent.AssistantStreamCancelled(streamId = "s1"))
        controller.render(ChatRenderEvent.AssistantStreamDelta(streamId = "s1", text = "late"))

        assertTrue(messages.isEmpty())
    }
}

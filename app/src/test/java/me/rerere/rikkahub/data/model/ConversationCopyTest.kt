package me.rerere.rikkahub.data.model

import java.time.Instant
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConversationCopyTest {
    @Test
    fun `copy to assistant preserves message nodes and creates independent conversation identity`() {
        val sourceId = Uuid.random()
        val sourceAssistantId = Uuid.random()
        val targetAssistantId = Uuid.random()
        val copiedId = Uuid.random()
        val now = Instant.parse("2026-10-13T00:00:00Z")
        val messageNodes = listOf(MessageNode(messages = emptyList()))
        val source = Conversation.ofId(
            id = sourceId,
            assistantId = sourceAssistantId,
            messages = messageNodes,
        ).copy(
            rootId = Uuid.random(),
            branchNumber = 2,
            isPinned = true,
            isConsolidated = true,
        )

        val copied = source.copyToAssistant(
            targetAssistantId = targetAssistantId,
            newId = copiedId,
            now = now,
        )

        assertEquals(messageNodes, copied.messageNodes)
        assertEquals(targetAssistantId, copied.assistantId)
        assertEquals(copiedId, copied.id)
        assertEquals(copiedId, copied.rootId)
        assertEquals(null, copied.branchNumber)
        assertEquals(now, copied.createAt)
        assertEquals(now, copied.updateAt)
        assertFalse(copied.isPinned)
        assertFalse(copied.isConsolidated)
        assertNotEquals(source.id, copied.id)
        assertEquals(sourceId, source.id)
    }
}

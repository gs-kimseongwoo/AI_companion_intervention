package com.example.zetareader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JevEvaluationTest {
    @Test
    fun mapsAndroidRolesToBridgeSpeakers() {
        val snapshot = ConversationSnapshot(
            conversationId = "room:a",
            revision = "rev-1",
            updatedAtEpochMs = 1L,
            turns = listOf(
                DialogueTurn("u1", MessageRole.USER, "hello", false),
                DialogueTurn(null, MessageRole.ASSISTANT, "hi", true),
            ),
        )

        assertEquals(
            listOf(
                JevTurnPayload("human", "hello"),
                JevTurnPayload("assistant", "hi"),
            ),
            snapshot.toJevTurns(),
        )
    }

    @Test
    fun rejectsUnknownRole() {
        val snapshot = ConversationSnapshot(
            conversationId = "room:a",
            revision = "rev-1",
            updatedAtEpochMs = 1L,
            turns = listOf(DialogueTurn(null, MessageRole.UNKNOWN, "?", true)),
        )

        assertThrows(IllegalArgumentException::class.java) { snapshot.toJevTurns() }
    }
}

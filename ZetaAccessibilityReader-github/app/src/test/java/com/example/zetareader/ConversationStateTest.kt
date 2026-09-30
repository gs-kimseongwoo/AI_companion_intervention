package com.example.zetareader

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateTest {
    @Test
    fun repeatedPendingAssistantEditsReplaceOneTurn() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        state.mergeVisibleMessages(
            listOf(
                message("user-1", MessageRole.USER, "질문"),
                message(null, MessageRole.ASSISTANT, "첫 답변"),
            ),
            seenAtEpochMs = 110L,
        )
        val firstRevision = state.snapshot().revision

        state.mergeVisibleMessages(
            listOf(
                message("user-1", MessageRole.USER, "질문"),
                message(null, MessageRole.ASSISTANT, "여러 번 고친 최종 답변"),
            ),
            seenAtEpochMs = 120L,
        )

        val snapshot = state.snapshot()
        assertEquals(2, state.messages.size)
        assertEquals(2, snapshot.turns.size)
        assertTrue(snapshot.turns.last().isPending)
        assertTrue(snapshot.turns.last().text.contains("최종 답변"))
        assertFalse(snapshot.turns.last().text.contains("첫 답변"))
        assertNotEquals(firstRevision, snapshot.revision)
    }

    @Test
    fun assigningIdToPendingAssistantDoesNotChangeRevision() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        val pending = listOf(
            message("user-1", MessageRole.USER, "질문"),
            message(null, MessageRole.ASSISTANT, "수정된 최종 답변"),
        )
        state.mergeVisibleMessages(pending, seenAtEpochMs = 110L)
        val pendingRevision = state.snapshot().revision

        val finalized = listOf(
            message("user-1", MessageRole.USER, "질문"),
            message("assistant-1", MessageRole.ASSISTANT, "수정된 최종 답변"),
        )
        state.mergeVisibleMessages(finalized, seenAtEpochMs = 120L)

        val snapshot = state.snapshot()
        assertEquals(2, state.messages.size)
        assertEquals("assistant-1", snapshot.turns.last().messageId)
        assertFalse(snapshot.turns.last().isPending)
        assertEquals(pendingRevision, snapshot.revision)
    }

    @Test
    fun revisitingVisibleMessageDropsSegmentsThatNoLongerExist() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        state.mergeVisibleMessages(
            listOf(
                message("assistant-1", MessageRole.ASSISTANT, "대사 1", segmentIndex = 0),
                message("assistant-1", MessageRole.ASSISTANT, "사라질 서술", segmentIndex = 1),
                message("assistant-1", MessageRole.ASSISTANT, "대사 2", segmentIndex = 2),
            ),
            seenAtEpochMs = 110L,
        )

        state.mergeVisibleMessages(
            listOf(
                message("assistant-1", MessageRole.ASSISTANT, "대사 1", segmentIndex = 0),
                message("assistant-1", MessageRole.ASSISTANT, "대사 2", segmentIndex = 1),
            ),
            seenAtEpochMs = 120L,
        )

        assertEquals(2, state.messages.size)
        assertFalse(state.snapshot().turns.single().text.contains("사라질 서술"))
    }

    @Test
    fun registryKeepsRoomsIndependentAndRestoresPreviousRoom() {
        val registry = ConversationRegistry()
        val visibleA = listOf(message("a-user", MessageRole.USER, "A 질문"))
        val visibleB = listOf(message("b-user", MessageRole.USER, "B 질문"))

        val roomA = registry.activate("room:a", visibleA, nowEpochMs = 100L).state
        roomA.mergeVisibleMessages(visibleA, seenAtEpochMs = 110L)
        val roomB = registry.activate("room:b", visibleB, nowEpochMs = 120L).state
        roomB.mergeVisibleMessages(visibleB, seenAtEpochMs = 130L)
        val roomAAgain = registry.activate("room:a", visibleA, nowEpochMs = 140L).state

        assertSame(roomA, roomAAgain)
        assertEquals(1, roomAAgain.messages.size)
        assertEquals("a-user", roomAAgain.messages.single().record.id)
        assertEquals("b-user", roomB.messages.single().record.id)
        assertEquals(2, registry.allStates().size)
    }

    @Test
    fun messageOverlapFindsRoomAndStableSelectionReplacesFallbackKey() {
        val registry = ConversationRegistry()
        val visible = listOf(message("shared-message", MessageRole.USER, "질문"))
        val fallback = registry.activate(null, visible, nowEpochMs = 100L).state
        fallback.mergeVisibleMessages(visible, seenAtEpochMs = 110L)

        val matched = registry.activate(null, visible, nowEpochMs = 120L)
        assertSame(fallback, matched.state)

        val promoted = registry.activate("room:stable", visible, nowEpochMs = 130L)
        assertSame(fallback, promoted.state)
        assertEquals("room:stable", promoted.state.conversationId)
        assertEquals("messages:shared-message", promoted.replacedConversationId)
    }

    @Test
    fun persistedConversationRoundTripsWithCurrentRevision() {
        val directory = Files.createTempDirectory("zeta-conversation-test").toFile()
        try {
            val state = ConversationState("room:persisted", createdAtEpochMs = 100L)
            state.mergeVisibleMessages(
                listOf(
                    message("user-1", MessageRole.USER, "질문"),
                    message(null, MessageRole.ASSISTANT, "최신 답변"),
                ),
                seenAtEpochMs = 120L,
            )
            val expected = state.snapshot()

            ConversationStatePersistence.save(directory, state)
            val restored = ConversationStatePersistence.loadAll(directory).single()
            val actual = restored.snapshot()

            assertEquals(expected.conversationId, actual.conversationId)
            assertEquals(expected.revision, actual.revision)
            assertEquals(expected.turns, actual.turns)
            assertEquals(120L, actual.updatedAtEpochMs)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun message(
        id: String?,
        role: MessageRole,
        text: String,
        segmentIndex: Int = 0,
    ): MessageRecord = MessageRecord(
        id = id,
        role = role,
        speaker = if (role == MessageRole.USER) "나" else "코토하",
        text = text,
        segmentIndex = segmentIndex,
    )
}

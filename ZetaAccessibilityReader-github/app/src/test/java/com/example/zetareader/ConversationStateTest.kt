package com.example.zetareader

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateTest {
    @Test
    fun userResponseStartsScenarioAndNextUserResponseStartsAnother() {
        assertEquals(
            listOf(null, null, null, 0, 0, 0, 1),
            scenarioIndexesForRoles(
                listOf(
                    MessageRole.ASSISTANT,
                    MessageRole.ASSISTANT,
                    MessageRole.ASSISTANT,
                    MessageRole.USER,
                    MessageRole.ASSISTANT,
                    MessageRole.ASSISTANT,
                    MessageRole.USER,
                ),
            ),
        )
    }

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
        assertEquals(110L, state.updatedAtEpochMs)
    }

    @Test
    fun evaluationSnapshotExcludesEverythingBeforeNewUserResponse() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        state.mergeVisibleMessages(
            listOf(
                message("old-user", MessageRole.USER, "어제 질문"),
                message("old-ai", MessageRole.ASSISTANT, "어제 답변"),
                message("new-user", MessageRole.USER, "오늘 질문"),
                message(null, MessageRole.ASSISTANT, "오늘 답변"),
            ),
            seenAtEpochMs = 120L,
        )

        val snapshot = state.evaluationSnapshot("new-user")!!

        assertEquals(2, snapshot.turns.size)
        assertEquals("오늘 질문", snapshot.turns.first().text.substringAfter("]\n"))
        assertTrue(snapshot.turns.last().text.contains("오늘 답변"))
        assertFalse(snapshot.turns.any { it.text.contains("어제") })
        assertTrue(snapshot.isReadyForEvaluation)
        assertNull(state.evaluationSnapshot("missing-user"))
    }

    @Test
    fun onlyNewStableUserIdStartsJevSession() {
        val visible = listOf(
            message("old-user", MessageRole.USER, "기존 질문"),
            message("old-ai", MessageRole.ASSISTANT, "기존 답변"),
            message("new-user", MessageRole.USER, "새 질문"),
        )

        assertEquals(
            "new-user",
            firstNewUserMessageId(visible, setOf("old-user", "old-ai")),
        )
        assertNull(firstNewUserMessageId(visible, visible.mapNotNull { it.id }.toSet()))
    }

    @Test
    fun jevContinuesWhileContentChangesWithinThreeHours() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        state.mergeVisibleMessages(
            listOf(message("user-1", MessageRole.USER, "첫 질문")),
            seenAtEpochMs = 1_000L,
        )
        state.jevStartMessageId = "user-1"

        assertFalse(
            state.resetJevAfterContentInactivity(
                1_000L + JEV_INACTIVITY_TIMEOUT_MS - 1L,
            ),
        )
        assertEquals("user-1", state.jevStartMessageId)

        state.mergeVisibleMessages(
            listOf(
                message("user-1", MessageRole.USER, "첫 질문"),
                message("ai-1", MessageRole.ASSISTANT, "두 시간 뒤 답변"),
            ),
            seenAtEpochMs = 1_000L + 2 * 60 * 60 * 1_000L,
        )
        assertFalse(
            state.resetJevAfterContentInactivity(
                state.updatedAtEpochMs + JEV_INACTIVITY_TIMEOUT_MS - 1L,
            ),
        )
        assertEquals("user-1", state.jevStartMessageId)
        assertEquals(2, state.evaluationSnapshot("user-1")!!.turns.size)

        assertTrue(
            state.resetJevAfterContentInactivity(
                state.updatedAtEpochMs + JEV_INACTIVITY_TIMEOUT_MS,
            ),
        )
        assertNull(state.jevStartMessageId)
    }

    @Test
    fun openingReaderDoesNotMoveTheContentInactivityClock() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        state.mergeVisibleMessages(
            listOf(message("user-1", MessageRole.USER, "질문")),
            seenAtEpochMs = 1_000L,
        )
        state.jevStartMessageId = "user-1"
        state.markAccessed(1_000L + JEV_INACTIVITY_TIMEOUT_MS - 1L)

        assertEquals(1_000L, state.updatedAtEpochMs)
        assertTrue(
            state.resetJevAfterContentInactivity(
                1_000L + JEV_INACTIVITY_TIMEOUT_MS,
            ),
        )
        assertNull(state.jevStartMessageId)
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
    fun newFlowFromEarlierMessageDeletesOldTail() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        state.mergeVisibleMessages(
            listOf(
                message("user-1", MessageRole.USER, "첫 질문"),
                message("ai-1", MessageRole.ASSISTANT, "첫 답변"),
                message("user-old", MessageRole.USER, "사라질 질문"),
                message("ai-old", MessageRole.ASSISTANT, "사라질 답변"),
            ),
            seenAtEpochMs = 110L,
        )

        val merge = state.mergeVisibleMessages(
            listOf(
                message("ai-1", MessageRole.ASSISTANT, "첫 답변"),
                message("user-new", MessageRole.USER, "새 흐름"),
                message(null, MessageRole.ASSISTANT, "새 답변"),
            ),
            seenAtEpochMs = 120L,
        )

        assertEquals("user-new", merge.replacementStartUserMessageId)
        assertEquals(
            listOf("user-1", "ai-1", "user-new", null),
            state.snapshot().turns.map { it.messageId },
        )
        assertFalse(state.snapshot().turns.any { it.text.contains("사라질") })
    }

    @Test
    fun editingEarlierUserMessageDeletesOldTail() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        state.mergeVisibleMessages(
            listOf(
                message("user-1", MessageRole.USER, "수정 전"),
                message("ai-old", MessageRole.ASSISTANT, "사라질 답변"),
                message("user-old", MessageRole.USER, "사라질 질문"),
            ),
            seenAtEpochMs = 110L,
        )

        val merge = state.mergeVisibleMessages(
            listOf(
                message("user-1", MessageRole.USER, "수정 후"),
                message(null, MessageRole.ASSISTANT, "새 답변"),
            ),
            seenAtEpochMs = 120L,
        )

        assertEquals("user-1", merge.replacementStartUserMessageId)
        assertEquals(listOf("user-1", null), state.snapshot().turns.map { it.messageId })
        assertFalse(state.snapshot().turns.any { it.text.contains("사라질") })
    }

    @Test
    fun scrollingToKnownOlderMessagesDoesNotDeleteTailOrChangeTime() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        val all = listOf(
            message("user-1", MessageRole.USER, "첫 질문"),
            message("ai-1", MessageRole.ASSISTANT, "첫 답변"),
            message("user-2", MessageRole.USER, "둘째 질문"),
            message("ai-2", MessageRole.ASSISTANT, "둘째 답변"),
        )
        state.mergeVisibleMessages(all, seenAtEpochMs = 110L)

        val merge = state.mergeVisibleMessages(all.take(2), seenAtEpochMs = 120L)

        assertFalse(merge.changed)
        assertEquals(110L, state.updatedAtEpochMs)
        assertEquals(4, state.snapshot().turns.size)
    }

    @Test
    fun messageMissingBetweenTwoVisibleAnchorsIsDeleted() {
        val state = ConversationState("room:a", createdAtEpochMs = 100L)
        state.mergeVisibleMessages(
            listOf(
                message("user-1", MessageRole.USER, "첫 질문"),
                message("ai-delete", MessageRole.ASSISTANT, "삭제할 답변"),
                message("user-2", MessageRole.USER, "둘째 질문"),
                message("ai-2", MessageRole.ASSISTANT, "둘째 답변"),
            ),
            seenAtEpochMs = 110L,
        )

        state.mergeVisibleMessages(
            listOf(
                message("user-1", MessageRole.USER, "첫 질문"),
                message("user-2", MessageRole.USER, "둘째 질문"),
                message("ai-2", MessageRole.ASSISTANT, "둘째 답변"),
            ),
            seenAtEpochMs = 120L,
        )

        assertEquals(
            listOf("user-1", "user-2", "ai-2"),
            state.snapshot().turns.map { it.messageId },
        )
        assertFalse(state.snapshot().turns.any { it.text.contains("삭제할") })
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
        assertTrue("messages:shared-message" in promoted.removedConversationIds)
    }

    @Test
    fun registryKeepsOnlyTenMostRecentRooms() {
        val registry = ConversationRegistry(maxConversations = 10)
        var lastActivation: ConversationActivation? = null
        repeat(11) { index ->
            val visible = listOf(message("user-$index", MessageRole.USER, "질문 $index"))
            val activation = registry.activate(
                preferredConversationId = "room:$index",
                visible = visible,
                nowEpochMs = 100L + index,
            )
            activation.state.mergeVisibleMessages(visible, 100L + index)
            lastActivation = activation
        }

        assertEquals(10, registry.allStates().size)
        assertFalse(registry.allStates().any { it.conversationId == "room:0" })
        assertTrue("room:0" in lastActivation!!.removedConversationIds)
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
            state.markAccessed(121L)
            state.jevStartMessageId = "user-1"
            val expected = state.snapshot()

            ConversationStatePersistence.save(directory, state)
            val restored = ConversationStatePersistence.loadAll(directory).single()
            val actual = restored.snapshot()

            assertEquals(expected.conversationId, actual.conversationId)
            assertEquals(expected.revision, actual.revision)
            assertEquals(expected.turns, actual.turns)
            assertEquals(120L, actual.updatedAtEpochMs)
            assertEquals("user-1", restored.jevStartMessageId)
            assertEquals(121L, restored.lastAccessedAtEpochMs)
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

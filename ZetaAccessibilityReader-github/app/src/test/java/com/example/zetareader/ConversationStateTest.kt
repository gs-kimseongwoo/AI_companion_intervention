package com.example.zetareader

import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
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
    fun jevSessionContinuesAcrossMidnightButResetsOnLaterNewDate() {
        val zone = ZoneId.of("Asia/Seoul")
        val state = ConversationState("room:a", createdAtEpochMs = epoch(2026, 9, 29, 23, zone))

        assertTrue(state.prepareJevSession(epoch(2026, 9, 29, 23, zone), zone))
        state.jevStartMessageId = "user-night"
        state.markVisitEnded(epoch(2026, 9, 30, 1, zone))

        assertFalse(state.prepareJevSession(epoch(2026, 9, 30, 2, zone), zone))
        assertEquals("user-night", state.jevStartMessageId)
        assertEquals("2026-09-30", state.jevSessionDate)

        state.markVisitEnded(epoch(2026, 9, 30, 3, zone))
        assertTrue(state.prepareJevSession(epoch(2026, 10, 1, 9, zone), zone))
        assertNull(state.jevStartMessageId)
        assertEquals("2026-10-01", state.jevSessionDate)
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
            state.prepareJevSession(121L, ZoneId.of("UTC"))
            state.jevStartMessageId = "user-1"
            state.markVisitEnded(130L)
            val expected = state.snapshot()

            ConversationStatePersistence.save(directory, state)
            val restored = ConversationStatePersistence.loadAll(directory).single()
            val actual = restored.snapshot()

            assertEquals(expected.conversationId, actual.conversationId)
            assertEquals(expected.revision, actual.revision)
            assertEquals(expected.turns, actual.turns)
            assertEquals(120L, actual.updatedAtEpochMs)
            assertEquals(state.jevSessionDate, restored.jevSessionDate)
            assertEquals("user-1", restored.jevStartMessageId)
            assertEquals(130L, restored.lastVisitEndedAtEpochMs)
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

    private fun epoch(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        zone: ZoneId,
    ): Long = ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone)
        .toInstant()
        .toEpochMilli()
}

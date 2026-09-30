package com.example.zetareader

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 대화방별 현재 상태와 제타가 전면에 있었던 방문 세션을 함께 보관한다.
 * 화면에서 사라진 메시지는 삭제하지 않고, A/B/C 방을 오가도 각 상태를 독립적으로 복원한다.
 */
object CaptureStore {
    const val ACTION_UPDATED = "com.example.zetareader.CAPTURE_UPDATED"

    private const val LATEST_FILE = "latest_capture.txt"
    private const val SESSIONS_DIRECTORY = "sessions"
    private const val CONVERSATIONS_DIRECTORY = "conversations"
    private const val ROOM_PREFIX = "room:"
    private const val ROOM_SELECTION_TTL_MS = 15_000L

    private data class SessionState(
        val startedAtEpochMs: Long,
        val file: File,
        val conversation: ConversationState,
        var lastCapture: CaptureResult? = null,
        var endedAtEpochMs: Long? = null,
    )

    private data class PendingConversation(
        val conversationId: String,
        val selectedAtEpochMs: Long,
    )

    /** 같은 제타 메시지 ID에 딸린 배경·인물 서술·대사를 하나의 응답으로 묶는다. */
    private data class ResponseGroup(
        val role: MessageRole,
        val messageId: String?,
        val messages: MutableList<StoredMessage> = mutableListOf(),
    )

    /** 제타의 AI 응답과 그 뒤에 이어지는 사용자 응답을 한 장면 단위로 묶는다. */
    private data class ScenarioGroup(
        val aiResponses: MutableList<ResponseGroup> = mutableListOf(),
        val userResponses: MutableList<ResponseGroup> = mutableListOf(),
        val unknownResponses: MutableList<ResponseGroup> = mutableListOf(),
    )

    private var activeSession: SessionState? = null
    private var registry: ConversationRegistry? = null
    private var pendingConversation: PendingConversation? = null

    @Volatile
    var latest: CaptureResult? = null
        private set

    @Volatile
    var latestFormatted: String? = null
        private set

    @Volatile
    var latestSnapshot: ConversationSnapshot? = null
        private set

    /** 대화방 목록의 room-list-item UUID를 다음 채팅 화면과 연결한다. */
    @Synchronized
    fun selectConversationRoom(roomId: String) {
        val normalized = roomId.trim().removePrefix(ROOM_PREFIX)
        if (normalized.isEmpty()) return
        pendingConversation = PendingConversation(
            conversationId = "$ROOM_PREFIX$normalized",
            selectedAtEpochMs = System.currentTimeMillis(),
        )
    }

    /** @return 이 호출에서 새 세션이 만들어졌으면 true. */
    @Synchronized
    fun beginSession(
        context: Context,
        visibleMessages: List<MessageRecord>,
    ): Boolean {
        val now = System.currentTimeMillis()
        val preferredConversationId = pendingConversation
            ?.takeIf { now - it.selectedAtEpochMs <= ROOM_SELECTION_TTL_MS }
            ?.conversationId

        activeSession?.let { active ->
            if (
                preferredConversationId == null ||
                active.conversation.conversationId == preferredConversationId
            ) {
                pendingConversation = null
                return false
            }
            finishActiveSession(context, active)
        }

        pendingConversation = null
        val conversationRegistry = ensureRegistry(context)
        val activation = conversationRegistry.activate(
            preferredConversationId = preferredConversationId,
            visible = visibleMessages,
            nowEpochMs = now,
        )
        activation.replacedConversationId?.let { previousId ->
            ConversationStatePersistence.delete(
                conversationsDirectory(context),
                previousId,
            )
        }

        val directory = File(context.filesDir, SESSIONS_DIRECTORY).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.KOREA)
            .format(Date(now))
        val state = SessionState(
            startedAtEpochMs = now,
            file = File(directory, "zeta_session_${stamp}.txt"),
            conversation = activation.state,
        )
        activeSession = state
        latest = null
        latestSnapshot = activation.state.snapshot()
        JevEvaluationCoordinator.submit(context, latestSnapshot!!)
        save(context, state)
        notifyUpdated(context)
        return true
    }

    @Synchronized
    fun publish(context: Context, capture: CaptureResult) {
        if (activeSession == null) beginSession(context, capture.messages)
        val state = activeSession ?: return

        state.conversation.mergeVisibleMessages(
            capture.messages,
            capture.capturedAtEpochMs,
        )
        val cumulativeCapture = capture.copy(
            messages = state.conversation.messageRecords(),
        )
        state.lastCapture = cumulativeCapture
        latest = cumulativeCapture
        latestSnapshot = state.conversation.snapshot()
        JevEvaluationCoordinator.submit(context, latestSnapshot!!)
        save(context, state)
        notifyUpdated(context)
    }

    /** @return 진행 중이던 세션을 이번 호출에서 종료했으면 true. */
    @Synchronized
    fun finishSession(context: Context): Boolean {
        val state = activeSession ?: return false
        finishActiveSession(context, state)
        return true
    }

    fun readLastFormatted(context: Context): String? =
        latestFormatted ?: runCatching {
            context.openFileInput(LATEST_FILE).bufferedReader().use { it.readText() }
        }.getOrNull()

    private fun save(context: Context, state: SessionState) {
        ConversationStatePersistence.save(
            conversationsDirectory(context),
            state.conversation,
        )
        val formatted = formatSession(state)
        state.file.writeText(formatted)
        File(context.filesDir, LATEST_FILE).writeText(formatted)
        latestFormatted = formatted
    }

    private fun notifyUpdated(context: Context) {
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }

    private fun finishActiveSession(context: Context, state: SessionState) {
        state.endedAtEpochMs = System.currentTimeMillis()
        save(context, state)
        if (activeSession === state) activeSession = null
        notifyUpdated(context)
    }

    private fun ensureRegistry(context: Context): ConversationRegistry {
        registry?.let { return it }
        return ConversationRegistry(
            ConversationStatePersistence.loadAll(conversationsDirectory(context)),
        ).also { registry = it }
    }

    private fun conversationsDirectory(context: Context): File =
        File(context.filesDir, CONVERSATIONS_DIRECTORY)

    private fun formatSession(state: SessionState): String = buildString {
        val messages = state.conversation.messages
        val snapshot = state.conversation.snapshot()
        val responses = groupResponses(messages)
        val scenarios = groupScenarios(responses)

        appendLine("제타 대화 세션")
        appendLine("상태: ${if (state.endedAtEpochMs == null) "기록 중" else "종료"}")
        appendLine("대화방: ${state.conversation.conversationId}")
        appendLine("리비전: ${snapshot.revision}")
        appendLine("시작: ${formatDate(state.startedAtEpochMs)}")
        state.endedAtEpochMs?.let { appendLine("종료: ${formatDate(it)}") }
        appendLine("저장 파일: $SESSIONS_DIRECTORY/${state.file.name}")
        appendLine("시나리오: ${scenarios.size}개")
        appendLine("응답: ${responses.size}개 · 세부 구간: ${messages.size}개")
        appendLine(
            "분석 준비: ${if (snapshot.isReadyForEvaluation) "완료" else "대기"} · " +
                "턴 ${snapshot.turns.size}개",
        )
        state.lastCapture?.let { capture ->
            appendLine("최근 갱신: ${formatDate(capture.capturedAtEpochMs)}")
            appendLine("최근 화면 분석: ${"%.2f".format(capture.scanDurationMs)} ms")
            appendLine("최근 변화 감지/저장: 최대 ${capture.eventToCaptureMs} ms")
        }
        appendLine()

        scenarios.forEachIndexed { index, scenario ->
            appendLine("===== SCENARIO ${index + 1} =====")

            if (scenario.aiResponses.isNotEmpty()) {
                appendLine("[AI RESPONSE]")
                appendResponseSection(scenario.aiResponses)
            }

            if (scenario.userResponses.isNotEmpty()) {
                appendLine("[USER RESPONSE]")
                appendResponseSection(scenario.userResponses)
            }

            if (scenario.unknownResponses.isNotEmpty()) {
                appendLine("[UNKNOWN RESPONSE]")
                appendResponseSection(scenario.unknownResponses)
            }

            appendLine()
        }
    }

    /** 같은 ID의 원본 구간들을 하나의 AI/USER 응답으로 만든다. */
    private fun groupResponses(messages: List<StoredMessage>): List<ResponseGroup> {
        val result = mutableListOf<ResponseGroup>()
        messages.forEach { stored ->
            val message = stored.record
            val role = normalizedRole(message)
            val previous = result.lastOrNull()
            val belongsToPrevious = previous != null &&
                previous.role == role &&
                when {
                    previous.messageId != null && message.id != null ->
                        previous.messageId == message.id
                    previous.messageId == null && message.id == null -> true
                    else -> false
                }

            if (belongsToPrevious) {
                previous.messages += stored
            } else {
                result += ResponseGroup(
                    role = role,
                    messageId = message.id,
                    messages = mutableListOf(stored),
                )
            }
        }
        return result
    }

    private fun normalizedRole(message: MessageRecord): MessageRole =
        if (message.role == MessageRole.UNKNOWN && message.kind == MessageKind.NARRATION) {
            MessageRole.ASSISTANT
        } else {
            message.role
        }

    /** AI 응답 하나와 그 뒤의 사용자 응답을 한 시나리오로 연결한다. */
    private fun groupScenarios(responses: List<ResponseGroup>): List<ScenarioGroup> {
        val result = mutableListOf<ScenarioGroup>()
        var current: ScenarioGroup? = null

        responses.forEach { response ->
            when (response.role) {
                MessageRole.ASSISTANT -> {
                    current = ScenarioGroup(
                        aiResponses = mutableListOf(response),
                    ).also(result::add)
                }

                MessageRole.USER -> {
                    val target = current ?: ScenarioGroup().also {
                        result += it
                        current = it
                    }
                    target.userResponses += response
                }

                MessageRole.UNKNOWN -> {
                    val target = current ?: ScenarioGroup().also {
                        result += it
                        current = it
                    }
                    target.unknownResponses += response
                }
            }
        }
        return result
    }

    /** 한 응답 안에서는 대사/서술을 나누지 않고 화자와 배경만 표시한다. */
    private fun StringBuilder.appendResponseSection(responses: List<ResponseGroup>) {
        responses.forEach { response ->
            var previousSpeaker: String? = null
            response.messages.forEach { stored ->
                val message = stored.record
                if (message.speaker != previousSpeaker) {
                    appendLine("[${message.speaker}]")
                    previousSpeaker = message.speaker
                }
                message.text.lineSequence().forEach(::appendLine)
            }
            appendLine()
        }
    }

    private fun formatDate(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.KOREA)
            .format(Date(epochMs))
}

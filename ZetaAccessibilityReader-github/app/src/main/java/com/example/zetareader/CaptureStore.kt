package com.example.zetareader

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 대화방마다 현재 활성 대화 하나만 보관한다. */
object CaptureStore {
    const val ACTION_UPDATED = "com.example.zetareader.CAPTURE_UPDATED"

    private const val LEGACY_LATEST_FILE = "latest_capture.txt"
    private const val LEGACY_SESSIONS_DIRECTORY = "sessions"
    private const val CONVERSATIONS_DIRECTORY = "conversations"
    private const val MAX_CONVERSATIONS = 10
    private const val ROOM_PREFIX = "room:"
    private const val ROOM_SELECTION_TTL_MS = 15_000L

    private data class ActiveConversation(
        val conversation: ConversationState,
        val observedMessageIds: MutableSet<String>,
        var baselineCapturePending: Boolean = true,
        var lastCapture: CaptureResult? = null,
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

    /** USER RESPONSE와 그 뒤의 AI 내용을 다음 USER RESPONSE 전까지 한 장면으로 묶는다. */
    private data class ScenarioGroup(
        val aiResponses: MutableList<ResponseGroup> = mutableListOf(),
        val userResponses: MutableList<ResponseGroup> = mutableListOf(),
        val unknownResponses: MutableList<ResponseGroup> = mutableListOf(),
    )

    private data class ScenarioLayout(
        val introduction: ScenarioGroup,
        val scenarios: List<ScenarioGroup>,
    )

    private var activeConversation: ActiveConversation? = null
    private var registry: ConversationRegistry? = null
    private var pendingConversation: PendingConversation? = null
    private var legacyFilesRemoved = false

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

    /** @return 이 호출에서 다른 대화방을 열었으면 true. */
    @Synchronized
    fun activateConversation(
        context: Context,
        visibleMessages: List<MessageRecord>,
    ): Boolean {
        val now = System.currentTimeMillis()
        val preferredConversationId = pendingConversation
            ?.takeIf { now - it.selectedAtEpochMs <= ROOM_SELECTION_TTL_MS }
            ?.conversationId

        activeConversation?.let { active ->
            if (
                preferredConversationId == null ||
                active.conversation.conversationId == preferredConversationId
            ) {
                pendingConversation = null
                return false
            }
            leaveActiveConversation(context, active, now)
        }

        pendingConversation = null
        val conversationRegistry = ensureRegistry(context)
        val activation = conversationRegistry.activate(
            preferredConversationId = preferredConversationId,
            visible = visibleMessages,
            nowEpochMs = now,
        )
        activation.removedConversationIds.forEach { previousId ->
            ConversationStatePersistence.delete(
                conversationsDirectory(context),
                previousId,
            )
        }
        val resetAfterInactivity = activation.state.markOpened(now)

        val state = ActiveConversation(
            conversation = activation.state,
            observedMessageIds = buildSet {
                addAll(activation.state.messages.mapNotNull { it.record.id })
                addAll(visibleMessages.mapNotNull { it.id })
            }.toMutableSet(),
        )
        activeConversation = state
        latest = null
        latestSnapshot = activation.state.snapshot()
        val currentEvaluation = activation.state.jevStartMessageId
            ?.let(activation.state::evaluationSnapshot)
        val displayedJev = JevEvaluationCoordinator.latest
        val canKeepDisplayedJev = !resetAfterInactivity &&
            displayedJev.conversationId == activation.state.conversationId &&
            displayedJev.revision == currentEvaluation?.revision
        if (!canKeepDisplayedJev) {
            JevEvaluationCoordinator.resetToBaseline(
                context,
                activation.state.conversationId,
            )
        }
        save(context, state)
        notifyUpdated(context)
        return true
    }

    @Synchronized
    fun publish(context: Context, capture: CaptureResult) {
        if (activeConversation == null) activateConversation(context, capture.messages)
        val state = activeConversation ?: return
        val isBaselineCapture = state.baselineCapturePending
        val newUserMessageId = if (isBaselineCapture) {
            null
        } else {
            firstNewUserMessageId(
                visible = capture.messages,
                observedMessageIds = state.observedMessageIds,
            )
        }

        val merge = state.conversation.mergeVisibleMessages(
            capture.messages,
            capture.capturedAtEpochMs,
        )
        val currentStart = state.conversation.jevStartMessageId
        val currentStartStillExists = currentStart
            ?.let(state.conversation::evaluationSnapshot) != null
        if (!isBaselineCapture && (currentStart == null || !currentStartStillExists)) {
            state.conversation.jevStartMessageId =
                merge.replacementStartUserMessageId ?: newUserMessageId
        }
        state.observedMessageIds += capture.messages.mapNotNull { it.id }
        state.baselineCapturePending = false
        val cumulativeCapture = capture.copy(
            messages = state.conversation.messageRecords(),
        )
        state.lastCapture = cumulativeCapture
        latest = cumulativeCapture
        latestSnapshot = state.conversation.snapshot()
        if (isBaselineCapture) {
            val currentEvaluation = state.conversation.jevStartMessageId
                ?.let(state.conversation::evaluationSnapshot)
            val displayedJev = JevEvaluationCoordinator.latest
            if (
                displayedJev.conversationId != state.conversation.conversationId ||
                displayedJev.revision != currentEvaluation?.revision
            ) {
                JevEvaluationCoordinator.resetToBaseline(
                    context,
                    state.conversation.conversationId,
                )
            }
        } else {
            state.conversation.jevStartMessageId
                ?.let(state.conversation::evaluationSnapshot)
                ?.let { JevEvaluationCoordinator.submit(context, it) }
        }
        save(context, state)
        notifyUpdated(context)
    }

    /** @return 열려 있던 대화방의 퇴장 시각을 기록했으면 true. */
    @Synchronized
    fun leaveConversation(context: Context): Boolean {
        val state = activeConversation ?: return false
        leaveActiveConversation(context, state, System.currentTimeMillis())
        return true
    }

    @Synchronized
    fun readLastFormatted(context: Context): String? = latestFormatted
        ?: ensureRegistry(context)
            .allStates()
            .maxByOrNull { it.lastOpenedAtEpochMs }
            ?.let { formatConversation(it, lastCapture = null) }

    private fun save(context: Context, state: ActiveConversation) {
        ConversationStatePersistence.save(
            conversationsDirectory(context),
            state.conversation,
        )
        latestFormatted = formatConversation(state.conversation, state.lastCapture)
    }

    private fun notifyUpdated(context: Context) {
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }

    private fun leaveActiveConversation(
        context: Context,
        state: ActiveConversation,
        leftAtEpochMs: Long,
    ) {
        state.conversation.markLeft(leftAtEpochMs)
        save(context, state)
        if (activeConversation === state) activeConversation = null
        notifyUpdated(context)
    }

    private fun ensureRegistry(context: Context): ConversationRegistry {
        removeLegacyFiles(context)
        registry?.let { return it }
        val directory = conversationsDirectory(context)
        val loaded = ConversationStatePersistence.loadAll(directory)
        val retained = loaded
            .sortedByDescending { it.lastOpenedAtEpochMs }
            .take(MAX_CONVERSATIONS)
        val retainedIds = retained.map { it.conversationId }.toSet()
        loaded
            .filter { it.conversationId !in retainedIds }
            .forEach { ConversationStatePersistence.delete(directory, it.conversationId) }
        return ConversationRegistry(
            initialStates = retained,
            maxConversations = MAX_CONVERSATIONS,
        ).also { registry = it }
    }

    private fun conversationsDirectory(context: Context): File =
        File(context.filesDir, CONVERSATIONS_DIRECTORY)

    private fun removeLegacyFiles(context: Context) {
        if (legacyFilesRemoved) return
        File(context.filesDir, LEGACY_SESSIONS_DIRECTORY).deleteRecursively()
        File(context.filesDir, LEGACY_LATEST_FILE).delete()
        legacyFilesRemoved = true
    }

    private fun formatConversation(
        conversation: ConversationState,
        lastCapture: CaptureResult?,
    ): String = buildString {
        val messages = conversation.messages
        val snapshot = conversation.snapshot()
        val evaluationSnapshot = conversation.jevStartMessageId
            ?.let(conversation::evaluationSnapshot)
        val responses = groupResponses(messages)
        val scenarioLayout = groupScenarios(responses)

        appendLine("제타 대화 상태")
        appendLine("대화방: ${conversation.conversationId}")
        appendLine("리비전: ${snapshot.revision}")
        appendLine("마지막 내용 변경: ${formatDate(conversation.updatedAtEpochMs)}")
        appendLine("마지막 입장: ${formatDate(conversation.lastOpenedAtEpochMs)}")
        appendLine(
            conversation.lastLeftAtEpochMs?.let { "마지막 퇴장: ${formatDate(it)}" }
                ?: "현재 상태: 대화방 열림",
        )
        appendLine("도입부: ${if (scenarioLayout.introduction.isEmpty()) "없음" else "있음"}")
        appendLine("시나리오: ${scenarioLayout.scenarios.size}개")
        appendLine("응답: ${responses.size}개 · 세부 구간: ${messages.size}개")
        appendLine("전체 현재 턴: ${snapshot.turns.size}개")
        appendLine(
            if (evaluationSnapshot == null) {
                "JEV 범위: 기준선 등록 완료 · 새 USER RESPONSE 대기 · 기존 내용 미전송"
            } else {
                "JEV 범위: 새 USER RESPONSE부터 ${evaluationSnapshot.turns.size}턴 · " +
                    if (evaluationSnapshot.isReadyForEvaluation) "분석 준비" else "AI 응답 대기"
            },
        )
        lastCapture?.let { capture ->
            appendLine("최근 갱신: ${formatDate(capture.capturedAtEpochMs)}")
            appendLine("최근 화면 분석: ${"%.2f".format(capture.scanDurationMs)} ms")
            appendLine("최근 변화 감지/저장: 최대 ${capture.eventToCaptureMs} ms")
        }
        appendLine()

        if (!scenarioLayout.introduction.isEmpty()) {
            appendLine("===== INTRO =====")
            appendScenarioSections(scenarioLayout.introduction, userFirst = false)
            appendLine()
        }

        scenarioLayout.scenarios.forEachIndexed { index, scenario ->
            appendLine("===== SCENARIO ${index + 1} =====")
            appendScenarioSections(scenario, userFirst = true)
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

    /** 첫 USER 전 AI는 INTRO, 이후에는 USER부터 다음 USER 직전까지가 한 시나리오다. */
    private fun groupScenarios(responses: List<ResponseGroup>): ScenarioLayout {
        val introduction = ScenarioGroup()
        val result = mutableListOf<ScenarioGroup>()
        val scenarioIndexes = scenarioIndexesForRoles(responses.map { it.role })

        responses.forEachIndexed { responseIndex, response ->
            val scenarioIndex = scenarioIndexes[responseIndex]
            val target = if (scenarioIndex == null) {
                introduction
            } else {
                while (result.size <= scenarioIndex) result += ScenarioGroup()
                result[scenarioIndex]
            }
            when (response.role) {
                MessageRole.ASSISTANT -> target.aiResponses += response
                MessageRole.USER -> target.userResponses += response
                MessageRole.UNKNOWN -> target.unknownResponses += response
            }
        }
        return ScenarioLayout(introduction = introduction, scenarios = result)
    }

    private fun ScenarioGroup.isEmpty(): Boolean =
        aiResponses.isEmpty() && userResponses.isEmpty() && unknownResponses.isEmpty()

    private fun StringBuilder.appendScenarioSections(
        scenario: ScenarioGroup,
        userFirst: Boolean,
    ) {
        if (userFirst && scenario.userResponses.isNotEmpty()) {
            appendLine("[USER RESPONSE]")
            appendResponseSection(scenario.userResponses)
        }
        if (scenario.aiResponses.isNotEmpty()) {
            appendLine("[AI RESPONSE]")
            appendResponseSection(scenario.aiResponses)
        }
        if (!userFirst && scenario.userResponses.isNotEmpty()) {
            appendLine("[USER RESPONSE]")
            appendResponseSection(scenario.userResponses)
        }
        if (scenario.unknownResponses.isNotEmpty()) {
            appendLine("[UNKNOWN RESPONSE]")
            appendResponseSection(scenario.unknownResponses)
        }
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

/** USER RESPONSE가 새 시나리오를 시작하며, 그 전 응답은 INTRO를 뜻하는 null이다. */
internal fun scenarioIndexesForRoles(roles: List<MessageRole>): List<Int?> {
    var scenarioIndex = -1
    return roles.map { role ->
        if (role == MessageRole.USER) scenarioIndex += 1
        scenarioIndex.takeIf { it >= 0 }
    }
}

/** 캐릭터 이름이 아니라 새 정식 USER 메시지 ID만 JEV 시작 기준으로 사용한다. */
internal fun firstNewUserMessageId(
    visible: List<MessageRecord>,
    observedMessageIds: Set<String>,
): String? = visible.firstNotNullOfOrNull { message ->
    message.id?.takeIf {
        message.role == MessageRole.USER && it !in observedMessageIds
    }
}

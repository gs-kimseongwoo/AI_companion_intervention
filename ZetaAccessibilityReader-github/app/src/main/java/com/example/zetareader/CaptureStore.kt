package com.example.zetareader

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 제타가 전면에 있는 한 번의 실행 구간을 하나의 세션 파일로 보관한다.
 * 화면에서 사라진 메시지도 메모리에 계속 유지해 새 캡처로 덮어쓰지 않는다.
 */
object CaptureStore {
    const val ACTION_UPDATED = "com.example.zetareader.CAPTURE_UPDATED"

    private const val LATEST_FILE = "latest_capture.txt"
    private const val SESSIONS_DIRECTORY = "sessions"

    private data class StoredMessage(
        var record: MessageRecord,
        val firstSeenAtEpochMs: Long,
        var lastUpdatedAtEpochMs: Long,
    )

    private data class SessionState(
        val startedAtEpochMs: Long,
        val file: File,
        val messages: MutableList<StoredMessage> = mutableListOf(),
        var lastCapture: CaptureResult? = null,
        var endedAtEpochMs: Long? = null,
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

    @Volatile
    var latest: CaptureResult? = null
        private set

    @Volatile
    var latestFormatted: String? = null
        private set

    /** @return 이 호출에서 새 세션이 만들어졌으면 true. */
    @Synchronized
    fun beginSession(context: Context): Boolean {
        if (activeSession != null) return false

        val startedAt = System.currentTimeMillis()
        val directory = File(context.filesDir, SESSIONS_DIRECTORY).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.KOREA)
            .format(Date(startedAt))
        val state = SessionState(
            startedAtEpochMs = startedAt,
            file = File(directory, "zeta_session_${stamp}.txt"),
        )
        activeSession = state
        latest = null
        save(context, state)
        notifyUpdated(context)
        return true
    }

    @Synchronized
    fun publish(context: Context, capture: CaptureResult) {
        if (activeSession == null) beginSession(context)
        val state = activeSession ?: return

        mergeVisibleMessages(state, capture.messages, capture.capturedAtEpochMs)
        val cumulativeCapture = capture.copy(
            messages = state.messages.map { it.record },
        )
        state.lastCapture = cumulativeCapture
        latest = cumulativeCapture
        save(context, state)
        notifyUpdated(context)
    }

    /** @return 진행 중이던 세션을 이번 호출에서 종료했으면 true. */
    @Synchronized
    fun finishSession(context: Context): Boolean {
        val state = activeSession ?: return false
        state.endedAtEpochMs = System.currentTimeMillis()
        save(context, state)
        activeSession = null
        notifyUpdated(context)
        return true
    }

    fun readLastFormatted(context: Context): String? =
        latestFormatted ?: runCatching {
            context.openFileInput(LATEST_FILE).bufferedReader().use { it.readText() }
        }.getOrNull()

    /** 화면에 동시에 보인 메시지들의 순서를 이용해 기존 누적 목록에 끼워 넣는다. */
    private fun mergeVisibleMessages(
        state: SessionState,
        visible: List<MessageRecord>,
        seenAtEpochMs: Long,
    ) {
        val resolved = MutableList<StoredMessage?>(visible.size) { null }

        visible.forEachIndexed { index, record ->
            val stored = findExisting(state.messages, record) ?: return@forEachIndexed
            stored.record = record
            stored.lastUpdatedAtEpochMs = seenAtEpochMs
            resolved[index] = stored
        }

        visible.forEachIndexed { index, record ->
            if (resolved[index] != null) return@forEachIndexed

            val previous = (index - 1 downTo 0)
                .firstNotNullOfOrNull { resolved[it] }
            val next = (index + 1 until visible.size)
                .firstNotNullOfOrNull { resolved[it] }
            val insertAt = when {
                next != null -> state.messages.indexOf(next).coerceAtLeast(0)
                previous != null -> state.messages.indexOf(previous) + 1
                else -> state.messages.size
            }
            val added = StoredMessage(
                record = record,
                firstSeenAtEpochMs = seenAtEpochMs,
                lastUpdatedAtEpochMs = seenAtEpochMs,
            )
            state.messages.add(insertAt.coerceIn(0, state.messages.size), added)
            resolved[index] = added
        }
    }

    private fun findExisting(
        storedMessages: List<StoredMessage>,
        incoming: MessageRecord,
    ): StoredMessage? {
        incoming.id?.let { id ->
            storedMessages.firstOrNull {
                it.record.id == id &&
                    it.record.segmentIndex == incoming.segmentIndex
            }?.let { return it }

            // 생성 중에는 ID가 없다가 답변이 완성되면 ID가 붙는다.
            // 같은 화자의 이어지는 본문이면 기존 임시 항목을 정식 ID로 승격한다.
            storedMessages.lastOrNull {
                it.record.id == null &&
                    it.record.segmentIndex == incoming.segmentIndex &&
                    it.record.role == incoming.role &&
                    it.record.kind == incoming.kind &&
                    it.record.speaker == incoming.speaker &&
                    textsBelongToSameStream(it.record.text, incoming.text)
            }?.let { return it }
        }

        if (incoming.id == null) {
            return storedMessages.lastOrNull {
                it.record.id == null &&
                    it.record.segmentIndex == incoming.segmentIndex &&
                    it.record.role == incoming.role &&
                    it.record.kind == incoming.kind &&
                    it.record.speaker == incoming.speaker
            }
        }
        return null
    }

    private fun textsBelongToSameStream(old: String, new: String): Boolean {
        val oldText = old.trim()
        val newText = new.trim()
        return oldText == newText ||
            newText.startsWith(oldText) ||
            oldText.startsWith(newText)
    }

    private fun save(context: Context, state: SessionState) {
        val formatted = formatSession(state)
        state.file.writeText(formatted)
        File(context.filesDir, LATEST_FILE).writeText(formatted)
        latestFormatted = formatted
    }

    private fun notifyUpdated(context: Context) {
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }

    private fun formatSession(state: SessionState): String = buildString {
        val responses = groupResponses(state.messages)
        val scenarios = groupScenarios(responses)

        appendLine("제타 대화 세션")
        appendLine("상태: ${if (state.endedAtEpochMs == null) "기록 중" else "종료"}")
        appendLine("시작: ${formatDate(state.startedAtEpochMs)}")
        state.endedAtEpochMs?.let { appendLine("종료: ${formatDate(it)}") }
        appendLine("저장 파일: $SESSIONS_DIRECTORY/${state.file.name}")
        appendLine("시나리오: ${scenarios.size}개")
        appendLine("응답: ${responses.size}개 · 세부 구간: ${state.messages.size}개")
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

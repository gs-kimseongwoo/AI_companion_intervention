package com.example.zetareader

import java.security.MessageDigest

internal data class StoredMessage(
    var record: MessageRecord,
    val firstSeenAtEpochMs: Long,
    var lastUpdatedAtEpochMs: Long,
)

/**
 * 한 대화방의 현재 활성 대화 상태.
 *
 * 화면에 보이지 않는다는 이유로 기존 메시지를 지우지 않는다. 동일 ID는 갱신하고,
 * ID가 없는 최신 AI 답변은 같은 임시 항목에서 계속 덮어쓴다.
 */
internal class ConversationState(
    var conversationId: String,
    val createdAtEpochMs: Long,
    val messages: MutableList<StoredMessage> = mutableListOf(),
    var updatedAtEpochMs: Long = createdAtEpochMs,
) {
    fun mergeVisibleMessages(
        visible: List<MessageRecord>,
        seenAtEpochMs: Long,
    ) {
        discardSupersededVisibleSegments(visible)
        val resolved = MutableList<StoredMessage?>(visible.size) { null }

        visible.forEachIndexed { index, record ->
            val stored = findExisting(record) ?: return@forEachIndexed
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
                next != null -> messages.indexOf(next).coerceAtLeast(0)
                previous != null -> messages.indexOf(previous) + 1
                else -> messages.size
            }
            val added = StoredMessage(
                record = record,
                firstSeenAtEpochMs = seenAtEpochMs,
                lastUpdatedAtEpochMs = seenAtEpochMs,
            )
            messages.add(insertAt.coerceIn(0, messages.size), added)
            resolved[index] = added
        }

        updatedAtEpochMs = maxOf(updatedAtEpochMs, seenAtEpochMs)
    }

    /**
     * 화면에 다시 나타난 하나의 메시지 ID는 그 ID의 현재 세부 구간에 대해서는
     * 권위 있는 관측값이다. 구간 구성이 바뀌었는데 예전 구간을 남기면 방을
     * 재진입할 때마다 같은 응답이 조금씩 중복되므로, 보이는 ID 내부만 정리한다.
     */
    private fun discardSupersededVisibleSegments(visible: List<MessageRecord>) {
        visible
            .asSequence()
            .mapNotNull { it.id }
            .distinct()
            .forEach { visibleId ->
                val currentSegmentIndexes = visible
                    .asSequence()
                    .filter { it.id == visibleId }
                    .map { it.segmentIndex }
                    .toSet()
                messages.removeAll {
                    it.record.id == visibleId &&
                        it.record.segmentIndex !in currentSegmentIndexes
                }
            }

        val pending = visible.filter { it.id == null }
        if (pending.isEmpty()) return
        val currentPendingKeys = pending.map(::segmentKey).toSet()
        messages.removeAll {
            it.record.id == null && segmentKey(it.record) !in currentPendingKeys
        }
    }

    private fun segmentKey(message: MessageRecord): String = buildString {
        append(message.segmentIndex)
        append('|').append(message.role.name)
        append('|').append(message.kind.name)
        append('|').append(message.speaker)
    }

    fun messageRecords(): List<MessageRecord> = messages.map { it.record }

    fun overlapCount(visible: List<MessageRecord>): Int {
        val visibleIds = visible.mapNotNull { it.id }.toSet()
        if (visibleIds.isEmpty()) return 0
        return messages
            .asSequence()
            .mapNotNull { it.record.id }
            .distinct()
            .count { it in visibleIds }
    }

    fun snapshot(): ConversationSnapshot {
        val turns = buildTurns(messages)
        return ConversationSnapshot(
            conversationId = conversationId,
            revision = revisionOf(turns),
            updatedAtEpochMs = updatedAtEpochMs,
            turns = turns,
        )
    }

    private fun findExisting(incoming: MessageRecord): StoredMessage? {
        incoming.id?.let { id ->
            messages.firstOrNull {
                it.record.id == id &&
                    it.record.segmentIndex == incoming.segmentIndex
            }?.let { return it }

            // 최신 AI 답변은 ID 없이 유지되다가 다음 사용자 메시지가 생길 때
            // 정식 ID를 얻는다. 본문이 같은 임시 항목을 그 ID로 승격한다.
            messages.lastOrNull {
                it.record.id == null &&
                    it.record.segmentIndex == incoming.segmentIndex &&
                    it.record.role == incoming.role &&
                    it.record.kind == incoming.kind &&
                    it.record.speaker == incoming.speaker &&
                    textsBelongToSameStream(it.record.text, incoming.text)
            }?.let { return it }
        }

        if (incoming.id == null) {
            // 수정과 재생성은 같은 ID 없는 최신 응답을 교체한다. 과거 버전을
            // 별도 항목으로 누적하지 않는다.
            return messages.lastOrNull {
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
}

internal data class ConversationActivation(
    val state: ConversationState,
    /** 임시 키를 방 UUID로 승격했을 때 제거할 예전 저장 키. */
    val replacedConversationId: String? = null,
)

/** 대화방 선택 ID와 기존 메시지 ID의 교집합을 이용해 상태를 분리·복원한다. */
internal class ConversationRegistry(
    initialStates: Collection<ConversationState> = emptyList(),
) {
    private val states = linkedMapOf<String, ConversationState>().apply {
        initialStates.forEach { put(it.conversationId, it) }
    }

    fun activate(
        preferredConversationId: String?,
        visible: List<MessageRecord>,
        nowEpochMs: Long,
    ): ConversationActivation {
        val matching = bestMatchingState(visible)

        if (preferredConversationId != null) {
            states[preferredConversationId]?.let {
                return ConversationActivation(it)
            }

            if (matching != null) {
                val previousId = matching.conversationId
                states.remove(previousId)
                matching.conversationId = preferredConversationId
                states[preferredConversationId] = matching
                return ConversationActivation(
                    state = matching,
                    replacedConversationId = previousId,
                )
            }

            return ConversationActivation(
                state = createState(preferredConversationId, nowEpochMs),
            )
        }

        matching?.let { return ConversationActivation(it) }

        val firstStableMessageId = visible.firstNotNullOfOrNull { it.id }
        val fallbackId = firstStableMessageId
            ?.let { "messages:$it" }
            ?: "visit:$nowEpochMs"
        return ConversationActivation(createState(fallbackId, nowEpochMs))
    }

    fun allStates(): Collection<ConversationState> = states.values

    private fun bestMatchingState(visible: List<MessageRecord>): ConversationState? =
        states.values
            .map { it to it.overlapCount(visible) }
            .filter { (_, overlap) -> overlap > 0 }
            .maxWithOrNull(
                compareBy<Pair<ConversationState, Int>> { it.second }
                    .thenBy { it.first.updatedAtEpochMs },
            )
            ?.first

    private fun createState(id: String, nowEpochMs: Long): ConversationState =
        ConversationState(
            conversationId = id,
            createdAtEpochMs = nowEpochMs,
        ).also { states[id] = it }
}

private data class TurnGroup(
    val role: MessageRole,
    val messageId: String?,
    val messages: MutableList<StoredMessage>,
)

private fun buildTurns(messages: List<StoredMessage>): List<DialogueTurn> {
    val groups = mutableListOf<TurnGroup>()
    messages.forEach { stored ->
        val message = stored.record
        val role = normalizedRole(message)
        val previous = groups.lastOrNull()
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
            groups += TurnGroup(
                role = role,
                messageId = message.id,
                messages = mutableListOf(stored),
            )
        }
    }

    return groups.map { group ->
        DialogueTurn(
            messageId = group.messageId,
            role = group.role,
            text = formatTurnText(group.messages),
            isPending = group.messageId == null,
        )
    }
}

private fun normalizedRole(message: MessageRecord): MessageRole =
    if (message.role == MessageRole.UNKNOWN && message.kind == MessageKind.NARRATION) {
        MessageRole.ASSISTANT
    } else {
        message.role
    }

private fun formatTurnText(messages: List<StoredMessage>): String = buildString {
    var previousSpeaker: String? = null
    messages.forEach { stored ->
        val message = stored.record
        val speaker = message.speaker.trim()
        if (
            speaker.isNotEmpty() &&
            speaker != "unknown" &&
            speaker != previousSpeaker
        ) {
            if (isNotEmpty()) append('\n')
            append('[').append(speaker).appendLine(']')
            previousSpeaker = speaker
        } else if (isNotEmpty()) {
            append('\n')
        }
        append(message.text.trim())
    }
}.trim()

private fun revisionOf(turns: List<DialogueTurn>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    turns.forEach { turn ->
        digest.update(turn.role.name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(turn.text.toByteArray(Charsets.UTF_8))
        digest.update(0xff.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

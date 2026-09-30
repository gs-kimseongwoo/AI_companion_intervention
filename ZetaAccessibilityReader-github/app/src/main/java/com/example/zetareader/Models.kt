package com.example.zetareader

const val BACKGROUND_SPEAKER = "배경"

/** Android의 Rect 대신 파서가 독립적으로 쓸 수 있는 화면 좌표. */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isVisible: Boolean get() = right > left && bottom > top
}

/** AccessibilityNodeInfo를 즉시 복사해 둔 가벼운 화면 트리. */
data class UiNode(
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: Bounds,
    val children: List<UiNode>,
)

enum class MessageRole {
    USER,
    ASSISTANT,
    UNKNOWN,
}

enum class MessageKind {
    DIALOGUE,
    NARRATION,
}

data class MessageRecord(
    val id: String?,
    val role: MessageRole,
    val speaker: String,
    val text: String,
    val kind: MessageKind = MessageKind.DIALOGUE,
    val segmentIndex: Int = 0,
)

data class ParseResult(
    val messages: List<MessageRecord>,
    val detectedUserSpeaker: String?,
)

data class CaptureResult(
    val capturedAtEpochMs: Long,
    val scanDurationMs: Double,
    val eventToCaptureMs: Long,
    val nodeCount: Int,
    val messages: List<MessageRecord>,
)

/** JEV 같은 후속 분석기가 사용할 수 있도록 세부 구간을 한 발화 단위로 묶은 결과. */
data class DialogueTurn(
    val messageId: String?,
    val role: MessageRole,
    val text: String,
    /** 최신 AI 답변처럼 아직 Zeta의 정식 메시지 ID가 붙지 않은 턴. */
    val isPending: Boolean,
)

/** 한 대화방의 현재 활성 대화만 나타낸다. 과거 수정 버전은 포함하지 않는다. */
data class ConversationSnapshot(
    val conversationId: String,
    /** 역할과 본문 순서로 계산한다. ID만 확정되는 경우에는 바뀌지 않는다. */
    val revision: String,
    val updatedAtEpochMs: Long,
    val turns: List<DialogueTurn>,
) {
    val isReadyForEvaluation: Boolean
        get() = turns.isNotEmpty() &&
            turns.none { it.role == MessageRole.UNKNOWN } &&
            turns.last().role == MessageRole.ASSISTANT
}

enum class JevEvaluationPhase {
    BASELINE,
    IDLE,
    WAITING,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

/** 현재 revision에 대한 관찰용 JEV 결과. 자동 종료 판단에는 아직 사용하지 않는다. */
data class JevEvaluationResult(
    val conversationId: String,
    val revision: String,
    val contextShift: Double,
    val naturalBreakpoint: Double,
    val engagementDrop: Double,
    val model: String,
    val latencyMs: Double,
)

data class JevEvaluationState(
    val phase: JevEvaluationPhase = JevEvaluationPhase.IDLE,
    val conversationId: String? = null,
    val revision: String? = null,
    val result: JevEvaluationResult? = null,
    val error: String? = null,
)

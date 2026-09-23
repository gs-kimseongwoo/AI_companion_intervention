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

package com.example.zetareader

/**
 * 제타의 접근성 트리에서 대사와 배경 설명을 순서대로 꺼내는 순수 Kotlin 파서.
 * 한 AI 메시지 안에서 화자가 바뀌는 경우도 별도 구간으로 분리한다.
 */
object ZetaDialogueParser {
    private const val MESSAGE_PREFIX = "message-MESSAGE-"
    private const val CLASS_BUTTON = "android.widget.Button"
    private const val CLASS_IMAGE = "android.widget.Image"
    private const val CLASS_TEXT = "android.widget.TextView"
    private const val CLASS_VIEW = "android.view.View"

    private enum class SegmentMode {
        DIALOGUE,
        NARRATION,
    }

    private data class RawSegment(
        val kind: MessageKind,
        val speaker: String,
        val text: String,
    )

    private val uiTexts = setOf(
        "답변은 모두 AI가 생성한 내용이에요",
        "무료",
        "광고 제거",
        "오늘의 퀴즈",
    )

    fun parse(
        root: UiNode,
        screenWidth: Int,
        previouslyDetectedUser: String?,
    ): ParseResult {
        val idNodes = root.depthFirst().filter {
            it.viewId?.startsWith(MESSAGE_PREFIX) == true
        }

        val parsed = idNodes.flatMapTo(mutableListOf()) { node ->
            parseMessageNode(node, screenWidth)
        }

        parsed += trailingAiMessages(root, idNodes.lastOrNull(), screenWidth)

        val visibleUserNames = parsed
            .filter {
                it.role == MessageRole.USER &&
                    it.kind == MessageKind.DIALOGUE
            }
            .map { it.speaker }
            .filter { it != "unknown" }
            .toSet()

        val detectedUser = when {
            visibleUserNames.size == 1 -> visibleUserNames.first()
            previouslyDetectedUser != null -> previouslyDetectedUser
            else -> null
        }

        val completed = parsed.map { message ->
            if (message.role != MessageRole.UNKNOWN) {
                message
            } else if (message.kind == MessageKind.NARRATION) {
                message.copy(role = MessageRole.ASSISTANT)
            } else if (detectedUser != null) {
                message.copy(
                    role = if (message.speaker == detectedUser) {
                        MessageRole.USER
                    } else {
                        MessageRole.ASSISTANT
                    },
                )
            } else {
                message
            }
        }

        return ParseResult(completed, detectedUser)
    }

    private fun parseMessageNode(
        node: UiNode,
        screenWidth: Int,
        forcedRole: MessageRole? = null,
    ): List<MessageRecord> {
        val baseId = node.viewId?.removePrefix("message-")
        val semanticNode = findSemanticContainer(node)
        val (primarySpeaker, speakerNode) = inferSpeaker(semanticNode)
        val sourceRole = forcedRole ?: roleFromAlignment(speakerNode, screenWidth)
        var currentSpeaker = primarySpeaker
        var mode = if (primarySpeaker == "unknown") {
            SegmentMode.NARRATION
        } else {
            SegmentMode.DIALOGUE
        }
        val segments = mutableListOf<RawSegment>()

        semanticNode.children.forEach { child ->
            val text = clean(child.text)
            val description = clean(child.contentDescription)

            when {
                child.className == CLASS_BUTTON && isSpeakerName(text) -> {
                    currentSpeaker = text
                    mode = SegmentMode.DIALOGUE
                }

                child.className == CLASS_IMAGE && isSpeakerName(description) -> {
                    currentSpeaker = description
                    mode = SegmentMode.DIALOGUE
                }

                child.className == CLASS_IMAGE -> {
                    currentSpeaker = BACKGROUND_SPEAKER
                    mode = SegmentMode.NARRATION
                }

                child.className == CLASS_TEXT && text == currentSpeaker -> Unit

                child.className == CLASS_VIEW -> {
                    appendContentSegments(
                        result = segments,
                        content = child,
                        mode = mode,
                        speaker = currentSpeaker,
                    )
                }
            }
        }

        return segments.mapIndexed { index, segment ->
            MessageRecord(
                id = baseId,
                role = sourceRole,
                speaker = segment.speaker,
                text = segment.text,
                kind = segment.kind,
                segmentIndex = index,
            )
        }
    }

    private fun appendContentSegments(
        result: MutableList<RawSegment>,
        content: UiNode,
        mode: SegmentMode,
        speaker: String,
    ) {
        content.leafNodes().forEach { leaf ->
            val text = clean(leaf.text)
            if (text.isEmpty() || text == speaker || text in uiTexts) return@forEach

            val kind = when {
                mode == SegmentMode.NARRATION -> MessageKind.NARRATION
                leaf.className == CLASS_TEXT -> MessageKind.DIALOGUE
                else -> MessageKind.NARRATION
            }
            val segmentSpeaker = when {
                kind == MessageKind.DIALOGUE -> speaker
                mode == SegmentMode.NARRATION -> BACKGROUND_SPEAKER
                else -> speaker
            }
            appendOrMerge(
                result,
                RawSegment(
                    kind = kind,
                    speaker = segmentSpeaker,
                    text = text,
                ),
            )
        }
    }

    private fun appendOrMerge(
        result: MutableList<RawSegment>,
        incoming: RawSegment,
    ) {
        val previous = result.lastOrNull()
        if (previous?.kind == incoming.kind && previous.speaker == incoming.speaker) {
            if (previous.text != incoming.text) {
                result[result.lastIndex] = previous.copy(
                    text = "${previous.text}\n${incoming.text}",
                )
            }
            return
        }
        result += incoming
    }

    private fun inferSpeaker(node: UiNode): Pair<String, UiNode?> {
        node.children.firstOrNull {
            it.className == CLASS_BUTTON && isSpeakerName(clean(it.text))
        }?.let {
            return clean(it.text) to it
        }

        node.children.firstOrNull {
            it.className == CLASS_TEXT && isSpeakerName(clean(it.text))
        }?.let {
            return clean(it.text) to it
        }

        node.depthFirst().firstOrNull {
            it.className == CLASS_BUTTON && isSpeakerName(clean(it.text))
        }?.let {
            return clean(it.text) to it
        }
        return "unknown" to null
    }

    /**
     * ID가 없는 생성 중 답변은 실제 메시지 블록이 여러 겹의 View 안에 들어간다.
     * 화자 표식과 본문을 직접 자식으로 가장 많이 가진 내부 컨테이너를 선택한다.
     */
    private fun findSemanticContainer(node: UiNode): UiNode =
        node.depthFirst().maxByOrNull { candidate ->
            val speakerMarkers = candidate.children.count { child ->
                (child.className == CLASS_BUTTON && isSpeakerName(clean(child.text))) ||
                    (child.className == CLASS_IMAGE &&
                        isSpeakerName(clean(child.contentDescription)))
            }
            val contentBlocks = candidate.children.count { child ->
                child.className == CLASS_VIEW &&
                    child.leafNodes().any { clean(it.text).isNotEmpty() }
            }
            speakerMarkers * 100 + contentBlocks
        } ?: node

    private fun isSpeakerName(value: String): Boolean =
        value.isNotEmpty() && value !in uiTexts

    private fun roleFromAlignment(node: UiNode?, screenWidth: Int): MessageRole {
        // WebView는 화면 밖 메시지의 세로 좌표를 0으로 만들지만 가로 정렬은 유지한다.
        if (node == null || node.bounds.right <= node.bounds.left) return MessageRole.UNKNOWN
        return if (node.bounds.left > screenWidth / 2) {
            MessageRole.USER
        } else {
            MessageRole.ASSISTANT
        }
    }

    /** 최신 AI 답변은 제타가 message-MESSAGE-* ID를 아직 붙이지 않는 경우가 있다. */
    private fun trailingAiMessages(
        root: UiNode,
        lastIdNode: UiNode?,
        screenWidth: Int,
    ): List<MessageRecord> {
        if (lastIdNode == null) return emptyList()
        val wrapper = root.findParentOf(lastIdNode) ?: return emptyList()
        val container = root.findParentOf(wrapper) ?: return emptyList()
        val startIndex = container.children.indexOfFirst { it === wrapper } + 1
        if (startIndex <= 0) return emptyList()

        for (candidate in container.children.drop(startIndex)) {
            val hasMessageActions = candidate.depthFirst().any {
                it.contentDescription == "Report message" ||
                    it.contentDescription == "Regenerate answers"
            }
            if (!hasMessageActions) continue

            val messages = parseMessageNode(
                node = candidate,
                screenWidth = screenWidth,
                forcedRole = MessageRole.ASSISTANT,
            )
            if (messages.isNotEmpty()) return messages
        }
        return emptyList()
    }

    private fun UiNode.depthFirst(): List<UiNode> {
        val result = mutableListOf<UiNode>()
        fun visit(node: UiNode) {
            result += node
            node.children.forEach(::visit)
        }
        visit(this)
        return result
    }

    private fun UiNode.leafNodes(): List<UiNode> =
        depthFirst().filter { it.children.isEmpty() }

    private fun UiNode.findParentOf(target: UiNode): UiNode? {
        if (children.any { it === target }) return this
        children.forEach { child ->
            child.findParentOf(target)?.let { return it }
        }
        return null
    }

    private fun clean(value: String?): String =
        value.orEmpty().replace("\r\n", "\n").replace("\r", "\n").trim()
}

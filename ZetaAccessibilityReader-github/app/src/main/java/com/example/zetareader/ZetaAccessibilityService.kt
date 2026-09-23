package com.example.zetareader

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 사용자가 Android 접근성 설정에서 직접 허용하면 동작한다.
 * XML 설정의 packageNames 때문에 제타(com.scatterlab.messenger) 이벤트만 전달받는다.
 */
class ZetaAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastEventUptimeMs: Long = 0L
    private var lastSignature: String = ""
    private var detectedUserSpeaker: String? = null
    private var captureScheduled = false
    private var consecutiveNonDialogueCaptures = 0
    private var overlay: LiveDialogueOverlay? = null

    private val captureRunnable = object : Runnable {
        override fun run() {
            captureScheduled = false
            val zetaIsVisible = captureVisibleDialogue()

            // 일부 React Native 화면은 문장이 스트리밍되는 동안 접근성 이벤트를
            // 매번 보내지 않는다. 제타가 앞에 있는 동안에는 짧은 간격으로 직접
            // 확인해서 이벤트가 빠진 변화도 놓치지 않는다.
            if (zetaIsVisible) {
                scheduleCapture(LIVE_POLL_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastEventUptimeMs = SystemClock.uptimeMillis()
        overlay = LiveDialogueOverlay(this)
        scheduleCapture()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != ZETA_PACKAGE) return

        lastEventUptimeMs = event.eventTime
        // 이미 예약된 작업은 취소하지 않는다. 계속 들어오는 이벤트가 수집을
        // 무한히 뒤로 미루지 않도록 최대 LIVE_POLL_INTERVAL_MS 안에 읽는다.
        scheduleCapture()
    }

    override fun onInterrupt() {
        handler.removeCallbacks(captureRunnable)
        captureScheduled = false
        overlay?.hide()
        finishConversationSession()
    }

    override fun onDestroy() {
        handler.removeCallbacks(captureRunnable)
        captureScheduled = false
        overlay?.hide()
        overlay = null
        finishConversationSession()
        super.onDestroy()
    }

    private fun scheduleCapture(delayMs: Long = 0L) {
        if (captureScheduled) return
        captureScheduled = true
        handler.postDelayed(captureRunnable, delayMs)
    }

    /** @return 현재 맨 앞 화면이 제타이면 true. */
    private fun captureVisibleDialogue(): Boolean {
        val scanStartedNs = SystemClock.elapsedRealtimeNanos()
        val root = rootInActiveWindow ?: run {
            overlay?.hide()
            finishConversationSession()
            return false
        }
        if (root.packageName?.toString() != ZETA_PACKAGE) {
            root.recycle()
            overlay?.hide()
            finishConversationSession()
            return false
        }
        val counter = intArrayOf(0)
        val snapshot = try {
            snapshot(root, counter)
        } catch (error: RuntimeException) {
            Log.w(TAG, "제타 접근성 트리를 읽는 중 화면이 바뀌었습니다.", error)
            return true
        } finally {
            root.recycle()
        }
        if (!snapshot.containsViewId(CHAT_ROOM_VIEW_ID)) {
            overlay?.hide()
            consecutiveNonDialogueCaptures += 1
            if (consecutiveNonDialogueCaptures >= NON_DIALOGUE_CONFIRMATIONS) {
                finishConversationSession()
            }
            return true
        }
        consecutiveNonDialogueCaptures = 0
        val parsed = ZetaDialogueParser.parse(
            root = snapshot,
            screenWidth = resources.displayMetrics.widthPixels,
            previouslyDetectedUser = detectedUserSpeaker,
        )
        if (parsed.messages.isEmpty()) {
            overlay?.showWaiting()
            return true
        }
        if (CaptureStore.beginSession(this)) {
            // 직전 대화와 화면이 같아도 새 대화방 세션의 첫 화면은 반드시 저장한다.
            lastSignature = ""
        }
        detectedUserSpeaker = parsed.detectedUserSpeaker ?: detectedUserSpeaker

        val scanFinishedNs = SystemClock.elapsedRealtimeNanos()
        val finishedUptimeMs = SystemClock.uptimeMillis()
        val scanDurationMs = (scanFinishedNs - scanStartedNs) / 1_000_000.0
        val eventAgeMs = (finishedUptimeMs - lastEventUptimeMs).coerceAtLeast(0L)
        val detectionDelayMs = if (eventAgeMs <= RECENT_EVENT_MAX_AGE_MS) {
            eventAgeMs
        } else {
            // 접근성 이벤트가 오지 않아 500ms 폴링으로 발견한 변화의 최대 지연.
            LIVE_POLL_INTERVAL_MS + scanDurationMs.toLong()
        }
        overlay?.show(parsed.messages, scanDurationMs)
        val signature = parsed.messages.joinToString("|") {
            "${it.id}:${it.segmentIndex}:${it.role}:${it.kind}:${it.speaker}:${it.text}"
        }

        // 실시간 확인은 계속하되 내용이 그대로면 파일/UI를 다시 쓰지 않는다.
        if (signature == lastSignature) return true
        lastSignature = signature

        CaptureStore.publish(
            this,
            CaptureResult(
                capturedAtEpochMs = System.currentTimeMillis(),
                scanDurationMs = scanDurationMs,
                eventToCaptureMs = detectionDelayMs,
                nodeCount = counter[0],
                messages = parsed.messages,
            ),
        )
        Log.d(TAG, "실시간 수집 완료: ${parsed.messages.size}개, ${counter[0]} nodes")
        return true
    }

    private fun finishConversationSession() {
        if (CaptureStore.finishSession(this)) {
            lastSignature = ""
            detectedUserSpeaker = null
            consecutiveNonDialogueCaptures = 0
            Log.d(TAG, "제타 세션 저장 완료")
        }
    }

    private fun snapshot(node: AccessibilityNodeInfo, counter: IntArray): UiNode {
        counter[0] += 1
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val children = buildList {
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    add(snapshot(child, counter))
                } finally {
                    child.recycle()
                }
            }
        }
        return UiNode(
            viewId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }

    private fun UiNode.containsViewId(expected: String): Boolean =
        viewId == expected || children.any { it.containsViewId(expected) }

    companion object {
        private const val TAG = "ZetaReader"
        private const val ZETA_PACKAGE = "com.scatterlab.messenger"
        private const val CHAT_ROOM_VIEW_ID = "chat-room-screen"
        private const val LIVE_POLL_INTERVAL_MS = 500L
        private const val RECENT_EVENT_MAX_AGE_MS = 1_500L
        private const val NON_DIALOGUE_CONFIRMATIONS = 2
    }
}

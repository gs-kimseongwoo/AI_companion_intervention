package com.example.zetareader

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

/**
 * 접근성 서비스에 허용된 TYPE_ACCESSIBILITY_OVERLAY를 사용한다.
 * 별도의 SYSTEM_ALERT_WINDOW 권한 없이 제타 위에 읽은 결과를 보여준다.
 */
class LiveDialogueOverlay(
    private val service: AccessibilityService,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val holdHandler = Handler(Looper.getMainLooper())
    private var isShowing = false
    private var isHolding = false
    private var holdSecondsRemaining = HOLD_SECONDS
    private var lastRenderedText = ""

    private val holdTick = object : Runnable {
        override fun run() {
            if (!isHolding) return
            holdSecondsRemaining -= 1
            if (holdSecondsRemaining <= 0) {
                isHolding = false
                textView.alpha = 1f
                textView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                openSavedSession()
                return
            }
            showHoldProgress()
            holdHandler.postDelayed(this, 1_000L)
        }
    }

    private val textView = TextView(service).apply {
        textSize = 12.5f
        setTextColor(Color.WHITE)
        setLineSpacing(0f, 1.12f)
        setPadding(dp(14), dp(11), dp(14), dp(12))
        maxLines = 7
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        elevation = dp(8).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(225, 28, 34, 45))
            setStroke(dp(1), Color.argb(190, 91, 166, 255))
        }
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    private val layoutParams = WindowManager.LayoutParams(
        dp(240),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(12)
        y = dp(62)
    }

    fun showWaiting() {
        render("● 실시간 읽기\n5초 누르면 저장 기록 보기\n제타 대화 화면을 기다리는 중…")
    }

    fun show(messages: List<MessageRecord>, scanDurationMs: Double) {
        val latest = messages.lastOrNull() ?: return showWaiting()
        val roleLabel = when (latest.kind) {
            MessageKind.NARRATION -> if (latest.role == MessageRole.USER) {
                "내 서술"
            } else if (latest.speaker != BACKGROUND_SPEAKER) {
                "인물 서술"
            } else {
                "배경 설명"
            }
            MessageKind.DIALOGUE -> when (latest.role) {
                MessageRole.USER -> "내 응답"
                MessageRole.ASSISTANT -> "AI 대사"
                MessageRole.UNKNOWN -> "대화"
            }
        }
        val speaker = latest.speaker
            .takeUnless {
                latest.speaker == BACKGROUND_SPEAKER ||
                    it.isBlank() ||
                    it == "unknown"
            }
            ?.let { " · $it" }
            .orEmpty()
        val body = latest.text
            .trim()
            .replace(Regex("\\s+"), " ")
            .let { if (it.length > MAX_BODY_LENGTH) "…${it.takeLast(MAX_BODY_LENGTH)}" else it }
        val duration = String.format(Locale.KOREA, "%.0f", scanDurationMs)

        render(
            "● 실시간 읽기 · ${duration}ms\n" +
                "5초 누르면 저장 기록 보기\n" +
                "$roleLabel$speaker\n" +
                body,
        )
    }

    fun hide() {
        cancelHold()
        if (!isShowing) return
        try {
            windowManager.removeViewImmediate(textView)
        } catch (error: RuntimeException) {
            Log.w(TAG, "오버레이를 닫는 중 창 상태가 바뀌었습니다.", error)
        } finally {
            isShowing = false
            lastRenderedText = ""
        }
    }

    private fun render(text: String) {
        if (text != lastRenderedText) {
            lastRenderedText = text
            if (!isHolding) textView.text = text
        }
        if (isShowing) return

        try {
            windowManager.addView(textView, layoutParams)
            isShowing = true
        } catch (error: RuntimeException) {
            isShowing = false
            Log.e(TAG, "접근성 오버레이를 표시하지 못했습니다.", error)
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginHold()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
        }
        return true
    }

    private fun beginHold() {
        holdHandler.removeCallbacks(holdTick)
        isHolding = true
        holdSecondsRemaining = HOLD_SECONDS
        textView.alpha = 0.88f
        showHoldProgress()
        holdHandler.postDelayed(holdTick, 1_000L)
    }

    private fun cancelHold() {
        holdHandler.removeCallbacks(holdTick)
        if (!isHolding) return
        isHolding = false
        textView.alpha = 1f
        textView.text = lastRenderedText
    }

    private fun showHoldProgress() {
        textView.text =
            "저장 기록 열기: ${holdSecondsRemaining}초\n" +
            "계속 누르고 있으세요\n" +
            "손을 떼면 취소됩니다"
    }

    private fun openSavedSession() {
        service.startActivity(
            Intent(service, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
        )
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "ZetaReader"
        private const val MAX_BODY_LENGTH = 96
        private const val HOLD_SECONDS = 5
    }
}

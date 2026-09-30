package com.example.zetareader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 접근성 권한 상태, 측정 시간, 분류된 대화를 보여주는 간단한 확인 화면. */
class MainActivity : Activity() {
    private lateinit var serviceStatus: TextView
    private lateinit var jevCredentialStatus: TextView
    private lateinit var timingStatus: TextView
    private lateinit var conversation: TextView

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshCapture()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(32, 70, 120)
        setContentView(buildContent())

        val filter = IntentFilter(CaptureStore.ACTION_UPDATED)
        registerUpdateReceiver(filter)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUpdateReceiver(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
        refreshJevCredentialStatus()
        refreshCapture()
    }

    override fun onDestroy() {
        unregisterReceiver(updateReceiver)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }

        outer.addView(TextView(this).apply {
            text = "Zeta Dialogue Reader"
            textSize = 26f
            setTextColor(Color.rgb(25, 35, 50))
        })
        outer.addView(TextView(this).apply {
            text = "제타가 앞에 떠 있는 동안 0.5초 간격으로 대화 변화를 확인합니다.\n오버레이를 5초간 누르면 이 화면에서 현재 대화를 볼 수 있습니다."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(14))
        })

        serviceStatus = TextView(this).apply {
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        outer.addView(serviceStatus, matchWidthWrapHeight())

        outer.addView(Button(this).apply {
            text = "1. 접근성 설정 열기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, matchWidthWrapHeight())

        jevCredentialStatus = TextView(this).apply {
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        outer.addView(jevCredentialStatus, matchWidthWrapHeight())

        outer.addView(Button(this).apply {
            text = "2. TypeSafe API 키 저장/교체"
            setOnClickListener { showApiKeyDialog() }
        }, matchWidthWrapHeight())

        outer.addView(Button(this).apply {
            text = "3. 현재 JEV 범위 다시 분석"
            setOnClickListener { retryJev() }
        }, matchWidthWrapHeight())

        outer.addView(Button(this).apply {
            text = "4. 제타 열기"
            setOnClickListener { openZeta() }
        }, matchWidthWrapHeight())

        outer.addView(Button(this).apply {
            text = "5. 최근 결과 새로고침"
            setOnClickListener { refreshCapture() }
        }, matchWidthWrapHeight())

        timingStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(20, 70, 120))
            setPadding(0, dp(12), 0, dp(8))
        }
        outer.addView(timingStatus, matchWidthWrapHeight())

        conversation = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(30, 35, 40))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        outer.addView(ScrollView(this).apply {
            addView(conversation, matchWidthWrapHeight())
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return outer
    }

    private fun refreshServiceStatus() {
        val enabled = isReaderServiceEnabled()
        serviceStatus.text = if (enabled) {
            "접근성 서비스: 켜짐 · 실시간 감시 준비됨\n오버레이 밖의 제타 화면은 평소처럼 조작할 수 있습니다."
        } else {
            "접근성 서비스: 꺼짐\n아래 버튼을 누르고 ‘제타 대화 읽기’를 허용하세요."
        }
        serviceStatus.setTextColor(if (enabled) Color.rgb(20, 120, 60) else Color.rgb(180, 60, 40))
    }

    private fun refreshJevCredentialStatus() {
        val configured = JevApiKeyStore.isConfigured(this)
        jevCredentialStatus.text = if (configured) {
            "TypeSafe API 키: 이 휴대폰에 보안 저장됨\nJEV 직접 연결 준비됨 · 컴퓨터 연결 불필요"
        } else {
            "TypeSafe API 키: 미설정\n아래 버튼에서 키를 저장해야 JEV를 사용할 수 있습니다."
        }
        jevCredentialStatus.setTextColor(
            if (configured) Color.rgb(20, 120, 60) else Color.rgb(180, 60, 40),
        )
    }

    private fun refreshCapture() {
        val formattedConversation = CaptureStore.readLastFormatted(this)
        val latest = CaptureStore.latest
        val snapshot = CaptureStore.latestSnapshot
        val jev = JevEvaluationCoordinator.latest
        if (snapshot != null) {
            val conversationState = snapshot.let {
                val room = it.conversationId
                    .removePrefix("room:")
                    .let { value -> if (value.length > 12) value.take(12) else value }
                "\n대화방 $room · 리비전 ${it.revision.take(12)} · " +
                    "턴 ${it.turns.size}개 · ${if (it.isReadyForEvaluation) "분석 준비" else "갱신 대기"}"
            }.orEmpty()
            val jevState = when (jev.phase) {
                JevEvaluationPhase.BASELINE -> "JEV: 새 USER RESPONSE 대기 · 기존 내용 미전송"
                JevEvaluationPhase.IDLE -> "JEV: 대화 완성 대기"
                JevEvaluationPhase.WAITING -> "JEV: 내용 안정화 대기"
                JevEvaluationPhase.RUNNING -> "JEV: 분석 중"
                JevEvaluationPhase.SUCCEEDED -> jev.result?.let { result ->
                    "JEV 관찰값 · 자연 종료 ${formatScore(result.naturalBreakpoint)} · " +
                        "맥락 전환 ${formatScore(result.contextShift)} · " +
                        "참여 저하 ${formatScore(result.engagementDrop)}\n" +
                        "JEV 직접 응답 · ${result.model} · " +
                        "${"%.0f".format(result.latencyMs)} ms · " +
                        "토큰 ${result.inputTokens ?: "?"}/${result.outputTokens ?: "?"}"
                } ?: "JEV: 결과 없음"
                JevEvaluationPhase.FAILED -> "JEV 오류: ${jev.error.orEmpty()}"
            }
            val captureState = latest?.let {
                "누적 구간 ${it.messages.size}개 · 분석 ${"%.2f".format(it.scanDurationMs)} ms · " +
                    "감지까지 최대 ${it.eventToCaptureMs} ms · ${it.nodeCount} nodes"
            } ?: "저장된 대화 불러옴"
            timingStatus.text = captureState + conversationState + "\n" + jevState
        } else {
            timingStatus.text = "제타 대화방을 기다리는 중입니다."
        }

        conversation.text = formattedConversation
            ?: "접근성 서비스를 켠 다음 제타의 대화 화면으로 이동하세요."
    }

    private fun formatScore(value: Double): String = "%.3f".format(value)

    private fun isReaderServiceEnabled(): Boolean {
        val expected = ComponentName(this, ZetaAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "TypeSafe API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("TypeSafe API 키")
            .setMessage("키는 이 휴대폰의 Android Keystore로 보호해 저장하며 화면에 다시 표시하지 않습니다.")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                runCatching { JevApiKeyStore.save(this, input.text.toString()) }
                    .onSuccess {
                        refreshJevCredentialStatus()
                        Toast.makeText(this, "API 키를 휴대폰에 저장했습니다.", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            this,
                            error.message ?: "API 키를 저장하지 못했습니다.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
            .show()
    }

    private fun retryJev() {
        if (!JevApiKeyStore.isConfigured(this)) {
            Toast.makeText(this, "TypeSafe API 키를 먼저 저장하세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (!CaptureStore.retryJev(this)) {
            Toast.makeText(
                this,
                "현재 다시 분석할 완성된 JEV 범위가 없습니다.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun openZeta() {
        val intent = packageManager.getLaunchIntentForPackage("com.scatterlab.messenger")
        if (intent == null) {
            Toast.makeText(this, "제타가 설치되어 있지 않습니다.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(intent)
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

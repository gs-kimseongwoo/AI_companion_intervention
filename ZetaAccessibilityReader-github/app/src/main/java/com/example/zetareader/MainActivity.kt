package com.example.zetareader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 접근성 권한 상태, 측정 시간, 분류된 대화를 보여주는 간단한 확인 화면. */
class MainActivity : Activity() {
    private lateinit var serviceStatus: TextView
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
            text = "제타가 앞에 떠 있는 동안 0.5초 간격으로 대화 변화를 확인합니다.\n오버레이를 5초간 누르면 이 화면에서 저장 기록을 볼 수 있습니다."
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

        outer.addView(Button(this).apply {
            text = "2. 제타 열기"
            setOnClickListener { openZeta() }
        }, matchWidthWrapHeight())

        outer.addView(Button(this).apply {
            text = "3. 최근 결과 새로고침"
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

    private fun refreshCapture() {
        val latest = CaptureStore.latest
        if (latest != null) {
            timingStatus.text =
                "누적 구간 ${latest.messages.size}개 · 분석 ${"%.2f".format(latest.scanDurationMs)} ms · " +
                "감지까지 최대 ${latest.eventToCaptureMs} ms · " +
                "${latest.nodeCount} nodes"
        } else {
            timingStatus.text = "새 제타 세션을 기다리는 중입니다."
        }

        conversation.text = CaptureStore.latestFormatted
            ?: CaptureStore.readLastFormatted(this)
            ?: "접근성 서비스를 켠 다음 제타의 대화 화면으로 이동하세요."
    }

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

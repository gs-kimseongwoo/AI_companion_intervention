package com.example.zetareader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64

/** ADB shell만 호출할 수 있는 디버그 데이터셋 입력 통로. */
class DatasetInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val encoded = intent?.getStringExtra(EXTRA_TEXT_BASE64).orEmpty()
        val text = runCatching {
            Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrNull().orEmpty()
        resultCode = if (ZetaAccessibilityService.sendDatasetMessage(text)) {
            Activity.RESULT_OK
        } else {
            Activity.RESULT_CANCELED
        }
    }

    companion object {
        private const val EXTRA_TEXT_BASE64 = "text_base64"
    }
}

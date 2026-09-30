package com.example.zetareader

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal data class JevTurnPayload(
    val speaker: String,
    val text: String,
)

internal fun ConversationSnapshot.toJevTurns(): List<JevTurnPayload> = turns.map { turn ->
    val speaker = when (turn.role) {
        MessageRole.USER -> "human"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.UNKNOWN -> throw IllegalArgumentException("Unknown turn cannot be sent to JEV")
    }
    JevTurnPayload(speaker = speaker, text = turn.text)
}

/**
 * 최신 대화 revision을 휴대폰에서 TypeSafe JEV로 직접 보낸다. 결과는 관찰
 * 화면에만 표시하고 제타 종료나 다른 개입 동작을 실행하지 않는다.
 */
object JevEvaluationCoordinator {
    private const val JEV_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    private const val JEV_MODEL = "jev-latest"
    private const val QUESTIONS_ASSET = "jev_questions.json"
    private const val DEBOUNCE_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val MAX_ERROR_CHARS = 500

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "zeta-jev-evaluator").apply { isDaemon = true }
    }
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    private var activeKey: String? = null
    private var attemptedKey: String? = null
    private val terminalStates = linkedMapOf<String, JevEvaluationState>()

    @Volatile
    var latest: JevEvaluationState = JevEvaluationState()
        private set

    /** 입장 당시 기존 대화는 기준선으로만 등록하고 JEV 요청을 취소한다. */
    fun resetToBaseline(context: Context, conversationId: String) {
        val appContext = context.applicationContext
        synchronized(lock) {
            activeKey = null
            pending?.cancel(false)
            pending = null
            latest = JevEvaluationState(
                phase = JevEvaluationPhase.BASELINE,
                conversationId = conversationId,
            )
        }
        notifyUpdated(appContext)
    }

    fun submit(context: Context, snapshot: ConversationSnapshot) {
        val appContext = context.applicationContext
        val key = snapshot.key()
        var changed = false

        synchronized(lock) {
            if (activeKey != key) {
                activeKey = key
                pending?.cancel(false)
                pending = null
                changed = true
            }

            if (!snapshot.isReadyForEvaluation) {
                latest = JevEvaluationState(
                    phase = JevEvaluationPhase.IDLE,
                    conversationId = snapshot.conversationId,
                    revision = snapshot.revision,
                )
                changed = true
            } else if (!JevApiKeyStore.isConfigured(appContext)) {
                latest = JevEvaluationState(
                    phase = JevEvaluationPhase.FAILED,
                    conversationId = snapshot.conversationId,
                    revision = snapshot.revision,
                    error = "Reader에서 TypeSafe API 키를 먼저 저장하세요.",
                )
                changed = true
            } else if (terminalStates[key] != null) {
                latest = terminalStates.getValue(key)
                changed = true
            } else if (attemptedKey != key && pending == null) {
                latest = JevEvaluationState(
                    phase = JevEvaluationPhase.WAITING,
                    conversationId = snapshot.conversationId,
                    revision = snapshot.revision,
                )
                pending = executor.schedule(
                    { evaluate(appContext, snapshot) },
                    DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS,
                )
                changed = true
            }
        }

        if (changed) notifyUpdated(appContext)
    }

    /** 키 교체나 일시 오류 뒤 현재 JEV 범위를 새 메시지 없이 다시 요청한다. */
    fun retry(context: Context, snapshot: ConversationSnapshot) {
        val key = snapshot.key()
        synchronized(lock) {
            pending?.cancel(false)
            pending = null
            terminalStates.remove(key)
            if (attemptedKey == key) attemptedKey = null
        }
        submit(context.applicationContext, snapshot)
    }

    private fun evaluate(
        context: Context,
        snapshot: ConversationSnapshot,
    ) {
        val key = snapshot.key()
        synchronized(lock) {
            pending = null
            if (activeKey != key) return
            attemptedKey = key
            latest = JevEvaluationState(
                phase = JevEvaluationPhase.RUNNING,
                conversationId = snapshot.conversationId,
                revision = snapshot.revision,
            )
        }
        notifyUpdated(context)

        try {
            val apiKey = JevApiKeyStore.load(context)
                ?: throw IllegalStateException("저장된 TypeSafe API 키를 읽을 수 없습니다.")
            val result = request(context, apiKey, snapshot)
            synchronized(lock) {
                if (activeKey != key) {
                    if (attemptedKey == key) attemptedKey = null
                    return
                }
                latest = JevEvaluationState(
                    phase = JevEvaluationPhase.SUCCEEDED,
                    conversationId = snapshot.conversationId,
                    revision = snapshot.revision,
                    result = result,
                )
                rememberTerminalState(key, latest)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (activeKey != key) {
                    if (attemptedKey == key) attemptedKey = null
                    return
                }
                latest = JevEvaluationState(
                    phase = JevEvaluationPhase.FAILED,
                    conversationId = snapshot.conversationId,
                    revision = snapshot.revision,
                    error = error.message.orEmpty().take(MAX_ERROR_CHARS),
                )
                rememberTerminalState(key, latest)
            }
        }
        notifyUpdated(context)
    }

    private fun request(
        context: Context,
        apiKey: String,
        snapshot: ConversationSnapshot,
    ): JevEvaluationResult {
        val turns = snapshot.toJevTurns()
        val conversationPrefix = JSONArray()
        var latestHumanTurn: JSONObject? = null
        turns.forEachIndexed { index, turn ->
            val json = JSONObject().apply {
                put("turn_index", (index + 1).toString())
                put("speaker", turn.speaker)
                put("text", turn.text)
            }
            conversationPrefix.put(json)
            if (turn.speaker == "human") latestHumanTurn = json
        }
        val state = JSONObject().apply {
            put("conversation_prefix", conversationPrefix)
            put("current_turn", conversationPrefix.getJSONObject(conversationPrefix.length() - 1))
            put("latest_human_turn", latestHumanTurn ?: "No human turn yet.")
        }
        val questions = context.assets.open(QUESTIONS_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val payloadText = JSONObject().apply {
            put("state", state)
            put("model", JEV_MODEL)
            put("questions", questions)
        }.toString()
        val payload = payloadText.toByteArray(Charsets.UTF_8)

        val connection = URL(JEV_ENDPOINT).openConnection() as HttpURLConnection
        val startedAtNs = SystemClock.elapsedRealtimeNanos()
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", "zeta-dialogue-reader/1.0")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("TypeSafe JEV HTTP $status: ${body.take(MAX_ERROR_CHARS)}")
            }
            val json = JSONObject(body)
            val answers = json.getJSONObject("answers")
            val usage = json.optJSONObject("usage")
            return JevEvaluationResult(
                conversationId = snapshot.conversationId,
                revision = snapshot.revision,
                contextShift = answers.noul("context_shift"),
                naturalBreakpoint = answers.noul("natural_breakpoint"),
                engagementDrop = answers.noul("engagement_drop"),
                model = json.optString("model", JEV_MODEL),
                latencyMs = (SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000.0,
                requestLengthChars = payloadText.length,
                inputTokens = usage?.optionalPositiveInt("input_tokens"),
                outputTokens = usage?.optionalPositiveInt("output_tokens"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.noul(name: String): Double {
        val answer = getJSONObject(name)
        check(answer.getString("type") == "noul") { "$name 응답 형식이 noul이 아닙니다." }
        return answer.getDouble("noul").also { value ->
            check(value in 0.0..1.0) { "$name 값이 0~1 범위를 벗어났습니다." }
        }
    }

    private fun JSONObject.optionalPositiveInt(name: String): Int? =
        optInt(name, -1).takeIf { it >= 0 }

    private fun ConversationSnapshot.key(): String = "$conversationId|$revision"

    private fun rememberTerminalState(key: String, state: JevEvaluationState) {
        terminalStates[key] = state
        while (terminalStates.size > 50) {
            terminalStates.remove(terminalStates.keys.first())
        }
    }

    private fun notifyUpdated(context: Context) {
        context.sendBroadcast(
            Intent(CaptureStore.ACTION_UPDATED).setPackage(context.packageName),
        )
    }
}

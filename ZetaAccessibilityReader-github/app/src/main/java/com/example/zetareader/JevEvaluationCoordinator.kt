package com.example.zetareader

import android.content.Context
import android.content.Intent
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
 * 최신 대화 revision만 로컬 브리지에 보낸다. 결과는 관찰 화면에만 표시하고
 * 제타 종료나 다른 개입 동작을 실행하지 않는다.
 */
object JevEvaluationCoordinator {
    private const val DEBOUNCE_MS = 2_000L
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
        val endpoint = BuildConfig.JEV_ENDPOINT
        val key = snapshot.key()
        var changed = false

        synchronized(lock) {
            if (activeKey != key) {
                activeKey = key
                pending?.cancel(false)
                pending = null
                changed = true
            }

            if (endpoint.isBlank() || !snapshot.isReadyForEvaluation) {
                latest = JevEvaluationState(
                    phase = JevEvaluationPhase.IDLE,
                    conversationId = snapshot.conversationId,
                    revision = snapshot.revision,
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
                    { evaluate(appContext, endpoint, snapshot) },
                    DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS,
                )
                changed = true
            }
        }

        if (changed) notifyUpdated(appContext)
    }

    private fun evaluate(
        context: Context,
        endpoint: String,
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
            val result = request(endpoint, snapshot)
            synchronized(lock) {
                if (activeKey != key) {
                    if (attemptedKey == key) attemptedKey = null
                    return
                }
                if (result.conversationId != snapshot.conversationId ||
                    result.revision != snapshot.revision
                ) {
                    throw IllegalStateException("JEV bridge returned a stale revision")
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

    private fun request(endpoint: String, snapshot: ConversationSnapshot): JevEvaluationResult {
        val payload = JSONObject().apply {
            put("conversation_id", snapshot.conversationId)
            put("revision", snapshot.revision)
            put("turns", JSONArray().apply {
                snapshot.toJevTurns().forEach { turn ->
                    put(JSONObject().apply {
                        put("speaker", turn.speaker)
                        put("text", turn.text)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("JEV bridge HTTP $status: ${body.take(MAX_ERROR_CHARS)}")
            }
            val json = JSONObject(body)
            return JevEvaluationResult(
                conversationId = json.getString("conversation_id"),
                revision = json.getString("revision"),
                contextShift = json.getDouble("context_shift"),
                naturalBreakpoint = json.getDouble("natural_breakpoint"),
                engagementDrop = json.getDouble("engagement_drop"),
                model = json.optString("model", "unknown"),
                latencyMs = json.optDouble("latency_ms", Double.NaN),
            )
        } finally {
            connection.disconnect()
        }
    }

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

package com.example.data.gemini

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent
    data class FinalTranscript(val text: String) : LiveServerEvent
    data class InterimTranscript(val text: String) : LiveServerEvent
    data class ServerError(val message: String, val isHighDemand: Boolean) : LiveServerEvent
    data class GoAway(val timeLeft: String?) : LiveServerEvent
    data object Ignored : LiveServerEvent
}

/**
 * Bidirectional WebSocket client for Gemini Live Transcription (`gemini-3.5-transcribe-live`).
 * Streams raw 16kHz 16-bit mono PCM chunks via `BidiGenerateContent` and emits finalized/interim
 * speech transcription events with automatic high-demand/error detection.
 */
class GeminiLiveTranscribeClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) {
    private var webSocket: WebSocket? = null
    private val closed = AtomicBoolean(false)
    private val setupReady = AtomicBoolean(false)

    companion object {
        const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        fun isLikelyHighDemandError(message: String?, httpCode: Int? = null, wsCode: Int? = null): Boolean {
            if (httpCode in setOf(429, 500, 502, 503, 504)) return true
            if (wsCode in setOf(1008, 1011, 1013)) return true
            val lower = message.orEmpty().lowercase()
            return lower.contains("high demand") ||
                lower.contains("overloaded") ||
                lower.contains("unavailable") ||
                lower.contains("resource_exhausted") ||
                lower.contains("resource exhausted") ||
                lower.contains("rate limit") ||
                lower.contains("quota") ||
                lower.contains("too many requests") ||
                lower.contains("503") ||
                lower.contains("429") ||
                lower.contains("try again") ||
                lower.contains("capacity") ||
                lower.contains("not found") ||
                lower.contains("not supported")
        }

        fun buildSetupPayload(modelId: String): String {
            val cleanModel = modelId.trim()
                .removePrefix("models/")
                .ifBlank { GeminiDmnService.DEFAULT_LIVE_TRANSCRIBE_MODEL }
            val setup = JSONObject()
                .put("model", "models/$cleanModel")
                .put(
                    "generationConfig",
                    JSONObject().put("responseModalities", JSONArray().put("TEXT"))
                )
                .put("inputAudioTranscription", JSONObject())
            return JSONObject().put("setup", setup).toString()
        }

        fun buildRealtimeAudioPayload(pcmBase64: String): String {
            val chunk = JSONObject()
                .put("mimeType", "audio/pcm;rate=16000")
                .put("data", pcmBase64)
            val realtimeInput = JSONObject()
                .put("mediaChunks", JSONArray().put(chunk))
            return JSONObject().put("realtimeInput", realtimeInput).toString()
        }

        fun buildAudioStreamEndPayload(): String {
            return JSONObject()
                .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
                .toString()
        }

        fun parseLiveServerMessage(rawJson: String): LiveServerEvent {
            if (rawJson.isBlank()) return LiveServerEvent.Ignored
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return LiveServerEvent.Ignored

            if (root.has("setupComplete")) {
                return LiveServerEvent.SetupComplete
            }

            root.optJSONObject("error")?.let { errObj ->
                val code = errObj.optInt("code", 0)
                val status = errObj.optString("status", "")
                val msg = errObj.optString("message", rawJson.take(240))
                val full = buildString {
                    if (code > 0) append("Error $code ")
                    if (status.isNotBlank()) append("($status) ")
                    append(msg)
                }.trim()
                return LiveServerEvent.ServerError(
                    message = full,
                    isHighDemand = isLikelyHighDemandError(full, httpCode = code.takeIf { it > 0 })
                )
            }

            root.optJSONObject("goAway")?.let { goAway ->
                return LiveServerEvent.GoAway(goAway.optString("timeLeft").takeIf { it.isNotBlank() })
            }

            val serverContent = root.optJSONObject("serverContent") ?: return LiveServerEvent.Ignored

            // 1. Finalized input transcription
            serverContent.optJSONObject("inputTranscription")?.let { trans ->
                val text = trans.optString("text", "")
                if (text.isNotBlank()) {
                    return LiveServerEvent.FinalTranscript(text)
                }
            }

            // 2. Interim real-time transcription
            serverContent.optJSONObject("interimInputTranscription")?.let { interim ->
                val text = interim.optString("text", "")
                if (text.isNotBlank()) {
                    return LiveServerEvent.InterimTranscript(text)
                }
            }

            // 3. Fallback: text parts emitted inside modelTurn (some live configs emit text here)
            serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val partText = parts.optJSONObject(i)?.optString("text").orEmpty()
                    if (partText.isNotBlank()) sb.append(partText)
                }
                val combined = sb.toString()
                if (combined.isNotBlank()) {
                    return LiveServerEvent.FinalTranscript(combined)
                }
            }

            return LiveServerEvent.Ignored
        }
    }

    suspend fun connect(
        apiKey: String,
        liveModelId: String,
        handshakeTimeoutMs: Long = 8_000L,
        onFinalTranscript: (String) -> Unit,
        onInterimTranscript: (String) -> Unit,
        onDisconnectedOrError: (reason: String, isHighDemand: Boolean) -> Unit
    ): Result<Unit> {
        close()
        closed.set(false)
        setupReady.set(false)

        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return Result.failure(IllegalStateException("Google AI Studio API key is missing."))
        }

        val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
        val url = "$WS_BASE_URL?key=$encodedKey"
        val request = Request.Builder().url(url).build()
        val handshakeDeferred = CompletableDeferred<Result<Unit>>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) {
                    webSocket.close(1000, "Client closed")
                    return
                }
                val sent = webSocket.send(buildSetupPayload(liveModelId))
                if (!sent) {
                    val err = IllegalStateException("Failed sending live transcription setup frame.")
                    handshakeDeferred.complete(Result.failure(err))
                    onDisconnectedOrError(err.message ?: "Setup send failed", true)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingPayload(
                    rawJson = text,
                    handshakeDeferred = handshakeDeferred,
                    onFinalTranscript = onFinalTranscript,
                    onInterimTranscript = onInterimTranscript,
                    onDisconnectedOrError = onDisconnectedOrError
                )
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Gemini Live API frequently sends JSON payloads in binary WebSocket frames
                handleIncomingPayload(
                    rawJson = bytes.utf8(),
                    handshakeDeferred = handshakeDeferred,
                    onFinalTranscript = onFinalTranscript,
                    onInterimTranscript = onInterimTranscript,
                    onDisconnectedOrError = onDisconnectedOrError
                )
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (!closed.getAndSet(true)) {
                    val cleanReason = reason.ifBlank { "WebSocket closed (code $code)" }
                    val isHighDemand = isLikelyHighDemandError(cleanReason, wsCode = code)
                    if (!handshakeDeferred.isCompleted) {
                        handshakeDeferred.complete(Result.failure(IllegalStateException(cleanReason)))
                    } else if (code != 1000) {
                        onDisconnectedOrError(cleanReason, isHighDemand)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.getAndSet(true)) {
                    val cleanReason = reason.ifBlank { "Live stream closed (code $code)" }
                    val isHighDemand = isLikelyHighDemandError(cleanReason, wsCode = code)
                    if (!handshakeDeferred.isCompleted) {
                        handshakeDeferred.complete(Result.failure(IllegalStateException(cleanReason)))
                    } else if (code != 1000) {
                        onDisconnectedOrError(cleanReason, isHighDemand)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed.getAndSet(true)) {
                    val httpCode = response?.code
                    val bodySnippet = runCatching { response?.body?.string()?.take(240) }.getOrNull().orEmpty()
                    val reason = buildString {
                        if (httpCode != null) append("HTTP $httpCode ")
                        append(t.message ?: t.javaClass.simpleName)
                        if (bodySnippet.isNotBlank()) append(" — $bodySnippet")
                    }.trim()
                    val highDemand = isLikelyHighDemandError(reason, httpCode = httpCode)
                    if (!handshakeDeferred.isCompleted) {
                        handshakeDeferred.complete(Result.failure(IllegalStateException(reason, t)))
                    } else {
                        onDisconnectedOrError(reason, highDemand)
                    }
                }
            }
        }

        webSocket = client.newWebSocket(request, listener)

        val result = withTimeoutOrNull(handshakeTimeoutMs) {
            handshakeDeferred.await()
        }
        return if (result == null) {
            close()
            Result.failure(IllegalStateException("Timed out connecting to $liveModelId (${handshakeTimeoutMs / 1000}s)."))
        } else {
            if (result.isFailure) close()
            result
        }
    }

    private fun handleIncomingPayload(
        rawJson: String,
        handshakeDeferred: CompletableDeferred<Result<Unit>>,
        onFinalTranscript: (String) -> Unit,
        onInterimTranscript: (String) -> Unit,
        onDisconnectedOrError: (reason: String, isHighDemand: Boolean) -> Unit
    ) {
        if (closed.get()) return
        when (val event = parseLiveServerMessage(rawJson)) {
            LiveServerEvent.SetupComplete -> {
                setupReady.set(true)
                if (!handshakeDeferred.isCompleted) {
                    handshakeDeferred.complete(Result.success(Unit))
                }
            }
            is LiveServerEvent.FinalTranscript -> {
                if (!setupReady.getAndSet(true) && !handshakeDeferred.isCompleted) {
                    handshakeDeferred.complete(Result.success(Unit))
                }
                onFinalTranscript(event.text)
            }
            is LiveServerEvent.InterimTranscript -> {
                if (!setupReady.getAndSet(true) && !handshakeDeferred.isCompleted) {
                    handshakeDeferred.complete(Result.success(Unit))
                }
                onInterimTranscript(event.text)
            }
            is LiveServerEvent.ServerError -> {
                closed.set(true)
                runCatching { webSocket?.close(1011, event.message.take(100)) }
                webSocket = null
                if (!handshakeDeferred.isCompleted) {
                    handshakeDeferred.complete(Result.failure(IllegalStateException(event.message)))
                } else {
                    onDisconnectedOrError(event.message, event.isHighDemand)
                }
            }
            is LiveServerEvent.GoAway -> {
                closed.set(true)
                runCatching { webSocket?.close(1000, "Server goAway") }
                webSocket = null
                onDisconnectedOrError("Live session reset by server (${event.timeLeft ?: "goAway"})", true)
            }
            LiveServerEvent.Ignored -> Unit
        }
    }

    fun sendPcmChunk(pcmBytes: ByteArray, length: Int = pcmBytes.size): Boolean {
        if (closed.get() || !setupReady.get() || length <= 0) return false
        val ws = webSocket ?: return false
        val base64 = Base64.encodeToString(pcmBytes, 0, length, Base64.NO_WRAP)
        return ws.send(buildRealtimeAudioPayload(base64))
    }

    fun sendAudioStreamEnd() {
        if (closed.get() || !setupReady.get()) return
        runCatching { webSocket?.send(buildAudioStreamEndPayload()) }
    }

    fun close() {
        closed.set(true)
        setupReady.set(false)
        val ws = webSocket
        webSocket = null
        if (ws != null) {
            runCatching { ws.close(1000, "Done") }
        }
    }
}

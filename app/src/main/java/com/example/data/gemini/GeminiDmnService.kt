package com.example.data.gemini

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class GeminiGenerationResult(
    val markdown: String,
    val rawTranscript: String,
    val modelUsed: String,
    val extractedWikiLinks: List<String>,
    val debugDetails: String = ""
)

data class GeminiStepUpdate(
    val stepId: String,
    val apiCall: String,
    val statusText: String,
    val stepStatus: String, // "IN_PROGRESS", "RETRYING", "COMPLETED", "FAILED"
    val attemptNumber: Int = 1,
    val errorDetail: String? = null,
    val modelId: String? = null,
    val requestSummary: String? = null,
    val responsePayload: String? = null,
    val historyEvent: String? = null
)

class RetryControlChannel {
    private val pendingModel = AtomicReference<String?>(null)
    private val immediateRetryRequested = AtomicReference(false)
    private val wakeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val activeCall = AtomicReference<Call?>(null)

    fun requestModelSwitch(newModelId: String, interruptActiveCall: Boolean) {
        val clean = newModelId.trim()
        if (clean.isBlank()) return
        pendingModel.set(clean)
        immediateRetryRequested.set(true)
        wakeSignal.tryEmit(Unit)
        if (interruptActiveCall) {
            activeCall.get()?.cancel()
        }
    }

    fun requestImmediateRetry() {
        immediateRetryRequested.set(true)
        wakeSignal.tryEmit(Unit)
    }

    internal fun attachCall(call: Call?) {
        activeCall.set(call)
    }

    internal fun hasPendingModel(): Boolean = pendingModel.get() != null

    internal fun consumePendingModel(): String? = pendingModel.getAndSet(null)

    internal fun consumeImmediateRetryFlag(): Boolean = immediateRetryRequested.getAndSet(false)

    internal suspend fun waitOrInterrupt(waitMs: Long): Boolean {
        if (waitMs <= 0L) return true
        return withTimeoutOrNull(waitMs) {
            wakeSignal.first()
            true
        } ?: false
    }
}

class GeminiApiException(
    message: String,
    val debugDetails: String,
    cause: Throwable? = null
) : IOException(message, cause)

class GeminiDmnService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    fun readTimeoutSeconds(): Long = client.readTimeoutMillis / 1000L

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com"
        private val WIKILINK_EXTRACT_REGEX = Regex("""(?<!!)\[\[([^\[\]|#]+)(?:#[^\[\]|]+)?(?:\|[^\[\]]+)?]]""")
        private val TRANSIENT_HTTP_CODES = setOf(429, 500, 502, 503, 504)
        const val DEFAULT_TRANSCRIBE_MODEL = "gemini-3.5-transcribe"
        const val DEFAULT_LIVE_TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live"
        const val CONNECT_TIMEOUT_SECONDS = 60L
        const val READ_WRITE_TIMEOUT_SECONDS = 360L // 6 minutes (> 5 min) per request
        const val MAX_RETRY_WINDOW_MS = 300_000L // 5-minute retry window for transient demand/rate spikes
        private const val MAX_BACKOFF_MS = 30_000L
    }

    suspend fun testApiKey(apiKey: String, modelId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotBlank()) { "Please enter your Google AI Studio API key." }
            val cleanModel = modelId.trim().ifBlank { "gemini-3.5-flash" }
            val endpoint = "$BASE_URL/v1beta/models/$cleanModel"
            val request = Request.Builder()
                .url(endpoint)
                .header("x-goog-api-key", cleanKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errDetail = parseGeminiErrorDetails(response.code, cleanModel, bodyStr)
                    val debug = buildString {
                        appendLine("Method: GET")
                        appendLine("Endpoint: $endpoint")
                        appendLine("Model: $cleanModel")
                        appendLine("HTTP Status: ${response.code} ${response.message}")
                        appendLine("Response Body:")
                        append(bodyStr.ifBlank { "(empty)" })
                    }
                    throw GeminiApiException("API check failed — $errDetail", debug)
                }
                val json = JSONObject(bodyStr)
                val displayName = json.optString("displayName", cleanModel)
                "Connected ($displayName)"
            }
        }
    }

    suspend fun fetchAvailableModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotBlank()) { "Please enter your Google AI Studio API key first." }
            val request = Request.Builder()
                .url("$BASE_URL/v1beta/models?pageSize=100")
                .header("x-goog-api-key", cleanKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errDetail = parseGeminiErrorDetails(response.code, null, bodyStr)
                    throw IOException("Could not list models — $errDetail")
                }
                parseAvailableModelsResponse(bodyStr)
            }
        }
    }

    fun parseAvailableModelsResponse(bodyStr: String): List<String> {
        val root = JSONObject(bodyStr)
        val arr = root.optJSONArray("models") ?: return emptyList()
        val collected = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawName = obj.optString("name", "")
            val id = rawName.removePrefix("models/").trim()
            if (id.isBlank()) continue
            val methods = obj.optJSONArray("supportedGenerationMethods")
            var supportsGenerate = false
            if (methods != null) {
                for (m in 0 until methods.length()) {
                    if (methods.optString(m) == "generateContent") {
                        supportsGenerate = true
                        break
                    }
                }
            }
            if (!supportsGenerate) continue
            val lower = id.lowercase()
            if (lower.contains("embedding") ||
                lower.contains("imagen") ||
                lower.contains("veo") ||
                lower.contains("aqa") ||
                lower.contains("tts") ||
                lower.contains("robotics")
            ) continue
            collected.add(id)
        }
        val preferredOrder = listOf(
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-flash-lite-latest",
            "gemini-flash-latest"
        )
        return collected.distinct().sortedWith(
            compareBy<String>(
                { model ->
                    val idx = preferredOrder.indexOf(model)
                    if (idx >= 0) idx else Int.MAX_VALUE
                },
                { model -> if (model.contains("flash-lite", ignoreCase = true)) 0 else 1 },
                { model -> if (model.contains("flash", ignoreCase = true)) 0 else 1 },
                { model -> model }
            )
        )
    }

    /**
     * Transcribes an inline WAV audio chunk directly using the dedicated transcription model
     * (default: gemini-3.5-transcribe). Used for batched fallback & pending audio catch-up.
     */
    suspend fun transcribeInlineWavChunk(
        apiKey: String,
        transcribeModelId: String = DEFAULT_TRANSCRIBE_MODEL,
        wavBytes: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotBlank()) { "Google AI Studio API key is missing." }
            require(wavBytes.size > 44) { "Audio chunk is empty." }

            val cleanModel = transcribeModelId.trim().ifBlank { DEFAULT_TRANSCRIBE_MODEL }
            val endpointUrl = "$BASE_URL/v1beta/models/$cleanModel:generateContent"
            val audioBase64 = Base64.getEncoder().encodeToString(wavBytes)

            val partsArray = JSONArray().put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "audio/wav")
                        .put("data", audioBase64)
                )
            )
            val payload = JSONObject().put(
                "contents",
                JSONArray().put(JSONObject().put("parts", partsArray))
            )

            val request = Request.Builder()
                .url(endpointUrl)
                .header("x-goog-api-key", cleanKey)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val details = parseGeminiErrorDetails(response.code, cleanModel, bodyStr)
                    throw GeminiApiException(
                        message = "Transcription error — $details",
                        debugDetails = "POST $endpointUrl\nHTTP ${response.code} ${response.message}\n${bodyStr.take(2500)}"
                    )
                }

                val json = JSONObject(bodyStr)
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@use ""
                }
                val parts = candidates.getJSONObject(0)
                    .optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: return@use ""

                buildString {
                    for (i in 0 until parts.length()) {
                        append(parts.getJSONObject(i).optString("text", ""))
                    }
                }.trim()
            }
        }
    }

    suspend fun processAudioToDmnMarkdown(
        apiKey: String,
        modelId: String,
        audioUri: Uri,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        existingNodes: List<String>,
        timestampStr: String,
        onStageChanged: suspend (stage: String) -> Unit,
        onStatusDetail: (suspend (String) -> Unit)? = null,
        onStepUpdate: (suspend (GeminiStepUpdate) -> Unit)? = null,
        retryControl: RetryControlChannel? = null,
        transcribeModelId: String = DEFAULT_TRANSCRIBE_MODEL,
        precomputedTranscript: String? = null
    ): GeminiGenerationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "Google AI Studio API key is missing." }
        val cleanFlashModel = modelId.trim().ifBlank { "gemini-3.5-flash" }
        val cleanTranscribeModel = transcribeModelId.trim().ifBlank { DEFAULT_TRANSCRIBE_MODEL }

        if (!precomputedTranscript.isNullOrBlank()) {
            onStageChanged("SYNTHESIZING")
            val prompt = buildVoiceTranscriptToDmnPrompt(precomputedTranscript.trim(), existingNodes, timestampStr)
            val graphOutput = callGenerateContent(
                apiKey = cleanKey,
                initialModelId = cleanFlashModel,
                fileUri = null,
                mimeType = null,
                prompt = prompt,
                onStatusDetail = onStatusDetail,
                onStepUpdate = onStepUpdate,
                retryControl = retryControl
            )
            val cleanedMarkdown = cleanFences(graphOutput.text)
            val links = WIKILINK_EXTRACT_REGEX.findAll(cleanedMarkdown)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            return@withContext GeminiGenerationResult(
                markdown = cleanedMarkdown,
                rawTranscript = precomputedTranscript.trim(),
                modelUsed = "Draft -> ${graphOutput.modelUsed}",
                extractedWikiLinks = links,
                debugDetails = graphOutput.debugDetails
            )
        }

        // Stage 1: Upload audio -> dedicated transcription model (gemini-3.5-transcribe)
        onStageChanged("UPLOADING")
        val uploaded = uploadAudioResumable(
            apiKey = cleanKey,
            audioUri = audioUri,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            onStepUpdate = onStepUpdate
        )

        val transcribeOutput = try {
            onStageChanged("PROCESSING_REMOTE")
            val activeFile = waitForFileActive(cleanKey, uploaded.name, onStepUpdate)

            onStageChanged("TRANSCRIBING")
            callTranscribeFileContent(
                apiKey = cleanKey,
                initialTranscribeModelId = cleanTranscribeModel,
                fileUri = activeFile.uri,
                mimeType = activeFile.mimeType.ifBlank { mimeType },
                onStatusDetail = onStatusDetail,
                onStepUpdate = onStepUpdate,
                retryControl = retryControl
            )
        } finally {
            // Privacy rule: delete temporary audio from Google servers right after transcription
            onStepUpdate?.invoke(
                GeminiStepUpdate(
                    stepId = "cleanup_remote",
                    apiCall = "DELETE $BASE_URL/v1beta/${uploaded.name}",
                    statusText = "Removing temporary audio from Gemini Files API...",
                    stepStatus = "IN_PROGRESS",
                    requestSummary = "DELETE $BASE_URL/v1beta/${uploaded.name}"
                )
            )
            deleteRemoteFileQuietly(cleanKey, uploaded.name)
            onStepUpdate?.invoke(
                GeminiStepUpdate(
                    stepId = "cleanup_remote",
                    apiCall = "DELETE $BASE_URL/v1beta/${uploaded.name}",
                    statusText = "Temporary audio removed from Gemini servers",
                    stepStatus = "COMPLETED",
                    responsePayload = "HTTP 200 OK (temporary file deleted)",
                    historyEvent = "Cleaned temporary file ${uploaded.name} from Gemini servers"
                )
            )
        }

        val cleanTranscript = transcribeOutput.text.trim()
        require(cleanTranscript.isNotBlank()) { "Transcription returned empty text." }

        // Stage 2: Dedicated Flash model for Transcript -> DMN Knowledge Graph Markdown
        onStageChanged("SYNTHESIZING")
        val prompt = buildVoiceTranscriptToDmnPrompt(cleanTranscript, existingNodes, timestampStr)
        val graphOutput = callGenerateContent(
            apiKey = cleanKey,
            initialModelId = cleanFlashModel,
            fileUri = null,
            mimeType = null,
            prompt = prompt,
            onStatusDetail = onStatusDetail,
            onStepUpdate = onStepUpdate,
            retryControl = retryControl
        )

        val cleanedMarkdown = cleanFences(graphOutput.text)
        val links = WIKILINK_EXTRACT_REGEX.findAll(cleanedMarkdown)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        GeminiGenerationResult(
            markdown = cleanedMarkdown,
            rawTranscript = cleanTranscript,
            modelUsed = "${transcribeOutput.modelUsed} -> ${graphOutput.modelUsed}",
            extractedWikiLinks = links,
            debugDetails = buildString {
                appendLine("=== Stage 1: Speech Transcription ===")
                appendLine(transcribeOutput.debugDetails)
                appendLine()
                appendLine("=== Stage 2: DMN Graph Synthesis ===")
                append(graphOutput.debugDetails)
            }.trim()
        )
    }

    suspend fun processTextToDmnMarkdown(
        apiKey: String,
        modelId: String,
        rawText: String,
        existingNodes: List<String>,
        timestampStr: String,
        onStageChanged: suspend (stage: String) -> Unit,
        onStatusDetail: (suspend (String) -> Unit)? = null,
        onStepUpdate: (suspend (GeminiStepUpdate) -> Unit)? = null,
        retryControl: RetryControlChannel? = null,
        isVoiceTranscript: Boolean = false
    ): GeminiGenerationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "Google AI Studio API key is missing." }
        require(rawText.isNotBlank()) { "Input text is empty." }
        val cleanModel = modelId.trim().ifBlank { "gemini-3.5-flash" }

        onStageChanged("SYNTHESIZING")
        val prompt = if (isVoiceTranscript) {
            buildVoiceTranscriptToDmnPrompt(rawText, existingNodes, timestampStr)
        } else {
            buildTextDmnPrompt(rawText, existingNodes, timestampStr)
        }
        val callOutput = callGenerateContent(
            apiKey = cleanKey,
            initialModelId = cleanModel,
            fileUri = null,
            mimeType = null,
            prompt = prompt,
            onStatusDetail = onStatusDetail,
            onStepUpdate = onStepUpdate,
            retryControl = retryControl
        )

        val cleanedMarkdown = cleanFences(callOutput.text)
        val links = WIKILINK_EXTRACT_REGEX.findAll(cleanedMarkdown)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        GeminiGenerationResult(
            markdown = cleanedMarkdown,
            rawTranscript = rawText,
            modelUsed = callOutput.modelUsed,
            extractedWikiLinks = links,
            debugDetails = callOutput.debugDetails
        )
    }

    private data class UploadedGeminiFile(
        val name: String,
        val uri: String,
        val mimeType: String,
        val state: String
    )

    private suspend fun uploadAudioResumable(
        apiKey: String,
        audioUri: Uri,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        onStepUpdate: (suspend (GeminiStepUpdate) -> Unit)? = null
    ): UploadedGeminiFile {
        val actualSize = if (sizeBytes > 0) sizeBytes else {
            context.contentResolver.openAssetFileDescriptor(audioUri, "r")?.use { it.length } ?: -1L
        }
        require(actualSize > 0L) { "Audio file is empty or unreadable." }

        val startUrl = "$BASE_URL/upload/v1beta/files"
        val startPayload = JSONObject().put(
            "file",
            JSONObject().put("display_name", displayName)
        ).toString()
        val startReqSummary = "POST $startUrl\nHeaders: X-Goog-Upload-Protocol=resumable, Command=start, Content-Length=$actualSize, Type=$mimeType\nBody: $startPayload"

        onStepUpdate?.invoke(
            GeminiStepUpdate(
                stepId = "upload_start",
                apiCall = "POST $startUrl (resumable start)",
                statusText = "Starting resumable upload session (${actualSize / 1024} KB, $mimeType)...",
                stepStatus = "IN_PROGRESS",
                requestSummary = startReqSummary
            )
        )

        val startRequest = Request.Builder()
            .url(startUrl)
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", actualSize.toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(startPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val uploadUrl = client.newCall(startRequest).execute().use { response ->
            val respBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = parseGeminiError(respBody) ?: "HTTP ${response.code}"
                val rawResp = "HTTP ${response.code} ${response.message}\n${respBody.ifBlank { "(empty)" }.take(2500)}"
                onStepUpdate?.invoke(
                    GeminiStepUpdate(
                        stepId = "upload_start",
                        apiCall = "POST $startUrl",
                        statusText = "Failed initiating upload: $err",
                        stepStatus = "FAILED",
                        errorDetail = err,
                        responsePayload = rawResp,
                        historyEvent = "Resumable upload start failed (HTTP ${response.code}): $err"
                    )
                )
                throw IOException("Failed to initiate Gemini audio upload: $err")
            }
            val urlHeader = response.header("X-Goog-Upload-URL")
                ?: throw IOException("Gemini upload endpoint did not return X-Goog-Upload-URL.")
            onStepUpdate?.invoke(
                GeminiStepUpdate(
                    stepId = "upload_start",
                    apiCall = "POST $startUrl",
                    statusText = "Upload session created",
                    stepStatus = "COMPLETED",
                    responsePayload = "HTTP ${response.code} ${response.message}\nX-Goog-Upload-URL: ${urlHeader.take(120)}...\n${respBody.ifBlank { "(session URL returned in header)" }}",
                    historyEvent = "Initiated Gemini resumable upload session (HTTP ${response.code})"
                )
            )
            urlHeader
        }

        val shortUploadEndpoint = "$BASE_URL/upload/v1beta/files (upload, finalize)"
        onStepUpdate?.invoke(
            GeminiStepUpdate(
                stepId = "upload_bytes",
                apiCall = "POST $shortUploadEndpoint",
                statusText = "Streaming ${actualSize / 1024} KB audio payload to Gemini Files API...",
                stepStatus = "IN_PROGRESS",
                requestSummary = "POST $shortUploadEndpoint\nHeaders: X-Goog-Upload-Offset=0, Command=upload, finalize\nStream: $actualSize bytes ($mimeType)"
            )
        )

        val streamingBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun contentLength() = actualSize
            override fun writeTo(sink: BufferedSink) {
                val stream = context.contentResolver.openInputStream(audioUri)
                    ?: throw IOException("Unable to open local audio file for upload.")
                stream.use { input ->
                    sink.writeAll(input.source())
                }
            }
        }

        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(streamingBody)
            .build()

        return client.newCall(uploadRequest).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            val rawResp = "HTTP ${response.code} ${response.message}\n${bodyStr.ifBlank { "(empty)" }.take(3000)}"
            if (!response.isSuccessful) {
                val err = parseGeminiError(bodyStr) ?: "HTTP ${response.code}"
                onStepUpdate?.invoke(
                    GeminiStepUpdate(
                        stepId = "upload_bytes",
                        apiCall = "POST $shortUploadEndpoint",
                        statusText = "Audio upload failed: $err",
                        stepStatus = "FAILED",
                        errorDetail = err,
                        responsePayload = rawResp,
                        historyEvent = "Audio payload upload failed (HTTP ${response.code}): $err"
                    )
                )
                throw IOException("Audio upload failed: $err")
            }
            val parsed = parseFileResponse(JSONObject(bodyStr))
            onStepUpdate?.invoke(
                GeminiStepUpdate(
                    stepId = "upload_bytes",
                    apiCall = "POST $shortUploadEndpoint",
                    statusText = "Uploaded ${actualSize / 1024} KB -> ${parsed.name}",
                    stepStatus = "COMPLETED",
                    responsePayload = rawResp,
                    historyEvent = "Uploaded ${actualSize / 1024} KB -> ${parsed.name} (state=${parsed.state})"
                )
            )
            parsed
        }
    }

    private suspend fun waitForFileActive(
        apiKey: String,
        fileName: String,
        onStepUpdate: (suspend (GeminiStepUpdate) -> Unit)? = null
    ): UploadedGeminiFile {
        val statusUrl = "$BASE_URL/v1beta/$fileName"
        repeat(150) { idx -> // Up to 5 minutes (150 * 2s) for audio file indexing
            val pollNum = idx + 1
            onStepUpdate?.invoke(
                GeminiStepUpdate(
                    stepId = "file_active",
                    apiCall = "GET $statusUrl",
                    statusText = "Checking remote file indexing status (poll #$pollNum)...",
                    stepStatus = "IN_PROGRESS",
                    attemptNumber = pollNum,
                    requestSummary = "GET $statusUrl"
                )
            )
            val request = Request.Builder()
                .url(statusUrl)
                .header("x-goog-api-key", apiKey)
                .get()
                .build()

            var lastRawBody = ""
            var lastCode = 200
            val fileInfo = client.newCall(request).execute().use { response ->
                lastCode = response.code
                val bodyStr = response.body?.string().orEmpty()
                lastRawBody = "HTTP ${response.code} ${response.message}\n${bodyStr.ifBlank { "(empty)" }.take(2500)}"
                if (!response.isSuccessful) {
                    val err = parseGeminiError(bodyStr) ?: "HTTP ${response.code}"
                    onStepUpdate?.invoke(
                        GeminiStepUpdate(
                            stepId = "file_active",
                            apiCall = "GET $statusUrl",
                            statusText = "File status check error: $err",
                            stepStatus = "FAILED",
                            attemptNumber = pollNum,
                            errorDetail = err,
                            responsePayload = lastRawBody,
                            historyEvent = "File status check #$pollNum failed (HTTP ${response.code}): $err"
                        )
                    )
                    throw IOException("Failed checking uploaded file status: $err")
                }
                val json = JSONObject(bodyStr)
                // GET /v1beta/files/{id} returns the file object directly (or wrapped in "file")
                if (json.has("file")) parseFileResponse(json) else parseFileObject(json)
            }

            when (fileInfo.state) {
                "ACTIVE" -> {
                    onStepUpdate?.invoke(
                        GeminiStepUpdate(
                            stepId = "file_active",
                            apiCall = "GET $statusUrl",
                            statusText = "Remote file state=ACTIVE (ready for synthesis)",
                            stepStatus = "COMPLETED",
                            attemptNumber = pollNum,
                            responsePayload = lastRawBody,
                            historyEvent = "Remote audio $fileName became ACTIVE (poll #$pollNum, HTTP $lastCode)"
                        )
                    )
                    return fileInfo
                }
                "FAILED" -> {
                    onStepUpdate?.invoke(
                        GeminiStepUpdate(
                            stepId = "file_active",
                            apiCall = "GET $statusUrl",
                            statusText = "Gemini returned state=FAILED",
                            stepStatus = "FAILED",
                            attemptNumber = pollNum,
                            errorDetail = "state=FAILED",
                            responsePayload = lastRawBody,
                            historyEvent = "Gemini rejected audio indexing (state=FAILED)"
                        )
                    )
                    throw IOException("Gemini rejected audio processing (state=FAILED).")
                }
                else -> {
                    onStepUpdate?.invoke(
                        GeminiStepUpdate(
                            stepId = "file_active",
                            apiCall = "GET $statusUrl",
                            statusText = "Remote state=${fileInfo.state}, waiting 2s (poll #$pollNum)...",
                            stepStatus = "IN_PROGRESS",
                            attemptNumber = pollNum,
                            responsePayload = lastRawBody
                        )
                    )
                    delay(2000L)
                }
            }
        }
        throw IOException("Timed out waiting for Gemini audio file indexing.")
    }

    private data class GenerateCallOutput(
        val text: String,
        val modelUsed: String,
        val debugDetails: String
    )

    private suspend fun callTranscribeFileContent(
        apiKey: String,
        initialTranscribeModelId: String,
        fileUri: String,
        mimeType: String,
        onStatusDetail: (suspend (String) -> Unit)? = null,
        onStepUpdate: (suspend (GeminiStepUpdate) -> Unit)? = null,
        retryControl: RetryControlChannel? = null
    ): GenerateCallOutput {
        val partsArray = JSONArray().put(
            JSONObject().put(
                "file_data",
                JSONObject()
                    .put("mime_type", mimeType)
                    .put("file_uri", fileUri)
            )
        )
        val payloadJson = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", partsArray))
        ).toString()

        return executeGenerateWithRetry(
            apiKey = apiKey,
            initialModelId = initialTranscribeModelId.trim().ifBlank { DEFAULT_TRANSCRIBE_MODEL },
            stepId = "transcribe",
            actionLabel = "transcription",
            inputSummary = "$mimeType ($fileUri)",
            payloadJson = payloadJson,
            onStatusDetail = onStatusDetail,
            onStepUpdate = onStepUpdate,
            retryControl = retryControl
        )
    }

    private suspend fun callGenerateContent(
        apiKey: String,
        initialModelId: String,
        fileUri: String?,
        mimeType: String?,
        prompt: String,
        onStatusDetail: (suspend (String) -> Unit)? = null,
        onStepUpdate: (suspend (GeminiStepUpdate) -> Unit)? = null,
        retryControl: RetryControlChannel? = null
    ): GenerateCallOutput {
        val partsArray = JSONArray().apply {
            if (!fileUri.isNullOrBlank() && !mimeType.isNullOrBlank()) {
                put(
                    JSONObject().put(
                        "file_data",
                        JSONObject()
                            .put("mime_type", mimeType)
                            .put("file_uri", fileUri)
                    )
                )
            }
            put(JSONObject().put("text", prompt))
        }
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", partsArray)
                )
            )
            put(
                "generationConfig",
                JSONObject().put("temperature", 0.2)
            )
        }

        return executeGenerateWithRetry(
            apiKey = apiKey,
            initialModelId = initialModelId.trim().ifBlank { "gemini-3.5-flash" },
            stepId = "generate",
            actionLabel = "synthesis",
            inputSummary = "${mimeType ?: "text/plain"}${if (!fileUri.isNullOrBlank()) " ($fileUri)" else ""}",
            payloadJson = payload.toString(),
            onStatusDetail = onStatusDetail,
            onStepUpdate = onStepUpdate,
            retryControl = retryControl
        )
    }

    private suspend fun executeGenerateWithRetry(
        apiKey: String,
        initialModelId: String,
        stepId: String,
        actionLabel: String,
        inputSummary: String,
        payloadJson: String,
        onStatusDetail: (suspend (String) -> Unit)? = null,
        onStepUpdate: (suspend (GeminiStepUpdate) -> Unit)? = null,
        retryControl: RetryControlChannel? = null
    ): GenerateCallOutput {
        var activeModel = initialModelId.trim().ifBlank { "gemini-3.5-flash" }
        val startMs = System.currentTimeMillis()
        var attempt = 0
        var lastError: IOException? = null
        var lastResponsePayload: String? = null

        while (true) {
            retryControl?.consumePendingModel()?.let { newModel ->
                if (newModel != activeModel) {
                    activeModel = newModel
                }
            }
            retryControl?.consumeImmediateRetryFlag()

            attempt++
            val endpointUrl = "$BASE_URL/v1beta/models/$activeModel:generateContent"
            val reqSummary = buildString {
                appendLine("POST $endpointUrl")
                appendLine("Model: $activeModel | Attempt: #$attempt | Stage: $actionLabel")
                appendLine("Input: $inputSummary")
                append("JSON Payload Size: ${payloadJson.length} chars")
            }

            onStepUpdate?.invoke(
                GeminiStepUpdate(
                    stepId = stepId,
                    apiCall = "POST $endpointUrl",
                    statusText = if (attempt == 1) {
                        "Sending $actionLabel request to $activeModel (${payloadJson.length} chars)..."
                    } else {
                        "Attempt #$attempt in flight to $activeModel..."
                    },
                    stepStatus = if (attempt == 1) "IN_PROGRESS" else "RETRYING",
                    attemptNumber = attempt,
                    errorDetail = lastError?.message,
                    modelId = activeModel,
                    requestSummary = reqSummary,
                    responsePayload = lastResponsePayload,
                    historyEvent = "Attempt #$attempt ($actionLabel) started -> $activeModel"
                )
            )

            val request = Request.Builder()
                .url(endpointUrl)
                .header("x-goog-api-key", apiKey)
                .post(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            var retryWaitMs: Long? = null
            var retryStatusMsg: String? = null
            var retryErrorDetail: String? = null
            var retryHistoryEvent: String? = null

            val callResult = try {
                val call = client.newCall(request)
                retryControl?.attachCall(call)
                try {
                    call.execute().use { response ->
                        val bodyStr = response.body?.string().orEmpty()
                        val rawRespSnapshot = "HTTP ${response.code} ${response.message}\n${bodyStr.ifBlank { "(empty body)" }.take(3500)}"
                        lastResponsePayload = rawRespSnapshot
                        val elapsedMs = System.currentTimeMillis() - startMs

                        if (!response.isSuccessful) {
                            val details = parseGeminiErrorDetails(response.code, activeModel, bodyStr)
                            val isTransient = response.code in TRANSIENT_HTTP_CODES
                            val hasQueuedSwitch = retryControl?.hasPendingModel() == true
                            if ((isTransient || hasQueuedSwitch) && elapsedMs < MAX_RETRY_WINDOW_MS) {
                                val rawBackoff = 5000L * (1L shl (attempt - 1).coerceAtMost(4))
                                val waitMs = if (hasQueuedSwitch) {
                                    0L
                                } else {
                                    rawBackoff.coerceAtMost(MAX_BACKOFF_MS)
                                        .coerceAtMost((MAX_RETRY_WINDOW_MS - elapsedMs).coerceAtLeast(1000L))
                                }
                                val elapsedSec = elapsedMs / 1000L
                                val waitSec = (waitMs / 1000L).coerceAtLeast(0L)
                                retryWaitMs = if (waitMs > 0L) waitMs else null
                                retryStatusMsg = if (hasQueuedSwitch) {
                                    "Switching to queued model after HTTP ${response.code} on $activeModel..."
                                } else {
                                    "Gemini $activeModel busy (HTTP ${response.code}), waiting ${waitSec}s before retry #${attempt + 1} (${elapsedSec}s / ${MAX_RETRY_WINDOW_MS / 1000L}s window)..."
                                }
                                retryErrorDetail = details
                                retryHistoryEvent = "Attempt #$attempt ($activeModel) -> HTTP ${response.code}: ${parseGeminiError(bodyStr) ?: response.message}"
                                lastError = IOException(details)
                                return@use null
                            }
                            val elapsedSec = elapsedMs / 1000L
                            val attemptsNote = if (attempt > 1) " (failed after $attempt attempts over ${elapsedSec}s)" else ""
                            val hint = when {
                                response.code == 503 || response.code == 429 ->
                                    "\nTip: Try gemini-3.5-flash, gemini-3.5-flash-lite, or gemini-3.5-transcribe, or tap Load My Key's Models in Sync."
                                details.contains("no longer available", ignoreCase = true) ->
                                    "\nTip: Switch to gemini-3.5-flash, gemini-3.8-flash, or gemini-3.5-flash-lite in Sync."
                                else -> ""
                            }
                            val debugReport = buildString {
                                appendLine("Method: POST")
                                appendLine("Endpoint: $endpointUrl")
                                appendLine("Model: $activeModel")
                                appendLine("HTTP Status: ${response.code} ${response.message}")
                                appendLine("Attempts: $attempt")
                                appendLine("Elapsed: ${elapsedSec}s")
                                appendLine("Input: $inputSummary")
                                appendLine("Payload Size: ${payloadJson.length} chars")
                                appendLine("Response Body:")
                                append(bodyStr.take(4000).ifBlank { "(empty)" })
                            }
                            onStepUpdate?.invoke(
                                GeminiStepUpdate(
                                    stepId = stepId,
                                    apiCall = "POST $endpointUrl",
                                    statusText = "Failed (HTTP ${response.code}) after $attempt attempt(s)",
                                    stepStatus = "FAILED",
                                    attemptNumber = attempt,
                                    errorDetail = details,
                                    modelId = activeModel,
                                    requestSummary = reqSummary,
                                    responsePayload = rawRespSnapshot,
                                    historyEvent = "Attempt #$attempt ($activeModel) failed terminally -> HTTP ${response.code}"
                                )
                            )
                            throw GeminiApiException(
                                message = "Gemini $actionLabel error$attemptsNote — $details$hint",
                                debugDetails = debugReport
                            )
                        }

                        val json = JSONObject(bodyStr)
                        val promptFeedback = json.optJSONObject("promptFeedback")
                        val blockReason = promptFeedback?.optString("blockReason")?.takeIf { it.isNotBlank() }
                        val candidates = json.optJSONArray("candidates")
                        if (candidates == null || candidates.length() == 0) {
                            val feedbackStr = promptFeedback?.toString() ?: bodyStr.take(300)
                            val debugReport = buildString {
                                appendLine("Method: POST")
                                appendLine("Endpoint: $endpointUrl")
                                appendLine("Model: $activeModel")
                                appendLine("HTTP Status: ${response.code}")
                                appendLine("Block Reason: ${blockReason ?: "none"}")
                                appendLine("Response Body:")
                                append(bodyStr.take(4000))
                            }
                            throw GeminiApiException(
                                message = "Gemini returned no candidates [model=$activeModel" +
                                    (if (blockReason != null) ", blockReason=$blockReason" else "") +
                                    "]. Response: $feedbackStr",
                                debugDetails = debugReport
                            )
                        }

                        val firstCandidate = candidates.getJSONObject(0)
                        val finishReason = firstCandidate.optString("finishReason", "")
                        val contentObj = firstCandidate.optJSONObject("content")
                        val parts = contentObj?.optJSONArray("parts")
                        if (parts == null || parts.length() == 0) {
                            throw GeminiApiException(
                                message = "Gemini returned empty content [model=$activeModel, finishReason=$finishReason]. Response: ${bodyStr.take(300)}",
                                debugDetails = "Endpoint: POST $endpointUrl\nHTTP ${response.code}\nFinish Reason: $finishReason\nRaw Response:\n${bodyStr.take(4000)}"
                            )
                        }

                        val text = buildString {
                            for (i in 0 until parts.length()) {
                                append(parts.getJSONObject(i).optString("text", ""))
                            }
                        }.trim()

                        if (text.isEmpty()) {
                            throw GeminiApiException(
                                message = "Gemini returned blank text [model=$activeModel, finishReason=$finishReason].",
                                debugDetails = "Endpoint: POST $endpointUrl\nHTTP ${response.code}\nFinish Reason: $finishReason"
                            )
                        }

                        val usage = json.optJSONObject("usageMetadata")
                        val debugReport = buildString {
                            appendLine("Method: POST")
                            appendLine("Endpoint: $endpointUrl")
                            appendLine("Model: $activeModel")
                            appendLine("HTTP Status: ${response.code} OK")
                            appendLine("Attempts: $attempt")
                            appendLine("Duration: ${elapsedMs / 1000.0}s")
                            appendLine("Finish Reason: ${finishReason.ifBlank { "STOP" }}")
                            appendLine("Input: $inputSummary")
                            if (usage != null) {
                                appendLine(
                                    "Tokens — prompt=${usage.optInt("promptTokenCount")}, " +
                                        "output=${usage.optInt("candidatesTokenCount")}, " +
                                        "total=${usage.optInt("totalTokenCount")}"
                                )
                            }
                            append("Output Characters: ${text.length}")
                        }
                        onStepUpdate?.invoke(
                            GeminiStepUpdate(
                                stepId = stepId,
                                apiCall = "POST $endpointUrl",
                                statusText = "HTTP ${response.code} OK (${text.length} chars via $activeModel)",
                                stepStatus = "COMPLETED",
                                attemptNumber = attempt,
                                modelId = activeModel,
                                requestSummary = reqSummary,
                                responsePayload = rawRespSnapshot,
                                historyEvent = "Attempt #$attempt ($activeModel) succeeded -> HTTP ${response.code} OK (${text.length} chars)"
                            )
                        )
                        GenerateCallOutput(text = text, modelUsed = activeModel, debugDetails = debugReport)
                    }
                } finally {
                    retryControl?.attachCall(null)
                }
            } catch (apiEx: GeminiApiException) {
                throw apiEx
            } catch (ioe: IOException) {
                val switchedModel = retryControl?.consumePendingModel()
                val manualImmediate = retryControl?.consumeImmediateRetryFlag() == true
                if (switchedModel != null || manualImmediate) {
                    val prevModel = activeModel
                    if (switchedModel != null) activeModel = switchedModel
                    retryWaitMs = null
                    retryErrorDetail = "Interrupted attempt on $prevModel to retry with $activeModel"
                    retryStatusMsg = "Switched model to $activeModel; retrying immediately..."
                    retryHistoryEvent = "Cancelled $prevModel -> switching immediately to $activeModel"
                    lastResponsePayload = "Attempt #$attempt on $prevModel interrupted by user to switch model to $activeModel."
                    lastError = IOException(retryErrorDetail)
                    null
                } else {
                    val elapsedMs = System.currentTimeMillis() - startMs
                    if (elapsedMs < MAX_RETRY_WINDOW_MS) {
                        val waitMs = 5000L.coerceAtMost((MAX_RETRY_WINDOW_MS - elapsedMs).coerceAtLeast(1000L))
                        retryWaitMs = waitMs
                        retryStatusMsg = "Network timeout/blip (${ioe.javaClass.simpleName}), retrying in ${waitMs / 1000L}s..."
                        retryErrorDetail = "${ioe.javaClass.simpleName}: ${ioe.message}"
                        retryHistoryEvent = "Attempt #$attempt ($activeModel) network error -> ${ioe.javaClass.simpleName}: ${ioe.message}"
                        lastResponsePayload = "Network Error: ${ioe.javaClass.name}: ${ioe.message}"
                        lastError = ioe
                        null
                    } else {
                        val debugReport = buildString {
                            appendLine("Method: POST")
                            appendLine("Endpoint: $endpointUrl")
                            appendLine("Model: $activeModel")
                            appendLine("Attempts: $attempt")
                            appendLine("Elapsed: ${elapsedMs / 1000L}s")
                            appendLine("Exception: ${ioe.javaClass.name}: ${ioe.message}")
                            lastError?.let { append("Last Transient Error: ${it.message}") }
                        }
                        onStepUpdate?.invoke(
                            GeminiStepUpdate(
                                stepId = stepId,
                                apiCall = "POST $endpointUrl",
                                statusText = "Connection failed after $attempt attempt(s)",
                                stepStatus = "FAILED",
                                attemptNumber = attempt,
                                errorDetail = "${ioe.javaClass.simpleName}: ${ioe.message}",
                                modelId = activeModel,
                                requestSummary = reqSummary,
                                responsePayload = "${ioe.javaClass.name}: ${ioe.message}",
                                historyEvent = "Attempt #$attempt ($activeModel) timed out terminally"
                            )
                        )
                        throw GeminiApiException(
                            message = "Gemini connection error after $attempt attempt(s) over ${elapsedMs / 1000L}s [model=$activeModel]: ${ioe.javaClass.simpleName}: ${ioe.message}",
                            debugDetails = debugReport,
                            cause = ioe
                        )
                    }
                }
            }

            if (callResult != null) return callResult
            retryStatusMsg?.let { msg ->
                onStatusDetail?.invoke(msg)
                onStepUpdate?.invoke(
                    GeminiStepUpdate(
                        stepId = stepId,
                        apiCall = "POST $endpointUrl",
                        statusText = msg,
                        stepStatus = "RETRYING",
                        attemptNumber = attempt,
                        errorDetail = retryErrorDetail,
                        modelId = activeModel,
                        requestSummary = reqSummary,
                        responsePayload = lastResponsePayload,
                        historyEvent = retryHistoryEvent
                    )
                )
            }
            retryWaitMs?.let { waitMs ->
                if (retryControl != null) {
                    retryControl.waitOrInterrupt(waitMs)
                } else {
                    delay(waitMs)
                }
            }
        }
    }


    private fun deleteRemoteFileQuietly(apiKey: String, fileName: String) {
        runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/v1beta/$fileName")
                .header("x-goog-api-key", apiKey)
                .delete()
                .build()
            client.newCall(request).execute().close()
        }
    }

    private fun parseFileResponse(json: JSONObject): UploadedGeminiFile {
        val fileObj = if (json.has("file")) json.getJSONObject("file") else json
        return parseFileObject(fileObj)
    }

    private fun parseFileObject(fileObj: JSONObject): UploadedGeminiFile {
        return UploadedGeminiFile(
            name = fileObj.getString("name"),
            uri = fileObj.getString("uri"),
            mimeType = fileObj.optString("mimeType", "audio/mp4"),
            state = fileObj.optString("state", "ACTIVE")
        )
    }

    private fun parseGeminiError(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun parseGeminiErrorDetails(httpCode: Int, modelId: String?, body: String?): String {
        val modelTag = if (!modelId.isNullOrBlank()) " [model=$modelId]" else ""
        if (body.isNullOrBlank()) {
            return "HTTP $httpCode$modelTag: Empty error response body from Gemini API."
        }
        return runCatching {
            val root = JSONObject(body)
            val errObj = root.optJSONObject("error")
            if (errObj == null) {
                "HTTP $httpCode$modelTag: ${body.trim().take(400)}"
            } else {
                val apiCode = errObj.optInt("code", httpCode)
                val status = errObj.optString("status", "").trim()
                val msg = errObj.optString("message", "").trim().ifBlank { "Unknown Gemini API error" }
                val statusPart = if (status.isNotEmpty()) " ($status)" else ""
                val detailsArr = errObj.optJSONArray("details")
                val detailsText = if (detailsArr != null && detailsArr.length() > 0) {
                    " | Details: ${detailsArr.toString().take(300)}"
                } else {
                    ""
                }
                "HTTP $apiCode$statusPart$modelTag: $msg$detailsText"
            }
        }.getOrElse {
            "HTTP $httpCode$modelTag: ${body.trim().take(400)}"
        }
    }

    private fun cleanFences(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            trimmed.lines().drop(1).dropLast(1).joinToString("\n").trim()
        } else {
            trimmed
        }
    }

    fun parseAudioDualOutput(rawResponse: String): Pair<String, String> {
        val rawMatch = Regex("""<RAW_TRANSCRIPT>\s*([\s\S]*?)\s*</RAW_TRANSCRIPT>""", RegexOption.IGNORE_CASE)
            .find(rawResponse)
        val dmnMatch = Regex("""<DMN_MARKDOWN>\s*([\s\S]*?)\s*</DMN_MARKDOWN>""", RegexOption.IGNORE_CASE)
            .find(rawResponse)

        val dmnMarkdown = cleanFences(dmnMatch?.groupValues?.get(1) ?: rawResponse)
        val rawTranscript = rawMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: run {
                val markers = listOf(
                    "### Stream of Consciousness (Cleaned)",
                    "### 🎙️ Stream of Consciousness (Cleaned)"
                )
                val markerHit = markers.firstNotNullOfOrNull { marker ->
                    dmnMarkdown.indexOf(marker).takeIf { it >= 0 }?.let { idx -> idx + marker.length }
                }
                if (markerHit != null) {
                    dmnMarkdown.substring(markerHit).trim()
                } else {
                    dmnMarkdown
                }
            }
        return rawTranscript to dmnMarkdown
    }

    fun buildDmnPrompt(existingNodes: List<String>, timestampStr: String): String {
        val nodesContext = if (existingNodes.isNotEmpty()) {
            existingNodes.take(500).joinToString(", ") { "[[$it]]" }
        } else {
            "None yet (propose new atomic [[Concept]] links freely)"
        }

        return """
You are an executive Default Mode Network (DMN) cognitive synthesizer and personal knowledge graph architect for an Obsidian vault.

### EXISTING KNOWLEDGE GRAPH NODES IN VAULT:
$nodesContext

### YOUR TASK:
Listen to the attached raw voice recording and produce TWO clearly delimited outputs:
1. `<RAW_TRANSCRIPT>`: A Gboard Rambler-grade clean prose transcript of the voice recording.
   - Strip all filler vocalizations ("um", "uh", "er", "like", "you know"), stutters, false starts, accidental word/phrase repetitions, and spoken meta-instructions to the recorder (such as "scratch that", "ignore that part", or dictation commands — apply corrections seamlessly rather than transcribing the command itself).
   - Preserve the speaker's exact voice, ideas, vocabulary, reasoning, and full substantive detail in clean, readable first-person prose paragraphs without summarizing away substance.
   - Do NOT include `[[WikiLinks]]` or emojis inside `<RAW_TRANSCRIPT>`.
2. `<DMN_MARKDOWN>`: A clean, professional Obsidian Markdown entry following the rules below.

### STRICT FORMATTING RULE:
- Do NOT use any emojis anywhere in `<RAW_TRANSCRIPT>` or `<DMN_MARKDOWN>` (zero icons in headings, bullets, tags, or body text). Keep the tone crisp, analytical, and professional.

Rules for `<DMN_MARKDOWN>`:
1. **PART 1: Rambler-Grade Clean Prose (`### Stream of Consciousness (Cleaned)`)**
   - Present the clean, articulate first-person ("I") prose version of the session, organized into logical paragraphs without filler words, repetitions, or dictation commands.
   - Preserve full intellectual depth without over-summarizing.
   - **Bi-Directional Graph Linking:** Whenever a concept, person, project, recurring problem, or mental model matches an item in `EXISTING KNOWLEDGE GRAPH NODES` (or warrants a new evergreen atomic topic node), naturally wrap it in Obsidian WikiLinks like `[[Concept Name]]` directly inline.

2. **PART 2: DMN Cognitive Synthesis (`### DMN Synthesis & Graph Connections`)**
   Structure the deeper synthesis into four subsections:
   - **Core Realizations & Insights:** Breakthrough ideas or underlying truths uncovered during this session (with inline `[[WikiLinks]]`).
   - **Past Solutions & Recognized Patterns:** How today's thoughts connect to past habits, previous problems, or historical solutions.
   - **Future Goals & Next Actions:** Concrete, actionable next steps formatted as Obsidian tasks (`- [ ] Task description`).
   - **Suggested Graph Connections:** Explicitly list 3 to 6 `[[Node Name]]` hubs (existing or new) that this thought clusters around, and briefly state why they connect.

3. **PART 3: Inline Metadata Tags**
   - Provide a single line of relevant Obsidian tags at the top of the entry (e.g., `#dmn #journal #insight #architecture`).

### OUTPUT FORMAT (Return BOTH XML-style blocks cleanly without outer code fences and with zero emojis):
<RAW_TRANSCRIPT>
[Clean, Rambler-style first-person transcript without filler words, repetitions, dictation commands, WikiLinks, or emojis...]
</RAW_TRANSCRIPT>

<DMN_MARKDOWN>
## DMN Session — $timestampStr
**Tags:** #dmn [add 2-4 contextual #tags]
**Linked Hubs:** [comma-separated list of top [[WikiLinks]] used]

### DMN Synthesis & Graph Connections
**Core Realizations & Insights**
- ...

**Past Solutions & Recognized Patterns**
- ...

**Future Goals & Next Actions**
- [ ] ...

**Suggested Graph Connections**
- [[Node A]] <-> [[Node B]]: Explanation of connection...

### Stream of Consciousness (Cleaned)
[Full Rambler-cleaned first-person prose with natural inline [[WikiLinks]]...]
</DMN_MARKDOWN>
        """.trimIndent()
    }

    fun buildTextDmnPrompt(rawText: String, existingNodes: List<String>, timestampStr: String): String {
        val nodesContext = if (existingNodes.isNotEmpty()) {
            existingNodes.take(500).joinToString(", ") { "[[$it]]" }
        } else {
            "None yet (propose new atomic [[Concept]] links freely)"
        }

        return """
You are an executive Default Mode Network (DMN) cognitive synthesizer and personal knowledge graph architect for an Obsidian vault.

### EXISTING KNOWLEDGE GRAPH NODES IN VAULT:
$nodesContext

### INPUT NOTE / EXTERNAL TRANSCRIPT:
\"\"\"
$rawText
\"\"\"

### STRICT FORMATTING RULE:
- Do NOT use any emojis anywhere in your response (zero icons in headings, bullets, tags, or prose). Maintain a crisp, analytical, professional tone.

### YOUR TASK:
Analyze the user's pasted transcript or written note above and output a structured Obsidian Markdown entry. Follow these exact rules:

1. **PART 1: Structured Thought Prose (`### Captured Thought (Graph-Linked)`)**
   - Preserve the user's full meaning, voice, nuance, and detail without over-summarizing. Clean up rough filler/repetitions gently if it is a rough transcript while retaining all ideas.
   - **Bi-Directional Graph Linking:** Whenever the text mentions a concept, person, project, recurring problem, or mental model that matches an item in `EXISTING KNOWLEDGE GRAPH NODES` (or warrants a new evergreen atomic topic node), naturally wrap it in Obsidian WikiLinks like `[[Concept Name]]` directly inline.

2. **PART 2: DMN Cognitive Synthesis (`### DMN Synthesis & Graph Connections`)**
   Structure the deeper synthesis into four subsections:
   - **Core Realizations & Insights:** Breakthrough ideas or underlying truths uncovered (with inline `[[WikiLinks]]`).
   - **Past Solutions & Recognized Patterns:** Connections to past habits, previous problems, or historical solutions.
   - **Future Goals & Next Actions:** Concrete actionable next steps formatted as Obsidian tasks (`- [ ] Task description`).
   - **Suggested Graph Connections:** Explicitly list 3 to 6 `[[Node Name]]` hubs (existing or new) that this thought clusters around, and briefly state why they connect.

3. **PART 3: Inline Metadata Tags**
   - Provide a single line of relevant Obsidian tags at the top of the entry (e.g., `#dmn #text-input #insight`).

### OUTPUT FORMAT (Valid Markdown Only, No Code Fences, Strictly Zero Emojis):
## DMN Note Session — $timestampStr
**Tags:** #dmn #text-input [add 2-4 contextual #tags]
**Linked Hubs:** [comma-separated list of top [[WikiLinks]] used]

### DMN Synthesis & Graph Connections
**Core Realizations & Insights**
- ...

**Past Solutions & Recognized Patterns**
- ...

**Future Goals & Next Actions**
- [ ] ...

**Suggested Graph Connections**
- [[Node A]] <-> [[Node B]]: Explanation of connection...

### Captured Thought (Graph-Linked)
[Graph-linked prose preserving the full depth of the user's input with natural inline [[WikiLinks]]...]
        """.trimIndent()
    }

    fun buildVoiceTranscriptToDmnPrompt(
        rawTranscript: String,
        existingNodes: List<String>,
        timestampStr: String
    ): String {
        val nodesContext = if (existingNodes.isNotEmpty()) {
            existingNodes.take(500).joinToString(", ") { "[[$it]]" }
        } else {
            "None yet (propose new atomic [[Concept]] links freely)"
        }

        return """
You are an executive Default Mode Network (DMN) cognitive synthesizer and personal knowledge graph architect for an Obsidian vault.

### EXISTING KNOWLEDGE GRAPH NODES IN VAULT:
$nodesContext

### RAW VOICE TRANSCRIPT (Produced by Speech-to-Text):
\"\"\"
$rawTranscript
\"\"\"

### STRICT FORMATTING RULE:
- Do NOT use any emojis anywhere in your response (zero icons in headings, bullets, tags, or prose). Maintain a crisp, analytical, professional tone.

### YOUR TASK:
Transform the raw spoken transcript above into a clean, graph-linked Obsidian Markdown DMN session entry:

1. **PART 1: Rambler-Grade Clean Prose (`### Stream of Consciousness (Cleaned)`)**
   - Strip filler vocalizations ("um", "uh", "er", "like", "you know"), stutters, false starts, accidental repetitions, and spoken self-corrections (apply corrections seamlessly).
   - Preserve the speaker's exact first-person ("I") voice, ideas, reasoning, and full substantive detail in logical paragraphs without summarizing away substance.
   - **Bi-Directional Graph Linking:** Whenever a concept, person, project, recurring problem, or mental model matches an item in `EXISTING KNOWLEDGE GRAPH NODES` (or warrants a new evergreen atomic topic node), naturally wrap it in Obsidian WikiLinks like `[[Concept Name]]` directly inline.

2. **PART 2: DMN Cognitive Synthesis (`### DMN Synthesis & Graph Connections`)**
   Structure the deeper synthesis into four subsections:
   - **Core Realizations & Insights:** Breakthrough ideas or underlying truths uncovered during this session (with inline `[[WikiLinks]]`).
   - **Past Solutions & Recognized Patterns:** How today's thoughts connect to past habits, previous problems, or historical solutions.
   - **Future Goals & Next Actions:** Concrete, actionable next steps formatted as Obsidian tasks (`- [ ] Task description`).
   - **Suggested Graph Connections:** Explicitly list 3 to 6 `[[Node Name]]` hubs (existing or new) that this thought clusters around, and briefly state why they connect.

3. **PART 3: Inline Metadata Tags**
   - Provide a single line of relevant Obsidian tags at the top of the entry (e.g., `#dmn #voice #insight #architecture`).

### OUTPUT FORMAT (Valid Markdown Only, No Code Fences, Strictly Zero Emojis):
## DMN Session — $timestampStr
**Tags:** #dmn [add 2-4 contextual #tags]
**Linked Hubs:** [comma-separated list of top [[WikiLinks]] used]

### DMN Synthesis & Graph Connections
**Core Realizations & Insights**
- ...

**Past Solutions & Recognized Patterns**
- ...

**Future Goals & Next Actions**
- [ ] ...

**Suggested Graph Connections**
- [[Node A]] <-> [[Node B]]: Explanation of connection...

### Stream of Consciousness (Cleaned)
[Full Rambler-cleaned first-person prose with natural inline [[WikiLinks]]...]
        """.trimIndent()
    }
}

package com.example.data.gemini

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit

data class GeminiGenerationResult(
    val markdown: String,
    val rawTranscript: String,
    val modelUsed: String,
    val extractedWikiLinks: List<String>
)

class GeminiDmnService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com"
        private val WIKILINK_EXTRACT_REGEX = Regex("""(?<!!)\[\[([^\[\]|#]+)(?:#[^\[\]|]+)?(?:\|[^\[\]]+)?]]""")
    }

    suspend fun testApiKey(apiKey: String, modelId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotBlank()) { "Please enter your Google AI Studio API key." }
            val cleanModel = modelId.trim().ifBlank { "gemini-flash-latest" }
            val request = Request.Builder()
                .url("$BASE_URL/v1beta/models/$cleanModel")
                .header("x-goog-api-key", cleanKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errMsg = parseGeminiError(bodyStr) ?: "HTTP ${response.code}"
                    throw IOException("API check failed: $errMsg")
                }
                val json = JSONObject(bodyStr)
                val displayName = json.optString("displayName", cleanModel)
                "Connected ($displayName)"
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
        onStageChanged: suspend (stage: String) -> Unit
    ): GeminiGenerationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "Google AI Studio API key is missing." }
        val cleanModel = modelId.trim().ifBlank { "gemini-flash-latest" }

        onStageChanged("UPLOADING")
        val uploaded = uploadAudioResumable(
            apiKey = cleanKey,
            audioUri = audioUri,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes
        )

        try {
            onStageChanged("PROCESSING_REMOTE")
            val activeFile = waitForFileActive(cleanKey, uploaded.name)

            onStageChanged("SYNTHESIZING")
            val prompt = buildDmnPrompt(existingNodes, timestampStr)
            val rawResponse = callGenerateContent(
                apiKey = cleanKey,
                modelId = cleanModel,
                fileUri = activeFile.uri,
                mimeType = activeFile.mimeType.ifBlank { mimeType },
                prompt = prompt
            )

            val (verbatimTranscript, markdown) = parseAudioDualOutput(rawResponse)
            val links = WIKILINK_EXTRACT_REGEX.findAll(markdown)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()

            GeminiGenerationResult(
                markdown = markdown,
                rawTranscript = verbatimTranscript,
                modelUsed = cleanModel,
                extractedWikiLinks = links
            )
        } finally {
            // Privacy rule from blueprint: delete temporary audio from Google servers immediately
            deleteRemoteFileQuietly(cleanKey, uploaded.name)
        }
    }

    suspend fun processTextToDmnMarkdown(
        apiKey: String,
        modelId: String,
        rawText: String,
        existingNodes: List<String>,
        timestampStr: String,
        onStageChanged: suspend (stage: String) -> Unit
    ): GeminiGenerationResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "Google AI Studio API key is missing." }
        require(rawText.isNotBlank()) { "Input text is empty." }
        val cleanModel = modelId.trim().ifBlank { "gemini-flash-latest" }

        onStageChanged("SYNTHESIZING")
        val prompt = buildTextDmnPrompt(rawText, existingNodes, timestampStr)
        val rawResponse = callGenerateContent(
            apiKey = cleanKey,
            modelId = cleanModel,
            fileUri = null,
            mimeType = null,
            prompt = prompt
        )

        val cleanedMarkdown = cleanFences(rawResponse)
        val links = WIKILINK_EXTRACT_REGEX.findAll(cleanedMarkdown)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        GeminiGenerationResult(
            markdown = cleanedMarkdown,
            rawTranscript = rawText,
            modelUsed = cleanModel,
            extractedWikiLinks = links
        )
    }

    private data class UploadedGeminiFile(
        val name: String,
        val uri: String,
        val mimeType: String,
        val state: String
    )

    private fun uploadAudioResumable(
        apiKey: String,
        audioUri: Uri,
        displayName: String,
        mimeType: String,
        sizeBytes: Long
    ): UploadedGeminiFile {
        val actualSize = if (sizeBytes > 0) sizeBytes else {
            context.contentResolver.openAssetFileDescriptor(audioUri, "r")?.use { it.length } ?: -1L
        }
        require(actualSize > 0L) { "Audio file is empty or unreadable." }

        val startPayload = JSONObject().put(
            "file",
            JSONObject().put("display_name", displayName)
        ).toString()

        val startRequest = Request.Builder()
            .url("$BASE_URL/upload/v1beta/files")
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", actualSize.toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(startPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val uploadUrl = client.newCall(startRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val err = parseGeminiError(response.body?.string()) ?: "HTTP ${response.code}"
                throw IOException("Failed to initiate Gemini audio upload: $err")
            }
            response.header("X-Goog-Upload-URL")
                ?: throw IOException("Gemini upload endpoint did not return X-Goog-Upload-URL.")
        }

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
            if (!response.isSuccessful) {
                val err = parseGeminiError(bodyStr) ?: "HTTP ${response.code}"
                throw IOException("Audio upload failed: $err")
            }
            parseFileResponse(JSONObject(bodyStr))
        }
    }

    private suspend fun waitForFileActive(apiKey: String, fileName: String): UploadedGeminiFile {
        repeat(60) {
            val request = Request.Builder()
                .url("$BASE_URL/v1beta/$fileName")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()

            val fileInfo = client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = parseGeminiError(bodyStr) ?: "HTTP ${response.code}"
                    throw IOException("Failed checking uploaded file status: $err")
                }
                val json = JSONObject(bodyStr)
                // GET /v1beta/files/{id} returns the file object directly (or wrapped in "file")
                if (json.has("file")) parseFileResponse(json) else parseFileObject(json)
            }

            when (fileInfo.state) {
                "ACTIVE" -> return fileInfo
                "FAILED" -> throw IOException("Gemini rejected audio processing (state=FAILED).")
                else -> delay(2000L)
            }
        }
        throw IOException("Timed out waiting for Gemini audio file indexing.")
    }

    private fun callGenerateContent(
        apiKey: String,
        modelId: String,
        fileUri: String?,
        mimeType: String?,
        prompt: String
    ): String {
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

        val request = Request.Builder()
            .url("$BASE_URL/v1beta/models/$modelId:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = parseGeminiError(bodyStr) ?: "HTTP ${response.code}"
                throw IOException("Gemini generation error: $err")
            }
            val json = JSONObject(bodyStr)
            val candidates = json.optJSONArray("candidates")
                ?: throw IOException("Gemini returned no candidates.")
            if (candidates.length() == 0) {
                throw IOException("Gemini returned an empty response.")
            }
            val parts = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            buildString {
                for (i in 0 until parts.length()) {
                    append(parts.getJSONObject(i).optString("text", ""))
                }
            }.trim()
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
                val cleanedSectionMarker = "### 🎙️ Stream of Consciousness (Cleaned)"
                val idx = dmnMarkdown.indexOf(cleanedSectionMarker)
                if (idx >= 0) {
                    dmnMarkdown.substring(idx + cleanedSectionMarker.length).trim()
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
You are an elite Default Mode Network (DMN) cognitive synthesizer and personal knowledge graph architect for an Obsidian vault.

### EXISTING KNOWLEDGE GRAPH NODES IN VAULT:
$nodesContext

### YOUR TASK:
Listen to the attached raw voice recording and produce TWO clearly delimited outputs:
1. `<RAW_TRANSCRIPT>`: An exact, word-for-word verbatim transcription of everything spoken in the recording—preserving exact wording, filler words, pauses/false starts, and punctuation as faithfully as possible without rewriting or summarizing. Do NOT add WikiLinks inside `<RAW_TRANSCRIPT>`.
2. `<DMN_MARKDOWN>`: A structured Obsidian Markdown entry following the rules below.

Rules for `<DMN_MARKDOWN>`:
1. **PART 1: Rambler-Grade Clean Prose (`### 🎙️ Stream of Consciousness (Cleaned)`)**
   - Act like Gboard Rambler at 99.9% accuracy: transform the natural, wandering spoken recording into clean, articulate, first-person ("I") written prose.
   - Strip all filler words ("um", "uh", "like", "you know"), false starts, stutters, and accidental repetitions.
   - Preserve my exact voice, emotional nuance, vocabulary, and depth of thought. Do NOT over-summarize or water down this section—keep the full richness of the monologue, broken into logical paragraphs.
   - **Bi-Directional Graph Linking:** Whenever I mention a concept, person, project, recurring problem, or mental model that matches an item in the `EXISTING KNOWLEDGE GRAPH NODES` list (or warrants a new evergreen atomic topic node), naturally wrap it in Obsidian WikiLinks like `[[Concept Name]]` directly inline!

2. **PART 2: DMN Cognitive Synthesis (`### 🧠 DMN Synthesis & Graph Connections`)**
   Extract and structure the deeper mental connections from the monologue into four bulleted subsections:
   - **💡 Core Realizations & Insights:** The breakthrough ideas or underlying truths uncovered during this session (with inline `[[WikiLinks]]`).
   - **🔄 Past Solutions & Recognized Patterns:** How today's thoughts connect to past habits, previous problems, or historical solutions.
   - **🎯 Future Goals & Next Actions:** Concrete, actionable next steps formatted as Obsidian tasks (`- [ ] Task description`).
   - **🕸️ Suggested Graph Connections:** Explicitly list 3 to 6 `[[Node Name]]` hubs (existing or new) that this thought clusters around, and briefly state *why* they connect.

3. **PART 3: Inline Metadata Tags**
   - Provide a single line of relevant Obsidian tags at the top of the entry (e.g., `#dmn #journal #insight #architecture`).

### OUTPUT FORMAT (Return BOTH XML-style blocks cleanly without outer code fences):
<RAW_TRANSCRIPT>
[Exact word-for-word verbatim transcription of the audio...]
</RAW_TRANSCRIPT>

<DMN_MARKDOWN>
## 🕒 DMN Session — $timestampStr
**Tags:** #dmn [add 2-4 contextual #tags]
**Linked Hubs:** [comma-separated list of top [[WikiLinks]] used]

### 🧠 DMN Synthesis & Graph Connections
**💡 Core Realizations & Insights**
- ...

**🔄 Past Solutions & Recognized Patterns**
- ...

**🎯 Future Goals & Next Actions**
- [ ] ...

**🕸️ Suggested Graph Connections**
- [[Node A]] ↔ [[Node B]]: Explanation of connection...

### 🎙️ Stream of Consciousness (Cleaned)
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
You are an elite Default Mode Network (DMN) cognitive synthesizer and personal knowledge graph architect for an Obsidian vault.

### EXISTING KNOWLEDGE GRAPH NODES IN VAULT:
$nodesContext

### INPUT NOTE / EXTERNAL TRANSCRIPT:
\"\"\"
$rawText
\"\"\"

### YOUR TASK:
Analyze the user's pasted transcript or written note above and output a structured Obsidian Markdown entry. Follow these exact rules:

1. **PART 1: Structured Thought Prose (`### 📝 Captured Thought (Graph-Linked)`)**
   - Preserve the user's full meaning, voice, nuance, and detail without over-summarizing. If the input is a rough transcript, polish clarity gently while retaining all ideas.
   - **Bi-Directional Graph Linking:** Whenever the text mentions a concept, person, project, recurring problem, or mental model that matches an item in the `EXISTING KNOWLEDGE GRAPH NODES` list (or warrants a new evergreen atomic topic node), naturally wrap it in Obsidian WikiLinks like `[[Concept Name]]` directly inline!

2. **PART 2: DMN Cognitive Synthesis (`### 🧠 DMN Synthesis & Graph Connections`)**
   Extract and structure the deeper mental connections into four bulleted subsections:
   - **💡 Core Realizations & Insights:** Breakthrough ideas or underlying truths uncovered (with inline `[[WikiLinks]]`).
   - **🔄 Past Solutions & Recognized Patterns:** Connections to past habits, previous problems, or historical solutions.
   - **🎯 Future Goals & Next Actions:** Concrete actionable next steps formatted as Obsidian tasks (`- [ ] Task description`).
   - **🕸️ Suggested Graph Connections:** Explicitly list 3 to 6 `[[Node Name]]` hubs (existing or new) that this thought clusters around, and briefly state *why* they connect.

3. **PART 3: Inline Metadata Tags**
   - Provide a single line of relevant Obsidian tags at the top of the entry (e.g., `#dmn #note #insight`).

### OUTPUT FORMAT (Valid Markdown Only, No Code Fences Wrapping the Response):
## 🕒 DMN Note Session — $timestampStr
**Tags:** #dmn #text-input [add 2-4 contextual #tags]
**Linked Hubs:** [comma-separated list of top [[WikiLinks]] used]

### 🧠 DMN Synthesis & Graph Connections
**💡 Core Realizations & Insights**
- ...

**🔄 Past Solutions & Recognized Patterns**
- ...

**🎯 Future Goals & Next Actions**
- [ ] ...

**🕸️ Suggested Graph Connections**
- [[Node A]] ↔ [[Node B]]: Explanation of connection...

### 📝 Captured Thought (Graph-Linked)
[Graph-linked prose preserving the full depth of the user's input with natural inline [[WikiLinks]]...]
        """.trimIndent()
    }
}

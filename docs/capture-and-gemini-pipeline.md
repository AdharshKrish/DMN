# Voice/Text Capture & Gemini DMN Pipeline

This document explains how Synapse DMN captures raw thoughts (both spoken audio and pasted/typed text), sends them through Gemini with long-timeout resiliency and live model switching, and produces clean, structured Markdown output.

---

## 1. Segmented Voice Recording (`DmnAudioRecorder`)

Voice journaling is engineered for long, interrupted sessions while producing a single unified `.m4a` file.

### Microphone Release During Pause
On Android 12+, simply calling `MediaRecorder.pause()` leaves the OS microphone indicator active in the status bar and keeps hardware mic access locked for the entire pause duration. To solve this:

1. **Recording (`RECORDING`):** `DmnAudioRecorder` writes an AAC-LC MPEG-4 stream (`44,100 Hz`, mono, `128,000 bps`) to a segment file (`dmn_rec_seg_0_<ts>.m4a`) in `cacheDir` and polls `recorder.maxAmplitude` every `80ms` for the live waveform visualizer.
2. **Pausing (`PAUSED` — `"Paused • Mic Off"`):** Calling `pauseRecording()` stops and **completely releases** `MediaRecorder` (`recorder = null`), finalizing the current segment file and immediately extinguishing Android's microphone-in-use indicator. Accumulated elapsed time is preserved.
3. **Resuming (`RECORDING`):** Calling `resumeRecording()` opens a new `MediaRecorder` segment (`dmn_rec_seg_1_<ts>.m4a`) with identical audio encoding parameters. If another app or phone call temporarily holds the mic, the failure is surfaced via a snackbar while keeping `PAUSED` state and all earlier segments intact.
4. **Saving (`stopRecording()`):** Runs on `Dispatchers.IO`:
   - If only 1 segment exists, it is renamed/used directly.
   - If multiple segments exist across pauses, `mergeM4aSegments()` losslessly remuxes all segments into a single `.m4a` container (`active_dmn_recording.m4a`) using `MediaExtractor` + `MediaMuxer` with `computeNextPtsOffsetUs()` and `ensureMonotonicPtsUs()` so presentation timestamps (`PTS`) remain strictly monotonic across pause boundaries without audio re-encoding.
   - The unified `.m4a` is written into `00_Audio_Inbox/` (`<Label>_yyyyMMdd_HHmmss.m4a`), and temporary segment cache files are cleaned up.

---

## 2. Direct Text Note Input (`StudioScreen` + `04_Text_Inbox`)

Users who transcribe elsewhere or type quick brain-dumps can switch the Studio hero card to **Text Note** mode:
- Enter or paste text via the clipboard paste action, optional title stem, and target format (`.md` or `.txt`).
- **Save to Queue:** Writes the file into `04_Text_Inbox/` for later batch synthesis.
- **Synthesize Now:** Saves into `04_Text_Inbox/` and immediately triggers `DmnProcessWorker` if online (or queues it until connectivity returns).

---

## 3. Gemini Processing Pipeline (`GeminiDmnService` & `DmnPipelineOrchestrator`)

All inbox processing runs sequentially inside `DmnPipelineOrchestrator.processMutex`.

### Step-by-Step Execution
- **For Audio Files (`00_Audio_Inbox/`):**
  1. **`upload_audio` (Gemini Files Resumable Upload):** Starts a resumable upload session (`POST https://generativelanguage.googleapis.com/upload/v1beta/files`) and streams bytes from the SAF `Uri` via `PUT uploadUrl` (`UPLOAD, FINALIZE`).
  2. **`wait_active` (Remote Indexing):** Polls `GET https://generativelanguage.googleapis.com/v1beta/files/{name}` every `2s` until file `state == "ACTIVE"` (fails immediately on `"FAILED"`).
  3. **`generate_dmn` (Audio Transcription + DMN Synthesis):** Calls `POST https://generativelanguage.googleapis.com/v1beta/models/{modelId}:generateContent` with the active `file_data` URI and the DMN prompt injected with existing vault node names (`[[Node]]`) and current session timestamp.
  4. **`cleanup_remote`:** Best-effort `DELETE` of the temporary Gemini Files API upload once synthesis succeeds.
  5. **`save_vault`:** Archives audio (`99_Audio_Archive/`), saves the raw transcript (`98_Transcript_Archive/<stem>.md`), appends the formatted DMN session to `01_Daily_Notes/YYYY-MM-DD.md`, records a `ProcessingJobEntity` (`COMPLETED`), and inserts a detailed `GEMINI` log entry.

- **For Text Files (`04_Text_Inbox/`):**
  - Skips Steps 1, 2, and 4 and proceeds straight from reading the raw text through `generate_dmn` → `save_vault`, archiving the original note into `98_Transcript_Archive/<stem>.md` and deleting the inbox copy.

---

## 4. Prompt Design & Rambler-Clean Output

- **Vault Context Injection:** Up to `500` existing Daily Note and Atomic Note titles are passed to Gemini as `[[WikiLinks]]` (`$nodesContext`), allowing new sessions to automatically connect to existing concepts in your Obsidian graph.
- **Audio Dual-Output Format:** Audio requests instruct Gemini to return two delimited blocks (`parseAudioDualOutput`):
  - `<RAW_TRANSCRIPT>...</RAW_TRANSCRIPT>` — Clean Rambler-grade verbatim transcript archived as a `.md` file in `98_Transcript_Archive/`.
  - `<DMN_MARKDOWN>...</DMN_MARKDOWN>` — Structured DMN session note appended to `01_Daily_Notes/YYYY-MM-DD.md`.
- **Rambler-Style Cleanup:** Both `<RAW_TRANSCRIPT>` and `### Stream of Consciousness (Cleaned)` explicitly instruct Gemini to strip filler sounds (`"um"`, `"uh"`, `"er"`, `"like"`, `"you know"`), stutters, false starts, accidental repetitions, and spoken meta-instructions while preserving full reasoning and nuance.
- **Professional Formatting (Zero Emojis):** Prompts explicitly forbid decorative emojis anywhere in the output.
- **Request Payload Shape:** Requests hit `POST https://generativelanguage.googleapis.com/v1beta/models/{modelId}:generateContent` with `generationConfig.temperature = 0.2`. Audio requests attach `contents[0].parts[0].file_data` (`mime_type` + `file_uri`) followed by `parts[1].text`; text requests send `contents[0].parts[0].text`.

### Exact Voice Recording Prompt (`GeminiDmnService.buildDmnPrompt`)

Here `$nodesContext` is either `existingNodes.take(500).joinToString(", ") { "[[$it]]" }` or `"None yet (propose new atomic [[Concept]] links freely)"`, and `$timestampStr` is formatted as `hh:mm a`:

~~~~markdown
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
~~~~

### Exact Text Input Prompt (`GeminiDmnService.buildTextDmnPrompt`)

Used when synthesizing pasted or typed notes from `04_Text_Inbox/`:

~~~~markdown
You are an executive Default Mode Network (DMN) cognitive synthesizer and personal knowledge graph architect for an Obsidian vault.

### EXISTING KNOWLEDGE GRAPH NODES IN VAULT:
$nodesContext

### INPUT NOTE / EXTERNAL TRANSCRIPT:
"""
$rawText
"""

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
~~~~

---

## 5. Long-Timeout Resiliency & Live Retry Model Switching

### Timeouts & 5-Minute Retry Window
- OkHttp client configuration in `GeminiDmnService`:
  - `connectTimeout`: **60 seconds**
  - `readTimeout` / `writeTimeout`: **360 seconds (6 minutes)** per request — accommodating slow/large multi-minute audio responses without premature client timeouts.
- Transient HTTP status codes (`429`, `500`, `502`, `503`, `504`) and socket/IO exceptions enter an exponential backoff retry loop up to `MAX_RETRY_WINDOW_MS = 300_000L` (5 minutes), capped at `30s` per sleep.
- Structured error parser (`parseGeminiErrorDetails`) extracts HTTP code, gRPC `status`, `error.message`, `details[].reason`, and model ID for display in both the active progress dialog and Sync logs.

### Live Inspection & Model Switching (`ActiveProcessingDialog` + `RetryControlChannel`)
Tapping the live synthesis banner or any active job opens `ActiveProcessingDialog`, which displays real-time `ActiveProcessingProgress`:
- Current file name, input type badge (`Audio` / `Text`), active model badge, pending override model pill, and live elapsed timer.
- **Active API Call:** Exact HTTP method + endpoint, status text (`IN PROGRESS` vs. `RETRYING (#N)`), request payload summary, and HTTP error summary.
- **Last Response Payload:** Monospace selectable card with one-tap Copy-to-Clipboard button showing the raw API response payload received so far.
- **What Happened So Far:** Timestamped chronological list of every attempt, HTTP response, backoff wait, and manual override.
- **Model & Retry Controls:** Select from quick chips (`gemini-3.5-flash`, `gemini-3.5-flash-lite`, `gemini-3.8-flash`, `gemini-flash-lite-latest`, `gemini-flash-latest`, plus models fetched dynamically from `/v1beta/models`) or enter any custom model ID:
  - **Queue Next Retry:** Stores the target model on `RetryControlChannel` so the next retry automatically uses the new model while letting the current call finish.
  - **Switch & Retry Now:** Cancels the active OkHttp `Call` or interrupts the backoff `withTimeoutOrNull` wait and immediately retries with the newly selected model.
  - **Retry Current Call Now:** Skips the remaining backoff countdown immediately without changing models.

---

Back to [README](../README.md)

# Voice/Text Capture & Gemini DMN Pipeline

This document explains how Synapse DMN captures raw thoughts (both spoken audio and pasted/typed text), sends them through Gemini with long-timeout resiliency and live model switching, and produces clean, structured Markdown output.

---

## 1. Switchable Live vs. Batch+Rambler Speech-to-Transcription Studio (`LiveTranscriptionSessionManager` & `PcmAudioEncoder`)

Voice journaling uses durable `AudioRecord` 16kHz mono 16-bit PCM recording (`filesDir/voice_drafts/<draftId>/session.pcm`) with a **switchable mode toggle** right on the Studio recording card (persisted in DataStore):

1. **Live Transcribe Mode (`VoiceTranscriptionMode.LIVE`):**
   - Opens a bidirectional WebSocket (`BidiGenerateContent`) to `gemini-3.5-transcribe-live` via `GeminiLiveTranscribeClient` and displays direct real-time streaming transcription output while buffering PCM safely to disk.
   - **Automatic Batched Fallback:** If the Live model returns high-demand (`429`/`503`), rate-limit, quota, `goAway`, or connection errors, recording continues uninterrupted and falls back to progressive batched `gemini-3.5-transcribe` chunks so zero speech is lost.
2. **Batch • Rambler Mode (`VoiceTranscriptionMode.BATCH_RAMBLER` — Two-Step Pipeline):**
   - Bypasses the Live WebSocket and processes audio in progressive batches (~12s chunks or >=6s + natural pause splits, plus tail flush on Pause/Stop or offline catch-up) through two sequential operations:
     - **Step 1 (Transcription):** Calls `gemini-3.5-transcribe` (`transcribeInlineWavChunk`) to extract the raw, literal speech text from each WAV slice.
     - **Step 2 (Rambler LLM Post-Processing):** Passes that raw text (plus the previous 240-char cleaned tail for sentence continuity) into the configured Flash model (default `gemini-3.5-flash`) via `cleanTranscriptRamblerStyle(...)` with a dedicated `system_instruction` prompt, then displays the Rambler-cleaned prose in the transcript box.
   - If Step 2 fails transiently even after retry, the raw Step 1 transcript is shown instead so zero speech is ever lost.
3. **Microphone Release During Pause (`Paused • Mic Off`):** Pausing stops and completely releases `AudioRecord`, extinguishing Android's active microphone indicator, closes any Live WebSocket cleanly, and flushes trailing audio (running the 2-step Rambler flow when in Batch mode). Resuming or tapping **Continue** later appends smoothly to the same draft.
4. **Zero-Loss Voice Audio Backup & Automatic Cleanup:**
   - While recording OR whenever any speech remains untranscribed (e.g., offline mode, network drop, or temporary API outage), `session.pcm` is safely persisted on device alongside exact `transcribedByteOffset` tracking in Room (`voice_drafts`). Reopening the app restores the draft with a **Voice Backup Safe** banner and **Transcribe** catch-up button.
   - Once 100% of recorded audio is transcribed and the transcript is durably saved in Room, the temporary `session.pcm` audio file is automatically deleted — voice recordings are not unnecessarily duplicated into `99_Audio_Archive/`.
5. **Durable Editable Drafts + Explicit Confirmation Before Flash DMN Graph Synthesis:**
   - Stopping a recording saves the draft for review and **never** auto-triggers Flash DMN Graph processing.
   - Users can close and reopen the app, edit the transcript, switch between multiple saved drafts, or continue recording more speech into a draft.
   - Only after explicit confirmation (**Confirm & Send to Flash**) is the transcript saved to `04_Text_Inbox/` (`Voice_*.md`) and sent to the Flash DMN Graph model.

---

## 2. Direct Text Note Input (`StudioScreen` + `04_Text_Inbox`)

Users who transcribe elsewhere or type quick brain-dumps can switch the Studio hero card to **Text / Transcript** mode:
- Enter or paste text via the clipboard paste action, optional title stem, and target format (`.md` or `.txt`).
- **Save to Queue:** Writes the file into `04_Text_Inbox/` for later batch synthesis.
- **Synthesize Now:** Saves into `04_Text_Inbox/` and immediately triggers `DmnProcessWorker` if online (or queues it until connectivity returns).

---

## 3. Two-Stage Gemini Processing Pipeline (`GeminiDmnService` & `DmnPipelineOrchestrator`)

All inbox processing runs sequentially inside `DmnPipelineOrchestrator.processMutex`, splitting speech-to-text from graph synthesis:

### Step-by-Step Execution
- **For Confirmed Voice Drafts (`StudioScreen` → `04_Text_Inbox/Voice_*.md`):**
  1. Already transcribed via `gemini-3.5-transcribe-live` and/or batched `gemini-3.5-transcribe` and reviewed by the user.
  2. **`generate` (Flash DMN Graph Synthesis):** Calls `POST https://generativelanguage.googleapis.com/v1beta/models/{flashModelId}:generateContent` with `buildVoiceTranscriptToDmnPrompt(...)` (default `gemini-3.5-flash`).
  3. **`save_vault`:** Archives the exact transcript into `98_Transcript_Archive/<stem>.md`, appends the structured DMN session to `01_Daily_Notes/YYYY-MM-DD.md`, and deletes the inbox copy (no voice audio archived).

- **For External Audio Files Dropped into `00_Audio_Inbox/`:**
  1. **`upload_start` / `upload_bytes` (Gemini Files Resumable Upload):** Streams bytes to `POST /upload/v1beta/files`.
  2. **`file_active` (Remote Indexing):** Polls `GET /v1beta/files/{name}` every `2s` until `state == "ACTIVE"`.
  3. **`transcribe` (Stage 1 — Dedicated Speech-to-Text):** Calls `POST /v1beta/models/{transcriptionModel}:generateContent` (default `gemini-3.5-transcribe`) with `file_data` only.
  4. **`cleanup_remote`:** Immediately `DELETE`s the temporary Gemini Files API upload right after transcription.
  5. **`generate` (Stage 2 — Flash DMN Graph Synthesis):** Calls `POST /v1beta/models/{flashModelId}:generateContent` (default `gemini-3.5-flash`) with `buildVoiceTranscriptToDmnPrompt(transcript, existingNodes, timestampStr)`.
  6. **`save_vault`:** Writes `98_Transcript_Archive/<stem>.md` and `01_Daily_Notes/YYYY-MM-DD.md`, and only after both writes succeed, deletes the temporary source audio from `00_Audio_Inbox/`.

- **For Pasted/Typed Text Files (`04_Text_Inbox/`):**
  - Runs `generate` using `buildTextDmnPrompt(...)` → `save_vault`, archiving into `98_Transcript_Archive/<stem>.md`.

---

## 4. Prompt Design & Rambler-Clean Output

- **Vault Context Injection:** Up to `500` existing Daily Note and Atomic Note titles are passed to Gemini as `[[WikiLinks]]` (`$nodesContext`), allowing new sessions to automatically connect to existing concepts in your Obsidian graph.
- **Dedicated Transcription + Flash Graph Prompt (`buildVoiceTranscriptToDmnPrompt`):** Takes the raw speech transcript produced by `gemini-3.5-transcribe` / `gemini-3.5-transcribe-live` and produces a Rambler-cleaned first-person `### Stream of Consciousness (Cleaned)` section with inline `[[WikiLinks]]` plus the 4-part `### DMN Synthesis & Graph Connections` section (`## DMN Session — $timestampStr`) with strictly zero emojis.
- **Request Payload Shape:** Requests hit `POST https://generativelanguage.googleapis.com/v1beta/models/{modelId}:generateContent` with `generationConfig.temperature = 0.2`. Transcription calls send pure audio (`inlineData` WAV for batches or `file_data` for inbox audio); Flash graph calls send `contents[0].parts[0].text`.

### Exact Batch Rambler Post-Processing System Prompt (`GeminiDmnService.buildRamblerSystemPrompt`)

Used in **Batch • Rambler** mode as `system_instruction` on `POST /v1beta/models/{flashModelId}:generateContent` (`temperature = 0.15`) immediately after `gemini-3.5-transcribe` produces raw text:

~~~~markdown
You are a Gboard Rambler-style speech-to-text post-processor.
Transform raw, literal spoken transcripts into clean, natural, fluent written prose while preserving 100% of the speaker's ideas, vocabulary, reasoning, and first-person ("I") voice.

STRICT RULES:
1. Remove filler vocalizations ("um", "uh", "er", "ah", "hmm", verbal ticks, and hesitation uses of "like", "you know", "sort of", "I mean").
2. Fix stutters, false starts, and accidental word or phrase repetitions.
3. Apply spoken self-corrections seamlessly (e.g., if the speaker says "let's ship Tuesday, wait no, actually Thursday" or "scratch that" / "ignore that", output only the corrected intent rather than transcribing the correction command).
4. Add clean punctuation, capitalization, and natural sentence/paragraph breaks so the text reads effortlessly.
5. Do NOT summarize away details, do NOT invent facts, do NOT add headings, bullet lists, [[WikiLinks]], emojis, quotes, or conversational preambles (such as "Here is the cleaned transcript:").
6. Return ONLY the cleaned prose text itself. If the input contains no meaningful speech, return an empty string.
~~~~

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

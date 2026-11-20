# Synapse DMN

**Synapse DMN** (`com.aistudio.synapsedmn.kxmqrp`) is an Android companion app for **Obsidian** built with Kotlin and Jetpack Compose (Material Design 3). It turns spoken stream-of-consciousness voice memos and pasted text notes into structured Default Mode Network (DMN) Markdown entries with automatic `[[WikiLink]]` knowledge-graph synthesis, verbatim Markdown transcript archives, interactive force-directed graph visualization, and efficient Google Drive sync.

---

## Core Features

- **Live-First Speech-to-Transcription Studio (`gemini-3.5-transcribe-live` → Batched `gemini-3.5-transcribe`):** Streams real-time speech transcription via Gemini Live WebSocket first, and automatically falls back to progressive batched transcription (`gemini-3.5-transcribe`) on high-demand (`429`/`503`) or disconnect errors without stopping the mic. Pausing releases hardware microphone access (`Paused • Mic Off`) so Android clears the mic indicator during multi-minute breaks.
- **Durable Editable Drafts, Safe Audio Backup & Explicit Confirmation:** All speech is continuously backed up to internal durable storage (`voice_drafts`) with exact byte-offset tracking so zero speech is lost across network outages, API errors, or app restarts. Once 100% of audio is transcribed and safely stored, temporary voice audio is automatically cleaned up (instead of archiving every recording). Drafts stay editable and continuable across app closures, and only proceed to Flash DMN Graph synthesis after explicit user confirmation.
- **Direct Text Note Input:** Capture or paste pre-transcribed text notes directly inside Studio as `.md` or `.txt` files (`04_Text_Inbox/`) and process them through the same Gemini DMN graph pipeline.
- **Two-Stage Gemini Pipeline (Dedicated Transcribe → Flash DMN Graph):** Uses `gemini-3.5-transcribe` strictly for speech-to-text and the configurable Flash model (default `gemini-3.5-flash`) strictly for transcription-to-graph conversion — injecting existing vault `[[WikiLinks]]`, generating emoji-free structured DMN Markdown sessions in `01_Daily_Notes/YYYY-MM-DD.md`, producing cleaned Rambler-style prose, and archiving exact raw transcripts in `.md` format (`98_Transcript_Archive/`).
- **Live Pipeline Inspector & Retry Model Switching:** Tapping the active synthesis banner opens a live inspector displaying the active HTTP API call, request/response payloads, chronological attempt history, a 5-minute transient retry window (`429`/`5xx`) with 6-minute socket timeouts, and instant controls to switch Gemini models (`gemini-3.5-flash`, `gemini-3.5-flash-lite`, `gemini-3.8-flash`, or custom IDs) on the next retry or immediately.
- **Interactive Force-Directed Knowledge Graph:** Visualizes Daily Notes, `02_Atomic_Nodes/`, and uncreated Ghost `[[WikiLinks]]` via a physics simulation with pan/zoom, search/group filters, backlinks inspector, and one-tap **Atomic Hub Note** promotion.
- **Obsidian Vault Explorer & Clean Session Deletion:** Read Daily Notes and Atomic Notes, toggle `- [ ]` Markdown checklists directly, play back archived `.m4a` audio, configure automatic expired-audio retention (`Keep Forever` or N days), and delete any DMN session completely (Daily Note block + archived audio + archived transcript + database job) in one confirmation.
- **Manifest-Powered Google Drive Sync:** Supports fast **Phone → Drive** mirror sync (including propagating local phone deletions to Drive) and **Two-Way Sync** (using `.synapse_sync_manifest.json` + Google Drive Changes delta API to avoid re-scanning unchanged vault files), plus background execution via `WorkManager` foreground workers.

---

## Documentation Index

Detailed technical documentation is organized by subsystem under [`docs/`](docs):

1. **[Architecture, Storage & Background Workers](docs/architecture-and-storage.md)**
   MVVM + `DmnPipelineOrchestrator` structure, Preferences DataStore keys, Room database schema (`processing_jobs`, `sync_records`, `sync_logs`), SAF vault persistence, and `WorkManager` foreground service + notification lifecycle.
2. **[Voice/Text Capture & Gemini Pipeline](docs/capture-and-gemini-pipeline.md)**
   Segmented `MediaRecorder` + `MediaMuxer` pause/resume stitching, Text Inbox flow, exact Gemini audio/text API prompts (`buildDmnPrompt` & `buildTextDmnPrompt`), dual-output parsing, 5-minute backoff retry loop, and live `RetryControlChannel` model switching.
3. **[Knowledge Graph, Atomic Notes & Vault Management](docs/knowledge-graph-and-vault.md)**
   Reflect-style Daily Notes vs. Atomic Nodes vs. Ghost Nodes, `ForceGraphEngine` physics layout, inline checklist toggling, raw `.md` transcript archives, audio retention pruning, and complete DMN session deletion.
4. **[Google Drive Sync, Diagnostics & CI/CD](docs/google-drive-sync-and-ci.md)**
   Google Identity Services OAuth + SHA-1 diagnostics, `.synapse_sync_manifest.json` diffing, `Phone → Drive` deletion mirroring vs. `Two-Way Sync` delta & conflict strategies, clickable debug logs, and GitHub Actions CI/Release APK packaging.

---

## Vault Folder Structure

When you pick an `ObsidianVault` folder via Android's Storage Access Framework, Synapse DMN provisions and maintains:

| Folder | Purpose |
| :--- | :--- |
| `00_Audio_Inbox/` | Queued `.m4a` voice recordings awaiting Gemini synthesis |
| `01_Daily_Notes/` | Daily Markdown notes (`YYYY-MM-DD.md`) containing structured DMN sessions |
| `02_Atomic_Nodes/` | Evergreen concept / principle notes (e.g., promoted from Ghost Links) |
| `03_Templates/` | Reserved vault templates directory |
| `04_Text_Inbox/` | Queued `.md` / `.txt` notes awaiting Gemini synthesis |
| `98_Transcript_Archive/` | Exact verbatim audio transcripts & original pasted/typed notes saved as `.md` |
| `99_Audio_Archive/` | Archived `.m4a` recordings retained per configured retention days |

---

## Quick Start & Setup

1. **Select Obsidian Vault:** Open the **Sync** tab (or Studio setup checklist) and grant folder access to your Obsidian Vault directory.
2. **Configure Gemini API:** Enter your Google AI Studio API key in **Sync -> Google AI Studio API**, choose or fetch a Gemini model (default: `gemini-3.5-flash`), and tap **Test & Save**.
3. **Optional — Connect Google Drive:**
   - In Google Cloud Console, enable the **Google Drive API** and create an **Android OAuth 2.0 Client ID** matching package `com.aistudio.synapsedmn.kxmqrp` and the SHA-1 shown in the app's **OAuth Setup Guide** (or GitHub Actions summary).
   - Sign in on the **Sync** tab, choose/create a Drive folder, and select **Phone → Drive** or **Two-Way Sync**.

---

## Build & Test

### Requirements
- JDK 21
- Android SDK (`minSdk = 24`, `compileSdk = 36`, `targetSdk = 36`)
- Gradle 9.3.1

### Commands

```bash
# Run local JVM & Robolectric unit tests
gradle :app:testDebugUnitTest --console=plain

# Build Debug APK
gradle :app:assembleDebug

# Build Release APK / AAB (requires KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD env vars)
gradle :app:assembleRelease :app:bundleRelease
```

---

## Tech Stack

- **UI:** Kotlin, Jetpack Compose, Material Design 3, Navigation Compose
- **Persistence:** Room (`2.7.0`), Preferences DataStore (`1.1.7`), Android Storage Access Framework (`DocumentFile`)
- **Background & Media:** WorkManager (`2.10.0`), `MediaRecorder`, `MediaExtractor` / `MediaMuxer`, `MediaPlayer`
- **Networking & Auth:** OkHttp (`4.10.0`), Retrofit (`2.12.0`), Moshi (`1.15.2`), Google Play Services Auth (`21.3.0`), Jetpack Credential Manager (`1.5.0`)
- **Testing:** JUnit 4, Robolectric (`4.16.1`), Kotlin Coroutines Test

# Architecture & Persistence

Synapse DMN is structured around a reactive MVVM + single-orchestrator architecture designed to survive app minimization, OS background execution limits, and Storage Access Framework (SAF) folder permissions.

---

## 1. High-Level Architecture

```
Jetpack Compose UI (Material 3)
 ├── StudioScreen   ──┐
 ├── GraphScreen    ──┼──▶ SynapseViewModel (StateFlow<MainUiState>, SharedFlow snackbars)
 ├── VaultScreen    ──┤          │
 └── SyncScreen     ──┘          ▼
                     DmnPipelineOrchestrator (ProcessMutex & SyncMutex)
                      ├── ObsidianVaultRepository (SAF DocumentFile I/O + Graph Parser)
                      ├── GeminiDmnService        (OkHttp resumable upload + generateContent)
                      ├── GoogleDriveSyncService  (OkHttp Drive v3 + .synapse_sync_manifest.json)
                      ├── GoogleDriveAuthManager  (Identity AuthorizationClient + OAuth2 userinfo)
                      ├── VaultSettingsRepository (Preferences DataStore)
                      └── AppDatabase (Room: processing_jobs, sync_records, sync_logs)
                                 ▲
                                 │ Driven in background by
                      WorkManager Foreground Workers
                       ├── DmnProcessWorker (FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                       └── DriveSyncWorker  (FOREGROUND_SERVICE_TYPE_DATA_SYNC)
```

### Key Singletons (`SynapseApplication`)
All core services are lazily instantiated in `SynapseApplication` using simple constructor injection:
- `AppDatabase` (`synapse_dmn_db`, Room v1)
- `VaultSettingsRepository` (`synapse_vault_prefs` DataStore)
- `ObsidianVaultRepository`
- `GeminiDmnService`
- `GoogleDriveAuthManager`
- `GoogleDriveSyncService`
- `DmnPipelineOrchestrator`
- `DmnAudioRecorder` & `DmnAudioPlayer`

---

## 2. UI Navigation & Adaptive Shell (`AppContainer.kt`)

- Single-activity Compose host (`MainActivity`) calls `enableEdgeToEdge()` and triggers `viewModel.refreshAll()` in `onResume()` so external Obsidian or file-manager changes are reflected immediately.
- Responsive navigation switches automatically based on screen width:
  - **Compact screens:** Bottom M3 `NavigationBar`.
  - **Medium / Expanded screens (`>= 600dp`):** Side `NavigationRail`.
- Four top-level destinations (`AppDestination`):
  1. **Studio** (`StudioScreen`): Voice recorder, text capture composer, inbox queue, live synthesis status banner/dialog, and synthesis history.
  2. **Graph** (`GraphScreen`): Interactive pinch/pan/zoom force-directed knowledge graph and node inspector bottom sheet.
  3. **Vault** (`VaultScreen`): Daily Notes, Atomic Nodes, and raw `.md` + archived `.m4a` browser with inline checklist toggling, audio playback, and session deletion.
  4. **Sync** (`SyncScreen`): Vault folder picker, Gemini API key & model configuration, Google Drive OAuth & folder selector, sync rules, audio retention settings, and clickable Activity & Sync Logs.

---

## 3. Data Persistence Layers

### A. Preferences DataStore (`VaultSettingsRepository`)
Stored in `synapse_vault_prefs`:

| Setting Field | Default | Purpose |
| :--- | :--- | :--- |
| `vaultTreeUri` / `vaultDisplayName` | `null` / `""` | Persisted SAF `Intent.FLAG_GRANT_READ_URI_PERMISSION \| WRITE` root directory URI |
| `geminiApiKey` | `""` | User-supplied Google AI Studio API key |
| `geminiModel` | `"gemini-3.5-flash"` | Target Gemini model ID |
| `autoProcessAudio` | `true` | Automatically enqueue `DmnProcessWorker` after saving new recordings/notes or on connectivity |
| `driveConnectedEmail` | `null` | Authorized Google account email |
| `driveFolderId` / `driveFolderName` | `null` / `"ObsidianVault"` | Target Google Drive root folder ID & display label |
| `autoDriveSync` | `true` | Automatically sync after synthesis and via periodic 2-hour WorkManager schedule |
| `syncWifiOnly` | `false` | Restrict Drive sync workers to `NetworkType.UNMETERED` |
| `syncDirection` | `SyncDirection.PHONE_TO_DRIVE` | `PHONE_TO_DRIVE` (fast mirror + deletions) or `TWO_WAY` (manifest + Drive Changes API) |
| `conflictStrategy` | `ConflictStrategy.KEEP_NEWER` | Conflict rule for `TWO_WAY` mode (`KEEP_NEWER` or `KEEP_BOTH`) |
| `lastDriveSyncMs` | `0L` | Timestamp of last successful Drive sync |
| `darkTheme` | `true` | Dark Obsidian-style theme vs. light archival paper theme |
| `audioRetentionDays` | `0` | Archived audio retention window (`0` = Keep Forever; `> 0` prunes files older than N days) |

### B. Room Database (`AppDatabase`)
1. **`processing_jobs` (`ProcessingJobEntity`)**
   - Primary key: `audioUri` (supports both audio and text inbox SAF URIs).
   - Tracks `fileName`, `sizeBytes`, `status` (`QUEUED`, `UPLOADING`, `PROCESSING_REMOTE`, `SYNTHESIZING`, `COMPLETED`, `FAILED`), `errorMessage`, `outputNotePath`, `archivedAudioName`, `linkedNodesCsv`, `createdAtMs`, and `updatedAtMs`.
2. **`sync_records` (`SyncRecordEntity`)**
   - Primary key: `relativePath` (e.g., `01_Daily_Notes/2026-11-18.md`).
   - Stores local vs. remote baseline metadata (`driveFileId`, `mimeType`, `localModifiedMs`, `localSizeBytes`, `remoteModifiedMs`, `remoteMd5`, `syncState`, `lastSyncedMs`) to avoid unnecessary transfers and detect true two-way conflicts.
3. **`sync_logs` (`SyncLogEntity`)**
   - Auto-increment `id`, `timestampMs`, `category` (`GEMINI`, `DRIVE`, `VAULT`, `ARCHIVE`), `level` (`INFO`, `SUCCESS`, `ERROR`), concise `message`, and rich multi-line `details` (HTTP status codes, endpoints, raw error payloads, file paths, or stack traces) inspectable on tap in the Sync tab.

### C. Obsidian Vault on Shared Storage (`ObsidianVaultRepository`)
- Uses Android Storage Access Framework (`DocumentFile.fromTreeUri`) so notes and recordings live directly inside the user's real `ObsidianVault` folder on device storage — immediately readable and editable by the Obsidian mobile app without import/export steps.
- Automatically provisions missing vault directories (`00_Audio_Inbox` through `99_Audio_Archive`) during vault initialization and refreshes.

---

## 4. Background Execution & Notification Lifecycle

- **App-Minimization Resilience:** Synthesis and Drive sync are never tied to UI `viewModelScope` lifecycles. Enqueuing work delegates to `WorkManager`:
  - `DmnProcessWorker` (`"dmn_audio_processing"`) requires `NetworkType.CONNECTED`, requests `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`, and runs as a `FOREGROUND_SERVICE_TYPE_DATA_SYNC` foreground worker.
  - `DriveSyncWorker` runs both on-demand (`"manual_drive_sync"`) and every 2 hours (`"periodic_drive_sync"`, `ExistingPeriodicWorkPolicy.UPDATE`) when auto-sync is enabled.
- **Deterministic Notification Cleanup (`ForegroundNotificationHelper`):**
  - Uses a dedicated monochrome `ic_notification_synapse` vector icon cleanly inset within a `24dp` viewport so status bar and notification badges never crop the graphic.
  - Progress updates flow strictly through `CoroutineWorker.setForeground(...)` while foreground mode is active rather than fighting `SystemForegroundService` with direct `ongoing = true` notifications.
  - Both workers guard emissions with an `AtomicBoolean`, call `watcher.cancelAndJoin()`, and explicitly invoke `ForegroundNotificationHelper.cancelNotification(...)` inside `withContext(NonCancellable)` in `finally` so notifications always disappear upon completion, error, or cancellation.
- **Keep Screen Awake:** `StudioScreen` sets `view.keepScreenOn = true` via `DisposableEffect` while visible so long voice journaling sessions do not lock mid-thought.

---

Back to [README](../README.md)

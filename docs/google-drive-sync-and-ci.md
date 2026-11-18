# Google Drive Sync, Diagnostics & CI/CD

This document covers Google Drive OAuth setup, the metadata-manifest sync engine (`Phone → Drive` mirror vs. `Two-Way Sync`), clickable debugging logs, and automated GitHub Actions builds.

---

## 1. Google Drive Authorization (`GoogleDriveAuthManager`)

Synapse DMN authenticates directly via Google Play Services Identity (`Identity.getAuthorizationClient`) requesting three scopes:
- `openid`
- `https://www.googleapis.com/auth/drive`
- `https://www.googleapis.com/auth/userinfo.email`

### Built-In Signing Diagnostics & `UNREGISTERED_ON_API_CONSOLE` Help
- Reads the live APK signing certificate SHA-1 (`readSigningDiagnostics`) and package name (`com.aistudio.synapsedmn.kxmqrp`) and renders them inside the Sync screen's **OAuth Setup Guide** expandable card with one-tap copy buttons.
- Translates Google `ApiException` codes (`10` / `8` `UNREGISTERED_ON_API_CONSOLE` / `DEVELOPER_ERROR`, `12500`, `12501`, `7`) into actionable remediation instructions.
- Drive folder selection uses an on-demand modal popup (**Choose Folder** / **Create New Folder**) rather than crowding the main Sync screen with root folder listings.

---

## 2. Metadata-Manifest Drive Sync (`GoogleDriveSyncService`)

To prevent pulling hundreds of vault files on every sync, Synapse DMN maintains a lightweight index file inside the Drive root folder:
- **`.synapse_sync_manifest.json`** — stores `version`, `updatedAtMs`, `driveStartPageToken`, relative folder IDs (`folders`), and per-file metadata (`id`, `mimeType`, `localModifiedMs`, `remoteModifiedMs`, `sizeBytes`, `md5Checksum`).

### Bootstrap & Incremental Flow
1. **Local Snapshot Scan:** Walks the SAF `ObsidianVault` tree once in memory, indexing non-hidden files (`localFiles`) and recording `sizeBytes` + `lastModifiedMs`.
2. **Manifest Load / One-Time Bootstrap:**
   - Fetches `.synapse_sync_manifest.json` from the Drive target folder.
   - If the manifest does not exist yet (first sync on an existing folder), performs a one-time recursive Drive scan, records the Drive Changes API `startPageToken`, and writes `.synapse_sync_manifest.json` so subsequent syncs never scan the full remote tree.

### Mode A: `Phone → Drive` (Default Fast Mirror)
Optimized for workflows where notes and recordings originate primarily on the phone:
1. **Zero Remote Scanning:** Skips Drive delta/tree calls after manifest load (assuming manifest exists).
2. **Local Delta Detection (`hasLocalChangedAgainstManifest`):** Compares each local file against `manifest.files[path]` and Room `sync_records` (`sizeBytes != manifest.sizeBytes` or `|localModifiedMs - baselineLocalModMs| > 2500ms`). Unchanged files are skipped immediately without network transfers.
3. **Upload New / Updated Files:** Creates missing Drive subfolders on demand and uploads new (`multipart`) or modified (`PATCH` upload) files, updating both the manifest and `sync_records`.
4. **Mirror Local Deletions (`computeLocalDeletionsForMirror`):** Any non-dotfile path present in `.synapse_sync_manifest.json` that no longer exists locally on the phone is deleted from Google Drive (`DELETE /drive/v3/files/{id}`, or `PATCH {"trashed": true}` on HTTP `403`, treating `404`/`410` as already removed) and evicted from the manifest and `sync_records`.
5. Writes updated `.synapse_sync_manifest.json` back to Drive only when `manifestDirty == true`.

### Mode B: `Two-Way Sync`
Designed for users editing the same vault across phone and desktop/Obsidian:
1. **Delta Change Query:** Calls Drive's `GET /drive/v3/changes?pageToken={driveStartPageToken}` (`applyDriveChangesDelta`) to update the manifest only for files modified, created, moved, or trashed remotely since the last sync (automatically re-bootstrapping if the token expires with HTTP `410`).
2. **Conflict Resolution (`hasLocalChangedAgainstManifest` + `hasRemoteChangedAgainstBaseline`):**
   - Local-only or remote-only edits transfer immediately in the appropriate direction.
   - When both sides changed independently since the last `SyncRecordEntity` baseline:
     - **`KEEP_NEWER`:** Compares `localModifiedMs` vs. `remoteModifiedMs` and keeps the newer version.
     - **`KEEP_BOTH`:** Saves the remote copy alongside as `*_conflict_<timestamp>.ext` and pushes the local file so neither edit is lost.

---

## 3. Clickable Activity & Sync Logs

Every Gemini call, Drive sync run, retention prune, and session deletion writes a `SyncLogEntity` entry (`GEMINI`, `DRIVE`, `VAULT`, `ARCHIVE`).
- Tapping any log row opens a **Log Details Dialog** displaying category, status level, timestamp, full message, raw HTTP status/endpoints/payloads/changed-path summaries, and a **Copy Details** button for debugging.

---

## 4. Build Configuration & GitHub Actions CI/CD

- **Gradle Version Catalog (`gradle/libs.versions.toml`):**
  - Android Gradle Plugin `9.1.1`, Kotlin `2.2.10`, Compose BOM `2024.09.00`, KSP `2.3.6`, Room `2.7.0`, WorkManager `2.10.0`, OkHttp `4.10.0`, Retrofit `2.12.0`, Moshi `1.15.2`, DataStore `1.1.7`, Robolectric `4.16.1`.
  - Application ID: `com.aistudio.synapsedmn.kxmqrp` (`minSdk = 24`, `compileSdk = 36`, `targetSdk = 36`).
- **Android CI (`.github/workflows/android-ci.yml`):**
  - Runs on `push` / `pull_request` to `main` / `master` and `workflow_dispatch`.
  - Decodes or provisions `debug.keystore`, prints the debug SHA-1 into `$GITHUB_STEP_SUMMARY`, runs `gradle :app:testDebugUnitTest --no-daemon --max-workers=2 --stacktrace`, builds `assembleDebug`, and uploads `synapse-dmn-debug-apk`.
- **Android Release (`.github/workflows/android-release.yml`):**
  - Runs on `v*` tag pushes or manual `workflow_dispatch` with optional `release_tag`.
  - Runs unit tests, builds Debug APK (plus signed Release APK & AAB when `RELEASE_KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD` secrets are configured), extracts `app_name` from `strings.xml`, renames every artifact uniquely as `<AppSlug>-<ReleaseTag>-<variant>.{apk,aab}` (e.g., `Synapse-DMN-v1.0.1-debug.apk`), and publishes them to GitHub Releases.

---

Back to [README](../README.md)

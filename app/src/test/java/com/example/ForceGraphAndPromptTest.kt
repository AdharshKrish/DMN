package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.drive.DriveSyncManifest
import com.example.data.drive.GoogleDriveAuthManager
import com.example.data.drive.GoogleDriveSyncService
import com.example.data.drive.ManifestFileEntry
import com.example.data.gemini.GeminiDmnService
import com.example.data.local.SyncRecordEntity
import com.example.data.prefs.SyncDirection
import com.example.data.prefs.VaultSettings
import com.example.data.vault.GraphEdge
import com.example.data.vault.GraphNode
import com.example.data.vault.NodeGroup
import com.example.data.vault.ObsidianVaultRepository
import com.example.graph.ForceGraphEngine
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ForceGraphAndPromptTest {

    @Test
    fun `force graph engine positions all nodes with distinct finite coordinates`() {
        val nodes = listOf(
            GraphNode("2026-11-16", "2026-11-16", "01_Daily_Notes/2026-11-16.md", NodeGroup.DAILY, listOf("#dmn"), listOf("Architecture", "Flow"), emptyList(), true),
            GraphNode("architecture", "Architecture", "02_Atomic_Nodes/Concepts/Architecture.md", NodeGroup.INSIGHT, listOf("#insight"), emptyList(), listOf("2026-11-16"), true),
            GraphNode("flow", "Flow", null, NodeGroup.GHOST, emptyList(), emptyList(), listOf("2026-11-16"), false)
        )
        val edges = listOf(
            GraphEdge("2026-11-16", "architecture"),
            GraphEdge("2026-11-16", "flow")
        )

        val layout = ForceGraphEngine.computeLayout(nodes, edges)
        assertEquals(3, layout.size)
        layout.values.forEach { positioned ->
            assertFalse(positioned.position.x.isNaN())
            assertFalse(positioned.position.y.isNaN())
            assertTrue(positioned.radius > 0f)
        }
        assertTrue(layout["2026-11-16"]!!.position != layout["architecture"]!!.position)
    }

    @Test
    fun `gemini prompt includes existing vault nodes as wikilinks and timestamp`() {
        val service = GeminiDmnService(ApplicationProvider.getApplicationContext())
        val prompt = service.buildDmnPrompt(listOf("Focus", "Obsidian Setup"), "04:15 PM")
        assertTrue(prompt.contains("[[Focus]]"))
        assertTrue(prompt.contains("[[Obsidian Setup]]"))
        assertTrue(prompt.contains("04:15 PM"))
        assertTrue(prompt.contains("Stream of Consciousness (Cleaned)"))
        assertTrue(prompt.contains("<RAW_TRANSCRIPT>"))
        assertTrue(prompt.contains("<DMN_MARKDOWN>"))
    }

    @Test
    fun `gemini parses dual raw transcript and dmn markdown blocks cleanly`() {
        val service = GeminiDmnService(ApplicationProvider.getApplicationContext())
        val simulated = """
            <RAW_TRANSCRIPT>
            Um so yeah I was thinking about uh Obsidian graphs and stuff.
            </RAW_TRANSCRIPT>

            <DMN_MARKDOWN>
            ## 🕒 DMN Session — 04:15 PM
            **Tags:** #dmn #obsidian
            ### 🧠 DMN Synthesis & Graph Connections
            - [[Obsidian]]
            ### 🎙️ Stream of Consciousness (Cleaned)
            I was reflecting on [[Obsidian]] graphs.
            </DMN_MARKDOWN>
        """.trimIndent()

        val (raw, dmn) = service.parseAudioDualOutput(simulated)
        assertEquals("Um so yeah I was thinking about uh Obsidian graphs and stuff.", raw)
        assertTrue(dmn.startsWith("## 🕒 DMN Session — 04:15 PM"))
        assertFalse(dmn.contains("<RAW_TRANSCRIPT>"))
        assertTrue(dmn.contains("[[Obsidian]]"))
    }

    @Test
    fun `gemini text dmn prompt preserves raw text and existing nodes`() {
        val service = GeminiDmnService(ApplicationProvider.getApplicationContext())
        val rawInput = "Line 1: Raw pasted transcript\nLine 2: Talking about Sleep Hygiene."
        val prompt = service.buildTextDmnPrompt(rawInput, listOf("Sleep Hygiene"), "09:30 AM")
        assertTrue(prompt.contains(rawInput))
        assertTrue(prompt.contains("[[Sleep Hygiene]]"))
        assertTrue(prompt.contains("09:30 AM"))
    }

    @Test
    fun `audio retention cutoff and timestamp parser handle forever and day windows`() {
        val nowMs = 1_800_000_000_000L
        assertNull(ObsidianVaultRepository.computeRetentionCutoffMs(0, nowMs))
        assertEquals(
            nowMs - 7L * 86_400_000L,
            ObsidianVaultRepository.computeRetentionCutoffMs(7, nowMs)
        )

        val parsed = ObsidianVaultRepository.parseArchiveTimestampMs("20261116_091530_DMN_session.m4a")
        assertNotNull(parsed)
        assertNull(ObsidianVaultRepository.parseArchiveTimestampMs("random_file.m4a"))
    }

    @Test
    fun `gemini error details include http code status model and details`() {
        val service = GeminiDmnService(ApplicationProvider.getApplicationContext())
        val json503 = """
            {
              "error": {
                "code": 503,
                "message": "This model is currently experiencing high demand.",
                "status": "UNAVAILABLE",
                "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "MODEL_OVERLOADED"}]
              }
            }
        """.trimIndent()
        val formatted = service.parseGeminiErrorDetails(503, "gemini-flash-latest", json503)
        assertTrue(formatted.contains("HTTP 503"))
        assertTrue(formatted.contains("UNAVAILABLE"))
        assertTrue(formatted.contains("gemini-flash-latest"))
        assertTrue(formatted.contains("MODEL_OVERLOADED"))

        val plainFallback = service.parseGeminiErrorDetails(502, "gemini-3.8-flash", "Bad Gateway HTML")
        assertTrue(plainFallback.contains("HTTP 502"))
        assertTrue(plainFallback.contains("gemini-3.8-flash"))
        assertTrue(plainFallback.contains("Bad Gateway HTML"))
        assertTrue(service.readTimeoutSeconds() >= 300L)
        assertTrue(GeminiDmnService.MAX_RETRY_WINDOW_MS >= 300_000L)

        val modelsJson = """
            {
              "models": [
                {"name": "models/text-embedding-004", "supportedGenerationMethods": ["embedContent"]},
                {"name": "models/gemini-flash-latest", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-3.5-flash-lite", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-3.8-flash", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-3.5-flash", "supportedGenerationMethods": ["generateContent"]}
              ]
            }
        """.trimIndent()
        val parsedModels = service.parseAvailableModelsResponse(modelsJson)
        assertEquals(
            listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-flash-latest"),
            parsedModels
        )
    }

    @Test
    fun `gemini prompts enforce zero emojis and rambler clean prose`() {
        val service = GeminiDmnService(ApplicationProvider.getApplicationContext())
        val audioPrompt = service.buildDmnPrompt(listOf("Focus"), "10:00 AM")
        val textPrompt = service.buildTextDmnPrompt("My draft note", listOf("Focus"), "10:05 AM")

        assertTrue(audioPrompt.contains("Rambler", ignoreCase = true))
        assertTrue(audioPrompt.contains("Do NOT use any emojis"))
        assertTrue(textPrompt.contains("Do NOT use any emojis"))

        val bannedEmojis = listOf("🕒", "🧠", "🎙️", "💡", "📝", "📅", "✨", "🎯")
        bannedEmojis.forEach { emoji ->
            assertFalse("Audio prompt should not contain $emoji", audioPrompt.contains(emoji))
            assertFalse("Text prompt should not contain $emoji", textPrompt.contains(emoji))
        }
    }

    @Test
    fun `removeDmnSessionFromMarkdown strips targeted block and extracts archive references`() {
        val sampleDaily = """
            |---
            |date: 2026-11-16
            |type: dmn-daily-note
            |---
            |
            |# 2026-11-16
            |
            |---
            |## DMN Session — 09:00 AM
            |**Tags:** #dmn
            |> **Original Audio:** ![[99_Audio_Archive/20261116_090000_first.m4a]]
            |> **Transcript Archive:** ![[98_Transcript_Archive/20261116_090000_first.md]]
            |
            |First session content.
            |
            |---
            |## DMN Note Session — 11:30 AM
            |**Tags:** #dmn #text-input
            |> **Transcript Archive:** ![[98_Transcript_Archive/20261116_113000_second.md]]
            |
            |Second session content.
        """.trimMargin()

        val firstRemoval = ObsidianVaultRepository.removeDmnSessionFromMarkdown(
            content = sampleDaily,
            sessionHeading = "## DMN Session — 09:00 AM"
        )
        assertTrue(firstRemoval.removed)
        assertNotNull(firstRemoval.remainingMarkdown)
        assertEquals(listOf("20261116_090000_first.m4a"), firstRemoval.referencedAudioNames)
        assertEquals(listOf("20261116_090000_first.md"), firstRemoval.referencedTranscriptNames)
        assertFalse(firstRemoval.remainingMarkdown!!.contains("09:00 AM"))
        assertTrue(firstRemoval.remainingMarkdown!!.contains("## DMN Note Session — 11:30 AM"))

        val secondRemoval = ObsidianVaultRepository.removeDmnSessionFromMarkdown(
            content = firstRemoval.remainingMarkdown!!,
            matchFileName = "20261116_113000_second.md"
        )
        assertTrue(secondRemoval.removed)
        assertNull(secondRemoval.remainingMarkdown)
        assertEquals(listOf("20261116_113000_second.md"), secondRemoval.referencedTranscriptNames)
    }

    @Test
    fun `drive auth manager maps unregistered console status 8 and reads package name`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = GoogleDriveAuthManager(ctx)
        val mapped = manager.mapGoogleAuthError(
            ApiException(Status(8, "[8] Unknown error [status=UNREGISTERED_ON_API_CONSOLE]"))
        )
        assertTrue(mapped.contains("UNREGISTERED_ON_API_CONSOLE"))
        assertTrue(mapped.contains("Android OAuth 2.0 Client ID"))

        val diagnostics = GoogleDriveAuthManager.readSigningDiagnostics(ctx)
        assertEquals(ctx.packageName, diagnostics.packageName)
    }

    @Test
    fun `drive sync manifest round-trips and detects local and remote deltas accurately`() {
        assertEquals(SyncDirection.PHONE_TO_DRIVE, VaultSettings().syncDirection)

        val manifest = DriveSyncManifest(
            version = 1,
            updatedAtMs = 1_750_000_000_000L,
            driveStartPageToken = "page-token-42",
            folders = mutableMapOf("01_Daily_Notes" to "folder_id_1"),
            files = mutableMapOf(
                "01_Daily_Notes/2026-11-18.md" to ManifestFileEntry(
                    id = "drive_file_1",
                    mimeType = "text/markdown",
                    localModifiedMs = 1_750_000_000_000L,
                    remoteModifiedMs = 1_750_000_005_000L,
                    sizeBytes = 512L,
                    md5Checksum = "abc123"
                )
            )
        )

        val serialized = GoogleDriveSyncService.serializeManifestJson(manifest)
        val parsed = GoogleDriveSyncService.parseManifestJson(serialized)
        assertNotNull(parsed)
        assertEquals("page-token-42", parsed!!.driveStartPageToken)
        assertEquals("folder_id_1", parsed.folders["01_Daily_Notes"])
        val parsedEntry = parsed.files["01_Daily_Notes/2026-11-18.md"]!!
        assertEquals("drive_file_1", parsedEntry.id)
        assertEquals(512L, parsedEntry.sizeBytes)
        assertEquals("abc123", parsedEntry.md5Checksum)

        // Unchanged local file (within 2.5s window and identical size)
        assertFalse(
            GoogleDriveSyncService.hasLocalChangedAgainstManifest(
                localSizeBytes = 512L,
                localModifiedMs = 1_750_000_001_000L,
                manifestEntry = parsedEntry,
                prevRecord = null
            )
        )
        // Changed size triggers upload
        assertTrue(
            GoogleDriveSyncService.hasLocalChangedAgainstManifest(
                localSizeBytes = 640L,
                localModifiedMs = 1_750_000_000_000L,
                manifestEntry = parsedEntry,
                prevRecord = null
            )
        )
        // Changed timestamp (>2500ms) triggers upload
        assertTrue(
            GoogleDriveSyncService.hasLocalChangedAgainstManifest(
                localSizeBytes = 512L,
                localModifiedMs = 1_750_000_010_000L,
                manifestEntry = parsedEntry,
                prevRecord = null
            )
        )

        val matchingRecord = SyncRecordEntity(
            relativePath = "01_Daily_Notes/2026-11-18.md",
            driveFileId = "drive_file_1",
            mimeType = "text/markdown",
            localModifiedMs = 1_750_000_000_000L,
            localSizeBytes = 512L,
            remoteModifiedMs = 1_750_000_005_000L,
            remoteMd5 = "abc123",
            syncState = "SYNCED"
        )
        assertFalse(GoogleDriveSyncService.hasRemoteChangedAgainstBaseline(parsedEntry, matchingRecord))
        assertTrue(
            GoogleDriveSyncService.hasRemoteChangedAgainstBaseline(
                parsedEntry.copy(md5Checksum = "def456"),
                matchingRecord
            )
        )
        assertTrue(
            GoogleDriveSyncService.hasRemoteChangedAgainstBaseline(
                parsedEntry.copy(remoteModifiedMs = 1_750_000_020_000L),
                matchingRecord
            )
        )
    }
}

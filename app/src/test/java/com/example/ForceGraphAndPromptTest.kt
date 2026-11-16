package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.drive.GoogleDriveAuthManager
import com.example.data.gemini.GeminiDmnService
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
}

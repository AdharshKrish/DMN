package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.gemini.GeminiDmnService
import com.example.data.vault.GraphEdge
import com.example.data.vault.GraphNode
import com.example.data.vault.NodeGroup
import com.example.graph.ForceGraphEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }
}

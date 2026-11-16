package com.example.data.vault

import android.net.Uri

enum class NodeGroup(val label: String) {
    DAILY("Daily DMN"),
    INSIGHT("Insight / Concept"),
    GOAL("Goal / Project"),
    GHOST("Uncreated Link")
}

data class InboxAudioFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val mimeType: String
)

data class VaultNote(
    val relativePath: String,
    val title: String,
    val folderType: String, // "01_Daily_Notes", "02_Atomic_Nodes", "Other"
    val content: String,
    val lastModifiedMs: Long,
    val tags: List<String>,
    val outgoingLinks: List<String>,
    val pendingTasks: Int,
    val completedTasks: Int,
    val embeddedAudioNames: List<String>
)

data class GraphNode(
    val id: String, // normalized lowercase title
    val title: String,
    val relativePath: String?, // null if ghost node (uncreated [[WikiLink]])
    val group: NodeGroup,
    val tags: List<String>,
    val outgoingLinks: List<String>,
    val incomingLinks: List<String>,
    val existsAsFile: Boolean
) {
    val connectionCount: Int get() = (outgoingLinks + incomingLinks).distinct().size
}

data class GraphEdge(
    val sourceId: String,
    val targetId: String
)

data class KnowledgeGraphData(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    val notesByPath: Map<String, VaultNote> = emptyMap(),
    val totalDailyNotes: Int = 0,
    val totalAtomicNodes: Int = 0,
    val totalGhostLinks: Int = 0,
    val totalPendingTasks: Int = 0
)

data class VaultFileEntry(
    val relativePath: String,
    val uri: Uri,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

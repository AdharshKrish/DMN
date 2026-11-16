package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.vault.GraphNode
import com.example.data.vault.NodeGroup
import com.example.graph.ForceGraphEngine
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalSynapseColors
import com.example.ui.viewmodel.MainUiState
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GraphScreen(
    state: MainUiState,
    onOpenNoteByPath: (String) -> Unit,
    onCreateHubNote: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sem = LocalSynapseColors.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<NodeGroup?>(null) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }

    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var canvasCenter by remember { mutableStateOf(Offset.Zero) }

    val filteredNodes = remember(state.graphData.nodes, searchQuery, selectedGroup) {
        state.graphData.nodes.filter { node ->
            val matchesGroup = selectedGroup == null || node.group == selectedGroup
            val matchesSearch = searchQuery.isBlank() ||
                node.title.contains(searchQuery, ignoreCase = true) ||
                node.tags.any { it.contains(searchQuery, ignoreCase = true) }
            matchesGroup && matchesSearch
        }
    }

    val visibleIds = remember(filteredNodes) { filteredNodes.map { it.id }.toSet() }
    val filteredEdges = remember(state.graphData.edges, visibleIds) {
        state.graphData.edges.filter { it.sourceId in visibleIds && it.targetId in visibleIds }
    }

    val positionedMap = remember(filteredNodes, filteredEdges) {
        ForceGraphEngine.computeLayout(filteredNodes, filteredEdges)
    }

    val selectedNode = selectedNodeId?.let { id ->
        state.graphData.nodes.firstOrNull { it.id == id }
    }

    val textMeasurer: TextMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Knowledge Graph",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${state.graphData.nodes.size} nodes • ${state.graphData.edges.size} WikiLink edges",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    label = "${state.graphData.totalGhostLinks} uncreated",
                    color = sem.ghostNode,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter nodes or #tags...") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("graph_search_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedGroup == null,
                    onClick = { selectedGroup = null },
                    label = { Text("All") },
                    modifier = Modifier.testTag("graph_filter_all")
                )
                NodeGroup.entries.forEach { grp ->
                    FilterChip(
                        selected = selectedGroup == grp,
                        onClick = { selectedGroup = if (selectedGroup == grp) null else grp },
                        label = { Text(grp.label) },
                        modifier = Modifier.testTag("graph_filter_${grp.name.lowercase()}")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            if (filteredNodes.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No Graph Nodes Yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Record a DMN voice session or connect an ObsidianVault with [[WikiLinks]] to illuminate your personal graph.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("knowledge_graph_canvas")
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(0.35f, 3.2f)
                                panOffset += pan
                            }
                        }
                        .pointerInput(positionedMap, zoomScale, panOffset, canvasCenter) {
                            detectTapGestures { tap ->
                                val worldTap = (tap - canvasCenter - panOffset) / zoomScale
                                val hit = positionedMap.values.firstOrNull { p ->
                                    val hitRadius = (p.radius + 16f) / zoomScale.coerceAtLeast(0.7f)
                                    hypot(worldTap.x - p.position.x, worldTap.y - p.position.y) <= hitRadius
                                }
                                selectedNodeId = hit?.node?.id
                            }
                        }
                ) {
                    canvasCenter = Offset(size.width / 2f, size.height / 2f)
                    val origin = canvasCenter + panOffset

                    // Draw edges
                    filteredEdges.forEach { edge ->
                        val src = positionedMap[edge.sourceId] ?: return@forEach
                        val tgt = positionedMap[edge.targetId] ?: return@forEach
                        val highlighted = selectedNodeId != null &&
                            (edge.sourceId == selectedNodeId || edge.targetId == selectedNodeId)
                        drawLine(
                            color = if (highlighted) {
                                primaryColor.copy(alpha = 0.9f)
                            } else {
                                outlineColor.copy(alpha = 0.45f)
                            },
                            start = origin + src.position * zoomScale,
                            end = origin + tgt.position * zoomScale,
                            strokeWidth = if (highlighted) 3.2f else 1.5f
                        )
                    }

                    // Draw nodes & labels
                    positionedMap.values.forEach { p ->
                        val screenPos = origin + p.position * zoomScale
                        val r = p.radius * zoomScale.coerceIn(0.65f, 1.8f)
                        val nodeColor = when (p.node.group) {
                            NodeGroup.DAILY -> sem.dailyBlue
                            NodeGroup.INSIGHT -> sem.insightPurple
                            NodeGroup.GOAL -> sem.emerald
                            NodeGroup.GHOST -> sem.ghostNode
                        }
                        val isSelected = p.node.id == selectedNodeId

                        if (isSelected) {
                            drawCircle(
                                color = sem.amber.copy(alpha = 0.28f),
                                radius = r + 12f,
                                center = screenPos
                            )
                        }

                        if (p.node.existsAsFile) {
                            drawCircle(
                                color = nodeColor,
                                radius = r,
                                center = screenPos
                            )
                            drawCircle(
                                color = if (isSelected) sem.amber else Color.White.copy(alpha = 0.25f),
                                radius = r,
                                center = screenPos,
                                style = Stroke(width = if (isSelected) 3.5f else 1.2f)
                            )
                        } else {
                            drawCircle(
                                color = nodeColor.copy(alpha = 0.22f),
                                radius = r,
                                center = screenPos
                            )
                            drawCircle(
                                color = if (isSelected) sem.amber else nodeColor,
                                radius = r,
                                center = screenPos,
                                style = Stroke(
                                    width = 2.2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                                )
                            )
                        }

                        if (zoomScale >= 0.72f || isSelected || p.node.connectionCount >= 2) {
                            val measured = textMeasurer.measure(
                                text = p.node.title.take(24),
                                style = labelStyle.copy(
                                    color = if (p.node.existsAsFile) onSurfaceColor else sem.ghostNode,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            )
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(
                                    x = screenPos.x - measured.size.width / 2f,
                                    y = screenPos.y + r + 5f
                                )
                            )
                        }
                    }
                }

                // Legend overlay
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LegendDot("Daily Note", sem.dailyBlue)
                        LegendDot("Insight / Concept", sem.insightPurple)
                        LegendDot("Goal / Project", sem.emerald)
                        LegendDot("Uncreated Link", sem.ghostNode)
                    }
                }

                // Zoom / Reset Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = { zoomScale = (zoomScale * 1.25f).coerceAtMost(3.2f) },
                        modifier = Modifier.testTag("graph_zoom_in_button")
                    ) {
                        Icon(Icons.Outlined.ZoomIn, contentDescription = "Zoom in")
                    }
                    SmallFloatingActionButton(
                        onClick = { zoomScale = (zoomScale * 0.8f).coerceAtLeast(0.35f) },
                        modifier = Modifier.testTag("graph_zoom_out_button")
                    ) {
                        Icon(Icons.Outlined.ZoomOut, contentDescription = "Zoom out")
                    }
                    SmallFloatingActionButton(
                        onClick = {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        },
                        modifier = Modifier.testTag("reset_graph_zoom_button")
                    ) {
                        Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Center graph")
                    }
                }
            }
        }
    }

    if (selectedNode != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedNodeId = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "[[${selectedNode.title}]]",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = selectedNode.relativePath ?: "Uncreated Atomic Node",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        label = selectedNode.group.label,
                        color = when (selectedNode.group) {
                            NodeGroup.DAILY -> sem.dailyBlue
                            NodeGroup.INSIGHT -> sem.insightPurple
                            NodeGroup.GOAL -> sem.emerald
                            NodeGroup.GHOST -> sem.ghostNode
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }

                if (selectedNode.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedNode.tags.forEach { tag ->
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (selectedNode.outgoingLinks.isNotEmpty()) {
                    Text(
                        text = "Outgoing Links (${selectedNode.outgoingLinks.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        selectedNode.outgoingLinks.forEach { linkTitle ->
                            SuggestionChip(
                                onClick = {
                                    selectedNodeId = linkTitle.trim().lowercase()
                                },
                                label = { Text("[[$linkTitle]]", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                if (selectedNode.incomingLinks.isNotEmpty()) {
                    Text(
                        text = "Backlinks (${selectedNode.incomingLinks.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        selectedNode.incomingLinks.forEach { backTitle ->
                            SuggestionChip(
                                onClick = {
                                    selectedNodeId = backTitle.trim().lowercase()
                                },
                                label = { Text("← [[$backTitle]]", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                if (selectedNode.relativePath != null) {
                    Button(
                        onClick = {
                            val path = selectedNode.relativePath
                            selectedNodeId = null
                            onOpenNoteByPath(path)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_graph_node_button")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Note in Reader")
                    }
                } else {
                    Button(
                        onClick = {
                            val title = selectedNode.title
                            selectedNodeId = null
                            onCreateHubNote(title)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create_hub_note_button")
                    ) {
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Hub Note in 02_Atomic_Nodes")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

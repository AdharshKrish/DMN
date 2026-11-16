package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.vault.ObsidianVaultRepository
import com.example.data.vault.VaultNote
import com.example.ui.components.MarkdownNoteReader
import com.example.ui.components.StatusPill
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.LocalSynapseColors
import com.example.ui.viewmodel.MainUiState

enum class VaultFilter(val label: String) {
    ALL("All Notes"),
    DAILY("Daily DMN"),
    ATOMIC("Atomic Nodes"),
    TASKS("Open Tasks")
}

@Composable
fun VaultScreen(
    state: MainUiState,
    onSelectNote: (String?) -> Unit,
    onOpenNoteByTitle: (String) -> Boolean,
    onCreateAtomicNote: (String) -> Unit,
    onToggleTask: (relativePath: String, lineIndex: Int) -> Unit,
    onTogglePlayAudio: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(VaultFilter.ALL) }
    var missingLinkPrompt by remember { mutableStateOf<String?>(null) }

    val sortedNotes = remember(state.graphData.notesByPath, searchQuery, filter) {
        state.graphData.notesByPath.values
            .filter { note ->
                val matchesFilter = when (filter) {
                    VaultFilter.ALL -> true
                    VaultFilter.DAILY -> note.folderType == ObsidianVaultRepository.DIR_DAILY_NOTES
                    VaultFilter.ATOMIC -> note.folderType == ObsidianVaultRepository.DIR_ATOMIC_NODES
                    VaultFilter.TASKS -> note.pendingTasks > 0
                }
                val matchesSearch = searchQuery.isBlank() ||
                    note.title.contains(searchQuery, ignoreCase = true) ||
                    note.content.contains(searchQuery, ignoreCase = true) ||
                    note.tags.any { it.contains(searchQuery, ignoreCase = true) }
                matchesFilter && matchesSearch
            }
            .sortedByDescending { it.lastModifiedMs }
    }

    val handleWikiLinkClick: (String) -> Unit = { title ->
        val found = onOpenNoteByTitle(title)
        if (!found) {
            missingLinkPrompt = title
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWidePane = maxWidth >= 720.dp
        val activeNote = state.selectedNote

        if (isWidePane) {
            Row(modifier = Modifier.fillMaxSize()) {
                VaultNotesListPane(
                    notes = sortedNotes,
                    selectedPath = activeNote?.relativePath,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    onSelectNote = { onSelectNote(it.relativePath) },
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                ) {
                    if (activeNote != null) {
                        MarkdownNoteReader(
                            note = activeNote,
                            playbackState = state.playbackState,
                            showBackButton = false,
                            onBack = {},
                            onWikiLinkClick = handleWikiLinkClick,
                            onToggleTask = { lineIdx -> onToggleTask(activeNote.relativePath, lineIdx) },
                            onTogglePlayAudio = onTogglePlayAudio
                        )
                    } else {
                        EmptyReaderPlaceholder(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        } else {
            if (activeNote != null) {
                BackHandler { onSelectNote(null) }
                MarkdownNoteReader(
                    note = activeNote,
                    playbackState = state.playbackState,
                    showBackButton = true,
                    onBack = { onSelectNote(null) },
                    onWikiLinkClick = handleWikiLinkClick,
                    onToggleTask = { lineIdx -> onToggleTask(activeNote.relativePath, lineIdx) },
                    onTogglePlayAudio = onTogglePlayAudio
                )
            } else {
                VaultNotesListPane(
                    notes = sortedNotes,
                    selectedPath = null,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    onSelectNote = { onSelectNote(it.relativePath) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (missingLinkPrompt != null) {
        val title = missingLinkPrompt!!
        AlertDialog(
            onDismissRequest = { missingLinkPrompt = null },
            title = { Text("Create [[$title]]?") },
            text = {
                Text("This WikiLink does not have a Markdown file yet. Would you like to create a hub note in 02_Atomic_Nodes/Concepts/?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        missingLinkPrompt = null
                        onCreateAtomicNote(title)
                    },
                    modifier = Modifier.testTag("confirm_create_wikilink_button")
                ) {
                    Text("Create Hub Note")
                }
            },
            dismissButton = {
                TextButton(onClick = { missingLinkPrompt = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun VaultNotesListPane(
    notes: List<VaultNote>,
    selectedPath: String?,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filter: VaultFilter,
    onFilterChange: (VaultFilter) -> Unit,
    onSelectNote: (VaultNote) -> Unit,
    modifier: Modifier = Modifier
) {
    val sem = LocalSynapseColors.current
    Column(
        modifier = modifier.padding(top = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Obsidian Vault (${notes.size})",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search notes, tags, or transcripts...") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_search_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VaultFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { onFilterChange(item) },
                        label = { Text(item.label) },
                        modifier = Modifier.testTag("vault_filter_${item.name.lowercase()}")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "No matching notes found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Process a recording in Studio or sync from Google Drive to populate your vault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notes, key = { it.relativePath }) { note ->
                    val isSelected = note.relativePath == selectedPath
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectNote(note) }
                            .testTag("vault_note_${note.title}"),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = note.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = formatRelativeTimestamp(note.lastModifiedMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Text(
                                text = note.relativePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (note.outgoingLinks.isNotEmpty()) {
                                    StatusPill(
                                        label = "${note.outgoingLinks.size} links",
                                        color = sem.insightPurple,
                                        containerColor = sem.insightPurpleContainer
                                    )
                                }
                                if (note.pendingTasks > 0) {
                                    StatusPill(
                                        label = "${note.pendingTasks} tasks",
                                        color = sem.emerald,
                                        containerColor = sem.emeraldContainer,
                                        icon = Icons.Outlined.TaskAlt
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyReaderPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = "Select a Note",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Tap any Daily DMN entry or Atomic Node to read, toggle tasks, and traverse [[WikiLinks]].",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

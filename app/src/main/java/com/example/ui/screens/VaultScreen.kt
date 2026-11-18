package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.AudioPlaybackState
import com.example.data.vault.ArchivedAudioItem
import com.example.data.vault.ArchivedTranscript
import com.example.data.vault.ObsidianVaultRepository
import com.example.data.vault.VaultNote
import com.example.ui.components.MarkdownNoteReader
import com.example.ui.components.StatusPill
import com.example.ui.components.formatBytes
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalSynapseColors
import com.example.ui.viewmodel.MainUiState

enum class VaultFilter(val label: String) {
    ALL("All Notes"),
    DAILY("Daily DMN"),
    ATOMIC("Atomic Nodes"),
    TASKS("Open Tasks"),
    ARCHIVES("Archives")
}

@Composable
fun VaultScreen(
    state: MainUiState,
    onSelectNote: (String?) -> Unit,
    onSelectTranscript: (String?) -> Unit,
    onOpenTranscriptByName: (String) -> Unit,
    onOpenNoteByTitle: (String) -> Boolean,
    onCreateAtomicNote: (String) -> Unit,
    onToggleTask: (relativePath: String, lineIndex: Int) -> Unit,
    onTogglePlayAudio: (String) -> Unit,
    onDeleteSessionFromNote: (dailyNotePath: String, sessionHeading: String) -> Unit,
    onDeleteArchiveSession: (transcriptFileName: String?, audioFileName: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(VaultFilter.ALL) }
    var missingLinkPrompt by remember { mutableStateOf<String?>(null) }
    var archivePendingDelete by remember { mutableStateOf<Pair<String?, String?>?>(null) }

    val sortedNotes = remember(state.graphData.notesByPath, searchQuery, filter) {
        if (filter == VaultFilter.ARCHIVES) {
            emptyList()
        } else {
            state.graphData.notesByPath.values
                .filter { note ->
                    val matchesFilter = when (filter) {
                        VaultFilter.ALL -> true
                        VaultFilter.DAILY -> note.folderType == ObsidianVaultRepository.DIR_DAILY_NOTES
                        VaultFilter.ATOMIC -> note.folderType == ObsidianVaultRepository.DIR_ATOMIC_NODES
                        VaultFilter.TASKS -> note.pendingTasks > 0
                        VaultFilter.ARCHIVES -> false
                    }
                    val matchesSearch = searchQuery.isBlank() ||
                        note.title.contains(searchQuery, ignoreCase = true) ||
                        note.content.contains(searchQuery, ignoreCase = true) ||
                        note.tags.any { it.contains(searchQuery, ignoreCase = true) }
                    matchesFilter && matchesSearch
                }
                .sortedByDescending { it.lastModifiedMs }
        }
    }

    val filteredTranscripts = remember(state.archivedTranscripts, searchQuery) {
        state.archivedTranscripts.filter { item ->
            searchQuery.isBlank() ||
                item.fileName.contains(searchQuery, ignoreCase = true) ||
                item.content.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredAudio = remember(state.archivedAudioFiles, searchQuery) {
        state.archivedAudioFiles.filter { item ->
            searchQuery.isBlank() || item.fileName.contains(searchQuery, ignoreCase = true)
        }
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
        val activeTranscript = state.selectedTranscript

        if (isWidePane) {
            Row(modifier = Modifier.fillMaxSize()) {
                VaultListPane(
                    notes = sortedNotes,
                    archivedTranscripts = filteredTranscripts,
                    archivedAudio = filteredAudio,
                    playbackState = state.playbackState,
                    retentionDays = state.settings.audioRetentionDays,
                    selectedPath = activeNote?.relativePath,
                    selectedTranscriptPath = activeTranscript?.relativePath,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    onSelectNote = { onSelectNote(it.relativePath) },
                    onSelectTranscript = { onSelectTranscript(it.relativePath) },
                    onTogglePlayAudio = onTogglePlayAudio,
                    onRequestDeleteTranscript = { archivePendingDelete = it.fileName to null },
                    onRequestDeleteAudio = { archivePendingDelete = null to it.fileName },
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
                    when {
                        activeTranscript != null -> {
                            ArchivedTranscriptViewer(
                                transcript = activeTranscript,
                                showBackButton = activeNote != null,
                                onBack = { onSelectTranscript(null) },
                                onRequestDelete = { archivePendingDelete = activeTranscript.fileName to null }
                            )
                        }
                        activeNote != null -> {
                            MarkdownNoteReader(
                                note = activeNote,
                                playbackState = state.playbackState,
                                showBackButton = false,
                                onBack = {},
                                onWikiLinkClick = handleWikiLinkClick,
                                onToggleTask = { lineIdx -> onToggleTask(activeNote.relativePath, lineIdx) },
                                onTogglePlayAudio = onTogglePlayAudio,
                                onOpenTranscript = onOpenTranscriptByName,
                                onDeleteSession = { heading ->
                                    onDeleteSessionFromNote(activeNote.relativePath, heading)
                                }
                            )
                        }
                        else -> {
                            EmptyReaderPlaceholder(modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        } else {
            when {
                activeTranscript != null -> {
                    BackHandler { onSelectTranscript(null) }
                    ArchivedTranscriptViewer(
                        transcript = activeTranscript,
                        showBackButton = true,
                        onBack = { onSelectTranscript(null) },
                        onRequestDelete = { archivePendingDelete = activeTranscript.fileName to null }
                    )
                }
                activeNote != null -> {
                    BackHandler { onSelectNote(null) }
                    MarkdownNoteReader(
                        note = activeNote,
                        playbackState = state.playbackState,
                        showBackButton = true,
                        onBack = { onSelectNote(null) },
                        onWikiLinkClick = handleWikiLinkClick,
                        onToggleTask = { lineIdx -> onToggleTask(activeNote.relativePath, lineIdx) },
                        onTogglePlayAudio = onTogglePlayAudio,
                        onOpenTranscript = onOpenTranscriptByName,
                        onDeleteSession = { heading ->
                            onDeleteSessionFromNote(activeNote.relativePath, heading)
                        }
                    )
                }
                else -> {
                    VaultListPane(
                        notes = sortedNotes,
                        archivedTranscripts = filteredTranscripts,
                        archivedAudio = filteredAudio,
                        playbackState = state.playbackState,
                        retentionDays = state.settings.audioRetentionDays,
                        selectedPath = null,
                        selectedTranscriptPath = null,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        filter = filter,
                        onFilterChange = { filter = it },
                        onSelectNote = { onSelectNote(it.relativePath) },
                        onSelectTranscript = { onSelectTranscript(it.relativePath) },
                        onTogglePlayAudio = onTogglePlayAudio,
                        onRequestDeleteTranscript = { archivePendingDelete = it.fileName to null },
                        onRequestDeleteAudio = { archivePendingDelete = null to it.fileName },
                        modifier = Modifier.fillMaxSize()
                    )
                }
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
    if (archivePendingDelete != null) {
        val (tName, aName) = archivePendingDelete!!
        val label = tName ?: aName ?: "session"
        AlertDialog(
            onDismissRequest = { archivePendingDelete = null },
            title = { Text("Delete Entire Session?") },
            text = {
                Text(
                    "This will permanently delete \"$label\" and every linked artifact (Daily Note DMN entry, archived audio, transcript .md, and job record)."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        archivePendingDelete = null
                        onDeleteArchiveSession(tName, aName)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_archive_session_button")
                ) {
                    Text("Delete Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { archivePendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun VaultListPane(
    notes: List<VaultNote>,
    archivedTranscripts: List<ArchivedTranscript>,
    archivedAudio: List<ArchivedAudioItem>,
    playbackState: AudioPlaybackState,
    retentionDays: Int,
    selectedPath: String?,
    selectedTranscriptPath: String?,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filter: VaultFilter,
    onFilterChange: (VaultFilter) -> Unit,
    onSelectNote: (VaultNote) -> Unit,
    onSelectTranscript: (ArchivedTranscript) -> Unit,
    onTogglePlayAudio: (String) -> Unit,
    onRequestDeleteTranscript: (ArchivedTranscript) -> Unit,
    onRequestDeleteAudio: (ArchivedAudioItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val sem = LocalSynapseColors.current
    val itemCountLabel = if (filter == VaultFilter.ARCHIVES) {
        "Archives (${archivedTranscripts.size + archivedAudio.size})"
    } else {
        "Obsidian Vault (${notes.size})"
    }

    Column(
        modifier = modifier.padding(top = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = itemCountLabel,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search notes, tags, or exact archives...") },
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
                        label = {
                            Text(
                                if (item == VaultFilter.ARCHIVES && archivedTranscripts.isNotEmpty()) {
                                    "${item.label} (${archivedTranscripts.size})"
                                } else {
                                    item.label
                                }
                            )
                        },
                        modifier = Modifier.testTag("vault_filter_${item.name.lowercase()}")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filter == VaultFilter.ARCHIVES) {
            ArchivesListContent(
                archivedTranscripts = archivedTranscripts,
                archivedAudio = archivedAudio,
                playbackState = playbackState,
                retentionDays = retentionDays,
                selectedTranscriptPath = selectedTranscriptPath,
                onSelectTranscript = onSelectTranscript,
                onTogglePlayAudio = onTogglePlayAudio,
                onRequestDeleteTranscript = onRequestDeleteTranscript,
                onRequestDeleteAudio = onRequestDeleteAudio
            )
        } else if (notes.isEmpty()) {
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
                        text = "Process a recording or text input in Studio, or sync from Google Drive to populate your vault.",
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
private fun ArchivesListContent(
    archivedTranscripts: List<ArchivedTranscript>,
    archivedAudio: List<ArchivedAudioItem>,
    playbackState: AudioPlaybackState,
    retentionDays: Int,
    selectedTranscriptPath: String?,
    onSelectTranscript: (ArchivedTranscript) -> Unit,
    onTogglePlayAudio: (String) -> Unit,
    onRequestDeleteTranscript: (ArchivedTranscript) -> Unit,
    onRequestDeleteAudio: (ArchivedAudioItem) -> Unit
) {
    val sem = LocalSynapseColors.current
    if (archivedTranscripts.isEmpty() && archivedAudio.isEmpty()) {
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
                    imageVector = Icons.AutoMirrored.Outlined.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = "No Archived Files Yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Exact audio transcriptions and original text inputs are permanently stored in 98_Transcript_Archive/.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (archivedTranscripts.isNotEmpty()) {
            item {
                Text(
                    text = "98_Transcript_Archive (${archivedTranscripts.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(archivedTranscripts, key = { it.relativePath }) { item ->
                val isSelected = item.relativePath == selectedTranscriptPath
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTranscript(item) }
                        .testTag("archived_transcript_${item.fileName}"),
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
                                text = item.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatRelativeTimestamp(item.archivedAtMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = item.content.trim().replace('\n', ' ').take(150),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(
                                    label = if (item.isFromAudio) "Voice Clean" else "Original Note",
                                    color = if (item.isFromAudio) sem.amber else sem.insightPurple,
                                    containerColor = if (item.isFromAudio) sem.amberContainer else sem.insightPurpleContainer
                                )
                                Text(
                                    text = formatBytes(item.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onRequestDeleteTranscript(item) },
                                modifier = Modifier.testTag("delete_archived_transcript_${item.fileName}")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete entire session",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (archivedAudio.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "99_Audio_Archive (${archivedAudio.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = sem.amber
                    )
                    Text(
                        text = if (retentionDays == 0) "Retained Forever" else "Retention: ${retentionDays}d",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(archivedAudio, key = { it.relativePath }) { audio ->
                val isPlaying = playbackState.activeAudioName == audio.fileName && playbackState.isPlaying
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(
                            onClick = { onTogglePlayAudio(audio.fileName) },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(sem.amberContainer)
                                .testTag("archive_play_${audio.fileName}")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause archived audio" else "Play archived audio",
                                tint = sem.amber
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = audio.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${formatBytes(audio.sizeBytes)} • ${formatRelativeTimestamp(audio.archivedAtMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onRequestDeleteAudio(audio) },
                            modifier = Modifier.testTag("delete_archived_audio_${audio.fileName}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete entire session",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.AudioFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun ArchivedTranscriptViewer(
    transcript: ArchivedTranscript,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val context = LocalContext.current
    val sem = LocalSynapseColors.current

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBackButton) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("transcript_viewer_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transcript.fileName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = transcript.relativePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText(transcript.fileName, transcript.content))
                        Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("copy_archived_transcript_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy transcript"
                    )
                }
                IconButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.testTag("delete_transcript_viewer_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete entire DMN session",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = if (transcript.isFromAudio) "Verbatim Voice Transcript" else "Original Input Note",
                    color = if (transcript.isFromAudio) sem.amber else sem.insightPurple,
                    containerColor = if (transcript.isFromAudio) sem.amberContainer else sem.insightPurpleContainer
                )
                Text(
                    text = "${formatBytes(transcript.sizeBytes)} • ${formatRelativeTimestamp(transcript.archivedAtMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                SelectionContainer {
                    Text(
                        text = transcript.content.ifBlank { "(Empty archive file)" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp)
                    )
                }
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
            text = "Select a Note or Archive",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Tap any Daily DMN entry, Atomic Node, or Archived Transcript to read, toggle tasks, and traverse [[WikiLinks]].",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

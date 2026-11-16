package com.example.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.drive.DriveFolderItem
import com.example.data.drive.GoogleDriveAuthManager
import com.example.data.local.SyncLogEntity
import com.example.data.prefs.ConflictStrategy
import com.example.data.prefs.VaultSettings
import com.example.data.vault.ObsidianVaultRepository
import com.example.ui.components.StatusPill
import com.example.ui.components.formatRelativeTimestamp
import com.example.ui.theme.LocalSynapseColors
import com.example.ui.viewmodel.GeminiTestState
import com.example.ui.viewmodel.MainUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncScreen(
    state: MainUiState,
    onPickVaultFolder: () -> Unit,
    onSaveGeminiConfig: (apiKey: String, model: String) -> Unit,
    onTestGeminiConnection: (apiKey: String, model: String) -> Unit,
    onAuthorizeDrive: (Activity) -> Unit,
    onRefreshDriveFolders: () -> Unit,
    onSelectDriveFolder: (DriveFolderItem) -> Unit,
    onCreateDriveFolder: (String) -> Unit,
    onDisconnectDrive: () -> Unit,
    onSyncDriveNow: () -> Unit,
    onUpdateAutomation: (autoProcess: Boolean, autoSync: Boolean, wifiOnly: Boolean, strategy: ConflictStrategy) -> Unit,
    onUpdateAudioRetentionDays: (Int) -> Unit,
    onPruneExpiredAudioNow: () -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val sem = LocalSynapseColors.current

    var apiKeyDraft by remember(state.settings.geminiApiKey) {
        mutableStateOf(state.settings.geminiApiKey)
    }
    var modelDraft by remember(state.settings.geminiModel) {
        mutableStateOf(state.settings.geminiModel)
    }
    var retentionDaysDraft by remember(state.settings.audioRetentionDays) {
        mutableStateOf(state.settings.audioRetentionDays.toString())
    }
    var showApiKey by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newDriveFolderName by remember { mutableStateOf("ObsidianVault") }
    val signingDiagnostics = remember(context) {
        GoogleDriveAuthManager.readSigningDiagnostics(context)
    }
    var showOAuthGuide by remember { mutableStateOf(false) }

    LaunchedEffect(state.driveAuthError) {
        if (!state.driveAuthError.isNullOrBlank()) {
            showOAuthGuide = true
        }
    }

    LaunchedEffect(state.settings.driveConnectedEmail) {
        if (!state.settings.driveConnectedEmail.isNullOrBlank() && state.driveFolders.isEmpty()) {
            onRefreshDriveFolders()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Drive Sync & Setup",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Configure ObsidianVault storage, Google AI Studio API, and two-way Google Drive sync",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 1. Local Obsidian Vault (SAF)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = sem.amber)
                            Text("Obsidian Vault (Local SAF)", style = MaterialTheme.typography.titleLarge)
                        }
                        StatusPill(
                            label = if (state.settings.isVaultConfigured) "Linked" else "Unset",
                            color = if (state.settings.isVaultConfigured) sem.emerald else sem.amber,
                            containerColor = if (state.settings.isVaultConfigured) sem.emeraldContainer else sem.amberContainer
                        )
                    }

                    Text(
                        text = if (state.settings.isVaultConfigured) {
                            "Folder: ${state.settings.vaultDisplayName}\nNodes indexed: ${state.graphData.nodes.size} (${state.graphData.totalDailyNotes} daily, ${state.graphData.totalAtomicNodes} atomic)"
                        } else {
                            "Select your root ObsidianVault folder so Synapse DMN can read 00_Audio_Inbox/ and write linked Markdown back to 01_Daily_Notes/."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onPickVaultFolder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pick_vault_folder_button")
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (state.settings.isVaultConfigured) "Change ObsidianVault Folder" else "Select ObsidianVault Folder")
                    }
                }
            }
        }

        // 2. Google AI Studio API Key & Model
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Google AI Studio API", style = MaterialTheme.typography.titleLarge)
                        }
                        StatusPill(
                            label = if (state.settings.isGeminiConfigured) "Key Saved" else "Key Required",
                            color = if (state.settings.isGeminiConfigured) sem.emerald else sem.amber,
                            containerColor = if (state.settings.isGeminiConfigured) sem.emeraldContainer else sem.amberContainer
                        )
                    }

                    OutlinedTextField(
                        value = apiKeyDraft,
                        onValueChange = { apiKeyDraft = it },
                        label = { Text("Google AI Studio API Key") },
                        placeholder = { Text("AIza...") },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { showApiKey = !showApiKey },
                                modifier = Modifier.testTag("toggle_api_key_visibility")
                            ) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showApiKey) "Hide key" else "Show key"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_api_key_input")
                    )

                    OutlinedTextField(
                        value = modelDraft,
                        onValueChange = { modelDraft = it },
                        label = { Text("Gemini Model ID") },
                        singleLine = true,
                        trailingIcon = {
                            if (modelDraft != VaultSettings.DEFAULT_GEMINI_MODEL) {
                                TextButton(onClick = { modelDraft = VaultSettings.DEFAULT_GEMINI_MODEL }) {
                                    Text("Reset")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_model_input")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modelPresets = listOf(
                            "gemini-flash-latest",
                            "gemini-2.5-flash",
                            "gemini-2.5-flash-lite",
                            "gemini-2.5-pro"
                        )
                        modelPresets.forEach { preset ->
                            FilterChip(
                                selected = modelDraft.trim() == preset,
                                onClick = { modelDraft = preset },
                                label = { Text(preset) },
                                modifier = Modifier.testTag("model_preset_$preset")
                            )
                        }
                    }

                    when (val test = state.geminiTestState) {
                        is GeminiTestState.Testing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        is GeminiTestState.Success -> {
                            Text(
                                text = "✓ ${test.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = sem.emerald
                            )
                        }
                        is GeminiTestState.Error -> {
                            Text(
                                text = test.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        GeminiTestState.Idle -> Unit
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSaveGeminiConfig(apiKeyDraft, modelDraft) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_gemini_key_button")
                        ) {
                            Text("Save")
                        }
                        Button(
                            onClick = { onTestGeminiConnection(apiKeyDraft, modelDraft) },
                            enabled = apiKeyDraft.isNotBlank() && state.geminiTestState !is GeminiTestState.Testing,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("test_gemini_key_button")
                        ) {
                            Text("Test & Save")
                        }
                    }
                }
            }
        }

        // 3. Google Drive Account & Two-Way Sync
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = sem.dailyBlue)
                            Text("Google Drive Sync", style = MaterialTheme.typography.titleLarge)
                        }
                        StatusPill(
                            label = if (state.settings.driveConnectedEmail != null) "Signed In" else "Signed Out",
                            color = if (state.settings.driveConnectedEmail != null) sem.emerald else sem.ghostNode,
                            containerColor = if (state.settings.driveConnectedEmail != null) sem.emeraldContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    }

                    if (state.settings.driveConnectedEmail == null) {
                        Text(
                            text = "Sign in with Google to grant Drive folder access and run two-way sync across your Obsidian notes and archived audio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { activity?.let(onAuthorizeDrive) },
                            enabled = activity != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_drive_button")
                        ) {
                            Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign in & Grant Google Drive Access")
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.settings.driveConnectedEmail,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Last sync: ${formatRelativeTimestamp(state.settings.lastDriveSyncMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = onDisconnectDrive,
                                modifier = Modifier.testTag("disconnect_drive_button")
                            ) {
                                Text("Disconnect")
                            }
                        }

                        // Drive Folder Selection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Drive Target Folder",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Row {
                                IconButton(
                                    onClick = onRefreshDriveFolders,
                                    modifier = Modifier.testTag("refresh_drive_folders_button")
                                ) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh Drive folders")
                                }
                                IconButton(
                                    onClick = { showCreateFolderDialog = true },
                                    modifier = Modifier.testTag("create_drive_folder_button")
                                ) {
                                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Create Drive folder")
                                }
                            }
                        }

                        if (state.isLoadingDriveFolders) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else if (state.driveFolders.isEmpty()) {
                            Text(
                                text = "No folders loaded yet. Tap Refresh or Create Folder to set up \"ObsidianVault\" in Google Drive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                state.driveFolders.take(8).forEach { folder ->
                                    val selected = folder.id == state.settings.driveFolderId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable { onSelectDriveFolder(folder) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selected,
                                            onClick = { onSelectDriveFolder(folder) }
                                        )
                                        Icon(
                                            imageVector = Icons.Outlined.Folder,
                                            contentDescription = null,
                                            tint = if (selected) sem.dailyBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = folder.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        if (state.isSyncingDrive) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            state.liveStatusMessage?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = sem.dailyBlue
                                )
                            }
                        }

                        Button(
                            onClick = onSyncDriveNow,
                            enabled = !state.isSyncingDrive && state.settings.isVaultConfigured && state.settings.isDriveConfigured,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sync_drive_now_button")
                        ) {
                            Icon(Icons.Outlined.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Run Two-Way Drive Sync Now")
                        }
                    }

                    if (!state.driveAuthError.isNullOrBlank()) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = state.driveAuthError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (state.settings.driveConnectedEmail == null || !state.driveAuthError.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { showOAuthGuide = !showOAuthGuide }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Google Cloud OAuth Setup (Package & SHA-1)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (showOAuthGuide) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = if (showOAuthGuide) "Hide OAuth setup" else "Show OAuth setup",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(visible = showOAuthGuide) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Google Drive OAuth requires an Android OAuth 2.0 Client ID matching this installed APK:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    CopyableCredentialRow(
                                        label = "Package Name",
                                        value = signingDiagnostics.packageName,
                                        testTag = "copy_package_name_button",
                                        onCopy = { copyToClipboard(context, "Package Name", signingDiagnostics.packageName) }
                                    )

                                    CopyableCredentialRow(
                                        label = "Installed APK SHA-1",
                                        value = signingDiagnostics.sha1Fingerprint ?: "Unavailable",
                                        testTag = "copy_sha1_button",
                                        copyEnabled = signingDiagnostics.sha1Fingerprint != null,
                                        onCopy = {
                                            signingDiagnostics.sha1Fingerprint?.let { sha1 ->
                                                copyToClipboard(context, "APK SHA-1", sha1)
                                            }
                                        }
                                    )

                                    Text(
                                        text = "1. Open Google Cloud Console → APIs & Services → Library and enable Google Drive API.\n" +
                                            "2. Configure OAuth consent screen (External) and add your Gmail under Test users.\n" +
                                            "3. Go to Credentials → Create client → Android and paste the Package Name & SHA-1 above.\n" +
                                            "4. Wait ~2–5 min for propagation, then tap Sign in & Grant Google Drive Access.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Automation & Appearance
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Automation & Sync Rules", style = MaterialTheme.typography.titleLarge)

                    ToggleRow(
                        title = "Auto-Process Inbox Queues",
                        subtitle = "Automatically send queued audio & text inputs to Gemini when online",
                        checked = state.settings.autoProcessAudio,
                        testTag = "auto_process_switch",
                        onCheckedChange = {
                            onUpdateAutomation(
                                it,
                                state.settings.autoDriveSync,
                                state.settings.syncWifiOnly,
                                state.settings.conflictStrategy
                            )
                        }
                    )

                    ToggleRow(
                        title = "Auto Two-Way Drive Sync",
                        subtitle = "Sync after DMN synthesis and every 2 hours in background",
                        checked = state.settings.autoDriveSync,
                        testTag = "auto_drive_sync_switch",
                        onCheckedChange = {
                            onUpdateAutomation(
                                state.settings.autoProcessAudio,
                                it,
                                state.settings.syncWifiOnly,
                                state.settings.conflictStrategy
                            )
                        }
                    )

                    ToggleRow(
                        title = "Drive Sync on Wi-Fi Only",
                        subtitle = "Avoid cellular data during large audio archive syncs",
                        checked = state.settings.syncWifiOnly,
                        testTag = "wifi_only_sync_switch",
                        onCheckedChange = {
                            onUpdateAutomation(
                                state.settings.autoProcessAudio,
                                state.settings.autoDriveSync,
                                it,
                                state.settings.conflictStrategy
                            )
                        }
                    )

                    ToggleRow(
                        title = "Midnight Neural Dark Theme",
                        subtitle = "Obsidian dark workspace vs archival paper light theme",
                        checked = state.settings.darkTheme,
                        testTag = "dark_theme_switch",
                        onCheckedChange = onToggleDarkTheme
                    )

                    Text("Conflict Resolution", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConflictStrategy.entries.forEach { strategy ->
                            FilterChip(
                                selected = state.settings.conflictStrategy == strategy,
                                onClick = {
                                    onUpdateAutomation(
                                        state.settings.autoProcessAudio,
                                        state.settings.autoDriveSync,
                                        state.settings.syncWifiOnly,
                                        strategy
                                    )
                                },
                                label = { Text(strategy.label) },
                                modifier = Modifier.testTag("conflict_${strategy.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        // 5. Archive & Audio Retention
        item {
            val activeRetentionDays = state.settings.audioRetentionDays
            val cutoffMs = ObsidianVaultRepository.computeRetentionCutoffMs(activeRetentionDays)
            val cutoffFormatted = remember(cutoffMs) {
                cutoffMs?.let { SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(it)) }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = sem.amber)
                            Text("Audio Retention", style = MaterialTheme.typography.titleLarge)
                        }
                        StatusPill(
                            label = if (activeRetentionDays == 0) "Forever" else "${activeRetentionDays}d",
                            color = if (activeRetentionDays == 0) sem.emerald else sem.amber,
                            containerColor = if (activeRetentionDays == 0) sem.emeraldContainer else sem.amberContainer
                        )
                    }

                    Text(
                        text = "Automatically prune processed recordings in 99_Audio_Archive/ older than your retention window. Exact transcripts in 98_Transcript_Archive/ are always preserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val presets = listOf(0 to "Forever", 7 to "7d", 30 to "30d", 90 to "90d")
                        presets.forEach { (days, label) ->
                            FilterChip(
                                selected = activeRetentionDays == days,
                                onClick = {
                                    retentionDaysDraft = days.toString()
                                    onUpdateAudioRetentionDays(days)
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("retention_chip_$days")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = retentionDaysDraft,
                            onValueChange = { input ->
                                retentionDaysDraft = input.filter { it.isDigit() }.take(4)
                            },
                            label = { Text("Custom retention days (0 = Forever)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("custom_retention_days_input")
                        )
                        OutlinedButton(
                            onClick = {
                                val parsed = retentionDaysDraft.toIntOrNull() ?: 0
                                onUpdateAudioRetentionDays(parsed)
                            },
                            modifier = Modifier.testTag("apply_retention_days_button")
                        ) {
                            Text("Apply")
                        }
                    }

                    val summaryText = if (cutoffFormatted != null) {
                        "Cutoff: recordings archived before $cutoffFormatted are eligible for cleanup. (${state.archivedAudioFiles.size} audio, ${state.archivedTranscripts.size} transcript(s) stored)"
                    } else {
                        "Keeping all archived audio indefinitely. (${state.archivedAudioFiles.size} audio, ${state.archivedTranscripts.size} transcript(s) stored)"
                    }
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (activeRetentionDays > 0) {
                        OutlinedButton(
                            onClick = onPruneExpiredAudioNow,
                            enabled = state.settings.isVaultConfigured,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("prune_expired_audio_button")
                        ) {
                            Text("Clean Up Expired Audio Now")
                        }
                    }
                }
            }
        }

        // 6. Activity Log
        if (state.logs.isNotEmpty()) {
            item {
                Text(
                    text = "Activity & Sync Log",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            items(state.logs.take(20), key = { it.id }) { log ->
                SyncLogRow(log = log)
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Google Drive Folder") },
            text = {
                OutlinedTextField(
                    value = newDriveFolderName,
                    onValueChange = { newDriveFolderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.testTag("new_drive_folder_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCreateFolderDialog = false
                        onCreateDriveFolder(newDriveFolderName)
                    },
                    modifier = Modifier.testTag("confirm_create_drive_folder_button")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SyncLogRow(log: SyncLogEntity) {
    val sem = LocalSynapseColors.current
    val badgeColor = when (log.level) {
        "SUCCESS" -> sem.emerald
        "ERROR" -> sem.recordingCrimson
        else -> sem.dailyBlue
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(badgeColor)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "[${log.category}] ${formatRelativeTimestamp(log.timestampMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CopyableCredentialRow(
    label: String,
    value: String,
    testTag: String,
    copyEnabled: Boolean = true,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onCopy,
            enabled = copyEnabled,
            modifier = Modifier.testTag(testTag)
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy $label",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

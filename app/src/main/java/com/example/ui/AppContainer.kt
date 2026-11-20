package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.GraphScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.screens.SyncScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.theme.SynapseDmnTheme
import com.example.ui.viewmodel.SynapseViewModel

enum class AppDestination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    STUDIO("Studio", Icons.Filled.Mic, Icons.Outlined.Mic),
    GRAPH("Graph", Icons.Filled.Hub, Icons.Outlined.Hub),
    VAULT("Vault", Icons.Filled.Description, Icons.Outlined.Description),
    SYNC("Sync", Icons.Filled.CloudSync, Icons.Outlined.CloudSync)
}

@Composable
fun SynapseAppRoot(viewModel: SynapseViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var activeTab by rememberSaveable { mutableStateOf(AppDestination.STUDIO) }
    val snackbarHostState = remember { SnackbarHostState() }

    val vaultPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.onVaultFolderSelected(uri)
        }
    }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        viewModel.handleDriveConsentResult(activityResult.data)
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.driveConsentRequests.collect { intentSenderRequest ->
            driveConsentLauncher.launch(intentSenderRequest)
        }
    }

    SynapseDmnTheme(darkTheme = state.settings.darkTheme) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 680.dp

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            AppDestination.entries.forEach { dest ->
                                val selected = activeTab == dest
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { activeTab = dest },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label) },
                                    modifier = Modifier.testTag("nav_tab_${dest.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            AppDestination.entries.forEach { dest ->
                                val selected = activeTab == dest
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { activeTab = dest },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label) },
                                    modifier = Modifier.testTag("nav_rail_${dest.name.lowercase()}")
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        val contentModifier = if (activeTab == AppDestination.GRAPH || activeTab == AppDestination.VAULT) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .widthIn(max = 760.dp)
                                .fillMaxSize()
                        }

                        when (activeTab) {
                            AppDestination.STUDIO -> {
                                StudioScreen(
                                    state = state,
                                    onPickVault = { vaultPickerLauncher.launch(null) },
                                    onOpenSyncTab = { activeTab = AppDestination.SYNC },
                                    onStartRecording = viewModel::startRecording,
                                    onPauseRecording = viewModel::pauseRecording,
                                    onResumeRecording = viewModel::resumeRecording,
                                    onStopRecordingForReview = viewModel::stopRecordingForReview,
                                    onRetryLiveTranscription = viewModel::retryLiveTranscription,
                                    onUpdateDraftTitle = viewModel::updateActiveDraftTitle,
                                    onUpdateDraftTranscript = viewModel::updateActiveDraftTranscript,
                                    onSelectDraft = viewModel::selectVoiceDraft,
                                    onNewDraft = viewModel::startNewVoiceDraft,
                                    onDeleteDraft = viewModel::deleteVoiceDraft,
                                    onTranscribePendingAudio = viewModel::transcribePendingDraftAudio,
                                    onConfirmSendDraftToDmn = viewModel::confirmAndSendDraftToDmn,
                                    onSubmitTextNote = viewModel::submitTextToQueue,
                                    onProcessAllNow = viewModel::processAllInboxNow,
                                    onProcessSingle = viewModel::processSingleInboxItemNow,
                                    onSwitchRetryModel = viewModel::switchModelForNextRetry,
                                    onRetryNow = viewModel::triggerImmediateRetryNow,
                                    onDeleteInboxAudio = viewModel::deleteInboxAudio,
                                    onDeleteInboxText = viewModel::deleteInboxText,
                                    onRefreshInbox = viewModel::refreshAll,
                                    onOpenNote = { notePath ->
                                        viewModel.openNoteByPath(notePath)
                                        activeTab = AppDestination.VAULT
                                    },
                                    onDeleteSession = viewModel::deleteSessionForJob,
                                    onClearCompletedJobs = viewModel::clearCompletedJobs,
                                    modifier = contentModifier
                                )
                            }

                            AppDestination.GRAPH -> {
                                GraphScreen(
                                    state = state,
                                    onOpenNoteByPath = { notePath ->
                                        viewModel.openNoteByPath(notePath)
                                        activeTab = AppDestination.VAULT
                                    },
                                    onCreateHubNote = { title ->
                                        viewModel.createAtomicNodeAndOpen(title)
                                        activeTab = AppDestination.VAULT
                                    },
                                    modifier = contentModifier
                                )
                            }

                            AppDestination.VAULT -> {
                                VaultScreen(
                                    state = state,
                                    onSelectNote = viewModel::openNoteByPath,
                                    onSelectTranscript = viewModel::openArchivedTranscript,
                                    onOpenTranscriptByName = viewModel::openArchivedTranscriptByName,
                                    onOpenNoteByTitle = viewModel::openNoteByTitle,
                                    onCreateAtomicNote = { title ->
                                        viewModel.createAtomicNodeAndOpen(title)
                                    },
                                    onToggleTask = viewModel::toggleTaskInNote,
                                    onTogglePlayAudio = viewModel::togglePlayArchivedAudio,
                                    onDeleteSessionFromNote = viewModel::deleteSessionFromDailyNote,
                                    onDeleteArchiveSession = { transcriptName, audioName ->
                                        viewModel.deleteSessionForArchive(transcriptName, audioName)
                                    },
                                    modifier = contentModifier
                                )
                            }

                            AppDestination.SYNC -> {
                                SyncScreen(
                                    state = state,
                                    onPickVaultFolder = { vaultPickerLauncher.launch(null) },
                                    onSaveGeminiConfig = viewModel::saveGeminiConfig,
                                    onTestGeminiConnection = viewModel::testGeminiConnection,
                                    onFetchAvailableModels = viewModel::fetchAvailableGeminiModels,
                                    onAuthorizeDrive = viewModel::authorizeGoogleDrive,
                                    onRefreshDriveFolders = viewModel::loadDriveFolders,
                                    onSelectDriveFolder = viewModel::selectDriveFolder,
                                    onCreateDriveFolder = viewModel::createAndSelectDriveFolder,
                                    onDisconnectDrive = viewModel::disconnectGoogleDrive,
                                    onSyncDriveNow = viewModel::syncDriveNow,
                                    onUpdateAutomation = viewModel::updateAutomationSettings,
                                    onUpdateAudioRetentionDays = viewModel::updateAudioRetentionDays,
                                    onPruneExpiredAudioNow = viewModel::pruneExpiredAudioNow,
                                    onToggleDarkTheme = viewModel::toggleDarkTheme,
                                    modifier = contentModifier
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

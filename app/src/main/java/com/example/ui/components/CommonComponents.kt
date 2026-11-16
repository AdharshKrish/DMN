package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.prefs.VaultSettings
import com.example.ui.theme.LocalSynapseColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusPill(
    label: String,
    color: Color,
    containerColor: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SetupChecklistCard(
    settings: VaultSettings,
    onPickVault: () -> Unit,
    onOpenSyncSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sem = LocalSynapseColors.current
    if (settings.isVaultConfigured && settings.isGeminiConfigured && settings.isDriveConfigured) {
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Quick Workspace Setup",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Connect your local ObsidianVault, Google AI Studio key, and Google Drive to unlock end-to-end DMN synthesis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SetupStepRow(
                title = if (settings.isVaultConfigured) "ObsidianVault: ${settings.vaultDisplayName}" else "1. Pick ObsidianVault Folder",
                done = settings.isVaultConfigured,
                icon = Icons.Outlined.FolderOpen,
                actionLabel = if (settings.isVaultConfigured) "Change" else "Select Folder",
                testTag = "setup_pick_vault_button",
                onClick = onPickVault
            )
            SetupStepRow(
                title = if (settings.isGeminiConfigured) "Gemini Key (${settings.geminiModel})" else "2. Enter Google AI Studio API Key",
                done = settings.isGeminiConfigured,
                icon = Icons.Outlined.Key,
                actionLabel = if (settings.isGeminiConfigured) "Edit" else "Configure",
                testTag = "setup_gemini_key_button",
                onClick = onOpenSyncSetup
            )
            SetupStepRow(
                title = if (settings.isDriveConfigured) "Drive: ${settings.driveFolderName}" else "3. Sign in & Pick Drive Folder",
                done = settings.isDriveConfigured,
                icon = Icons.Outlined.CloudOff,
                actionLabel = if (settings.isDriveConfigured) "Manage" else "Connect Drive",
                testTag = "setup_drive_button",
                onClick = onOpenSyncSetup
            )
        }
    }
}

@Composable
private fun SetupStepRow(
    title: String,
    done: Boolean,
    icon: ImageVector,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    val sem = LocalSynapseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (done) Icons.Outlined.CheckCircle else icon,
            contentDescription = null,
            tint = if (done) sem.emerald else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!done) {
            Button(
                onClick = onClick,
                modifier = Modifier.testTag(testTag)
            ) {
                Text(actionLabel)
            }
        } else {
            TextButton(
                onClick = onClick,
                modifier = Modifier.testTag(testTag)
            ) {
                Text(actionLabel)
            }
        }
    }
}

fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    }
}

fun formatRelativeTimestamp(ms: Long): String {
    if (ms <= 0L) return "Never"
    return SimpleDateFormat("MMM d, hh:mm a", Locale.US).format(Date(ms))
}

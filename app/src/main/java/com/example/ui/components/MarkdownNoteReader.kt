package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.audio.AudioPlaybackState
import com.example.data.vault.VaultNote
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalSynapseColors

private val WIKILINK_INLINE_REGEX = Regex("""\[\[([^\[\]|#]+)(?:#([^\[\]|]+))?(?:\|([^\[\]]+))?]]""")
private val AUDIO_EMBED_LINE_REGEX = Regex("""!\[\[(?:99_Audio_Archive/)?([^\]|]+\.(?:m4a|mp3|wav|webm|ogg|aac))]]""", RegexOption.IGNORE_CASE)
private val BOLD_INLINE_REGEX = Regex("""\*\*(.+?)\*\*""")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkdownNoteReader(
    note: VaultNote,
    playbackState: AudioPlaybackState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onWikiLinkClick: (String) -> Unit,
    onToggleTask: (lineIndex: Int) -> Unit,
    onTogglePlayAudio: (audioName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sem = LocalSynapseColors.current
    val lines = remember(note.content) { note.content.lines() }
    val bodyStartIdx = remember(lines) {
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) end + 2 else 0
        } else {
            0
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
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
                        modifier = Modifier.testTag("note_reader_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to vault list"
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = note.relativePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (note.tags.isNotEmpty()) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        note.tags.forEach { tag ->
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelMedium,
                                color = sem.insightPurple,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(sem.insightPurpleContainer.copy(alpha = 0.7f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            itemsIndexed(lines.drop(bodyStartIdx)) { offsetIndex, rawLine ->
                val actualLineIdx = bodyStartIdx + offsetIndex
                val trimmed = rawLine.trim()

                val audioMatch = AUDIO_EMBED_LINE_REGEX.find(trimmed)
                when {
                    audioMatch != null -> {
                        val audioName = audioMatch.groupValues[1].trim()
                        EmbeddedAudioPlayerCard(
                            audioName = audioName,
                            playbackState = playbackState,
                            onTogglePlay = { onTogglePlayAudio(audioName) }
                        )
                    }

                    trimmed == "---" -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }

                    trimmed.startsWith("# ") -> {
                        RichMarkdownText(
                            text = trimmed.removePrefix("# "),
                            baseStyle = MaterialTheme.typography.headlineMedium,
                            baseColor = MaterialTheme.colorScheme.onBackground,
                            onWikiLinkClick = onWikiLinkClick
                        )
                    }

                    trimmed.startsWith("## ") -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        RichMarkdownText(
                            text = trimmed.removePrefix("## "),
                            baseStyle = MaterialTheme.typography.headlineSmall,
                            baseColor = MaterialTheme.colorScheme.primary,
                            onWikiLinkClick = onWikiLinkClick
                        )
                    }

                    trimmed.startsWith("### ") -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        RichMarkdownText(
                            text = trimmed.removePrefix("### "),
                            baseStyle = MaterialTheme.typography.titleLarge,
                            baseColor = sem.amber,
                            onWikiLinkClick = onWikiLinkClick
                        )
                    }

                    trimmed.startsWith("- [ ] ") || trimmed.startsWith("* [ ] ") -> {
                        val taskText = trimmed.drop(6)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onToggleTask(actualLineIdx) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = false,
                                onCheckedChange = { onToggleTask(actualLineIdx) },
                                colors = CheckboxDefaults.colors(checkedColor = sem.emerald)
                            )
                            RichMarkdownText(
                                text = taskText,
                                baseStyle = MaterialTheme.typography.bodyMedium,
                                baseColor = MaterialTheme.colorScheme.onSurface,
                                onWikiLinkClick = onWikiLinkClick,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    trimmed.startsWith("- [x] ", ignoreCase = true) || trimmed.startsWith("* [x] ", ignoreCase = true) -> {
                        val taskText = trimmed.drop(6)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onToggleTask(actualLineIdx) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = true,
                                onCheckedChange = { onToggleTask(actualLineIdx) },
                                colors = CheckboxDefaults.colors(checkedColor = sem.emerald)
                            )
                            RichMarkdownText(
                                text = taskText,
                                baseStyle = MaterialTheme.typography.bodyMedium.copy(
                                    textDecoration = TextDecoration.LineThrough
                                ),
                                baseColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                onWikiLinkClick = onWikiLinkClick,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            RichMarkdownText(
                                text = trimmed.drop(2),
                                baseStyle = MaterialTheme.typography.bodyMedium,
                                baseColor = MaterialTheme.colorScheme.onSurface,
                                onWikiLinkClick = onWikiLinkClick,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    trimmed.startsWith("> ") -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                                .padding(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(22.dp)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            RichMarkdownText(
                                text = trimmed.removePrefix("> "),
                                baseStyle = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                baseColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                onWikiLinkClick = onWikiLinkClick,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    trimmed.isNotEmpty() -> {
                        RichMarkdownText(
                            text = trimmed,
                            baseStyle = MaterialTheme.typography.bodyLarge,
                            baseColor = MaterialTheme.colorScheme.onSurface,
                            onWikiLinkClick = onWikiLinkClick
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RichMarkdownText(
    text: String,
    baseStyle: androidx.compose.ui.text.TextStyle,
    baseColor: Color,
    onWikiLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sem = LocalSynapseColors.current
    val linkColor = MaterialTheme.colorScheme.primary

    val annotated = remember(text, linkColor, sem.insightPurple) {
        buildAnnotatedString {
            var cursor = 0
            val matches = WIKILINK_INLINE_REGEX.findAll(text).toList()
            for (match in matches) {
                if (match.range.first > cursor) {
                    appendBoldFormatted(text.substring(cursor, match.range.first))
                }
                val targetTitle = match.groupValues[1].trim()
                val alias = match.groupValues[3].trim()
                val display = if (alias.isNotEmpty()) alias else targetTitle
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "wikilink_$targetTitle",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                textDecoration = TextDecoration.Underline
                            )
                        ),
                        linkInteractionListener = { onWikiLinkClick(targetTitle) }
                    )
                ) {
                    append("[[$display]]")
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) {
                appendBoldFormatted(text.substring(cursor))
            }
        }
    }

    Text(
        text = annotated,
        style = baseStyle.copy(color = baseColor),
        modifier = modifier
    )
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendBoldFormatted(segment: String) {
    var cursor = 0
    for (match in BOLD_INLINE_REGEX.findAll(segment)) {
        if (match.range.first > cursor) {
            append(segment.substring(cursor, match.range.first))
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(match.groupValues[1])
        }
        cursor = match.range.last + 1
    }
    if (cursor < segment.length) {
        append(segment.substring(cursor))
    }
}

@Composable
private fun EmbeddedAudioPlayerCard(
    audioName: String,
    playbackState: AudioPlaybackState,
    onTogglePlay: () -> Unit
) {
    val sem = LocalSynapseColors.current
    val isThisActive = playbackState.activeAudioName == audioName
    val isPlaying = isThisActive && playbackState.isPlaying
    val progress = if (isThisActive && playbackState.durationMs > 0) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, sem.amber.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(sem.amberContainer)
                        .testTag("play_archived_audio_button")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause archived audio" else "Play archived audio",
                        tint = sem.amber
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AudioFile,
                            contentDescription = null,
                            tint = sem.amber,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Original Voice Recording",
                            style = MaterialTheme.typography.labelMedium,
                            color = sem.amber
                        )
                    }
                    Text(
                        text = audioName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isThisActive && playbackState.durationMs > 0) {
                    Text(
                        text = "${formatDurationMs(playbackState.positionMs.toLong())} / ${formatDurationMs(playbackState.durationMs.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isThisActive) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = sem.amber
                )
            }
        }
    }
}

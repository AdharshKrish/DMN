package com.example.data.vault

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ObsidianVaultRepository(private val context: Context) {

    companion object {
        const val DIR_AUDIO_INBOX = "00_Audio_Inbox"
        const val DIR_DAILY_NOTES = "01_Daily_Notes"
        const val DIR_ATOMIC_NODES = "02_Atomic_Nodes"
        const val DIR_TEMPLATES = "03_Templates"
        const val DIR_TEXT_INBOX = "04_Text_Inbox"
        const val DIR_TRANSCRIPT_ARCHIVE = "98_Transcript_Archive"
        const val DIR_AUDIO_ARCHIVE = "99_Audio_Archive"

        private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav", "webm", "ogg", "aac")
        private val TEXT_EXTENSIONS = setOf("txt", "md")
        private val SKIP_GRAPH_DIRS = setOf(
            DIR_AUDIO_INBOX,
            DIR_TEXT_INBOX,
            DIR_TEMPLATES,
            DIR_TRANSCRIPT_ARCHIVE,
            DIR_AUDIO_ARCHIVE
        )
        private val DAILY_DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private val WIKILINK_REGEX = Regex("""(?<!!)\[\[([^\[\]|#]+)(?:#[^\[\]|]+)?(?:\|[^\[\]]+)?]]""")
        private val EMBED_AUDIO_REGEX = Regex("""!\[\[(?:99_Audio_Archive/)?([^\]|]+\.(?:m4a|mp3|wav|webm|ogg|aac))]]""", RegexOption.IGNORE_CASE)
        private val EMBED_TRANSCRIPT_REGEX = Regex("""!\[\[(?:98_Transcript_Archive/)?([^\]|]+\.(?:txt|md))]]""", RegexOption.IGNORE_CASE)
        private val ARCHIVE_STAMP_REGEX = Regex("""^(\d{8}_\d{6})_.*""")
        private val INLINE_TAG_REGEX = Regex("""(?<!\S)#([A-Za-z0-9_\-/]+)""")
        private val TASK_OPEN_REGEX = Regex("""^\s*[-*]\s+\[\s]\s+.*""")
        private val TASK_DONE_REGEX = Regex("""^\s*[-*]\s+\[[xX]]\s+.*""")

        fun computeRetentionCutoffMs(retentionDays: Int, nowMs: Long = System.currentTimeMillis()): Long? {
            if (retentionDays <= 0) return null
            return nowMs - retentionDays.toLong() * 86_400_000L
        }

        fun parseArchiveTimestampMs(fileName: String): Long? {
            val match = ARCHIVE_STAMP_REGEX.matchEntire(fileName) ?: return null
            val stamp = match.groupValues[1]
            return runCatching {
                val parser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
                    isLenient = false
                }
                parser.parse(stamp)?.time
            }.getOrNull()
        }

        fun isDmnSessionHeading(line: String): Boolean {
            val t = line.trim()
            return t.startsWith("## ") && t.contains("DMN", ignoreCase = true)
        }

        data class SessionRemovalResult(
            val removed: Boolean,
            val remainingMarkdown: String?, // null means daily note has no sessions left and can be deleted
            val referencedAudioNames: List<String>,
            val referencedTranscriptNames: List<String>,
            val matchedHeading: String?
        )

        fun removeDmnSessionFromMarkdown(
            content: String,
            sessionHeading: String? = null,
            matchFileName: String? = null
        ): SessionRemovalResult {
            val lines = content.lines()
            var bodyStartIdx = 0
            if (lines.firstOrNull()?.trim() == "---") {
                val fmEnd = lines.drop(1).indexOfFirst { it.trim() == "---" }
                if (fmEnd >= 0) {
                    bodyStartIdx = fmEnd + 2
                }
            }

            // Include top H1 date heading (# YYYY-MM-DD or # 📅 YYYY-MM-DD) in headerPrefix if before first ---
            var dividerSearchStart = bodyStartIdx
            while (dividerSearchStart < lines.size && lines[dividerSearchStart].trim() != "---") {
                dividerSearchStart++
            }
            val headerPrefix = lines.take(dividerSearchStart).joinToString("\n").trimEnd()
            val afterHeader = lines.drop(dividerSearchStart)

            // Split into session blocks separated by standalone --- lines
            val blocks = mutableListOf<String>()
            val current = mutableListOf<String>()
            for (line in afterHeader) {
                if (line.trim() == "---") {
                    val joined = current.joinToString("\n").trim()
                    if (joined.isNotEmpty()) blocks.add(joined)
                    current.clear()
                } else {
                    current.add(line)
                }
            }
            val lastJoined = current.joinToString("\n").trim()
            if (lastJoined.isNotEmpty()) blocks.add(lastJoined)

            if (blocks.isEmpty()) {
                return SessionRemovalResult(
                    removed = false,
                    remainingMarkdown = content,
                    referencedAudioNames = emptyList(),
                    referencedTranscriptNames = emptyList(),
                    matchedHeading = null
                )
            }

            val cleanHeading = sessionHeading?.trim()?.takeIf { it.isNotBlank() }
            val cleanFile = matchFileName?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }
            val cleanStem = cleanFile?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }

            val targetIdx = blocks.indexOfFirst { block ->
                val firstHeading = block.lines().firstOrNull { it.trim().startsWith("## ") }?.trim()
                val headingHit = cleanHeading != null && firstHeading != null &&
                    firstHeading.equals(cleanHeading, ignoreCase = true)
                val fileHit = cleanFile != null && block.contains(cleanFile, ignoreCase = true)
                val stemHit = cleanStem != null && cleanStem.length >= 6 &&
                    (EMBED_AUDIO_REGEX.findAll(block).any { it.groupValues[1].substringBeforeLast('.').equals(cleanStem, ignoreCase = true) } ||
                        EMBED_TRANSCRIPT_REGEX.findAll(block).any { it.groupValues[1].substringBeforeLast('.').equals(cleanStem, ignoreCase = true) })
                headingHit || fileHit || stemHit
            }

            if (targetIdx < 0) {
                return SessionRemovalResult(
                    removed = false,
                    remainingMarkdown = content,
                    referencedAudioNames = emptyList(),
                    referencedTranscriptNames = emptyList(),
                    matchedHeading = null
                )
            }

            val removedBlock = blocks[targetIdx]
            val audioNames = EMBED_AUDIO_REGEX.findAll(removedBlock)
                .map { it.groupValues[1].trim() }
                .distinct()
                .toList()
            val transcriptNames = EMBED_TRANSCRIPT_REGEX.findAll(removedBlock)
                .map { it.groupValues[1].trim() }
                .distinct()
                .toList()
            val matchedHeading = removedBlock.lines().firstOrNull { it.trim().startsWith("## ") }?.trim()

            val remaining = blocks.toMutableList().apply { removeAt(targetIdx) }
            val remainingMd = if (remaining.isEmpty()) {
                null
            } else {
                buildString {
                    if (headerPrefix.isNotBlank()) {
                        append(headerPrefix)
                        append("\n\n---\n")
                    }
                    append(remaining.joinToString("\n\n---\n"))
                    append("\n")
                }
            }
            return SessionRemovalResult(
                removed = true,
                remainingMarkdown = remainingMd,
                referencedAudioNames = audioNames,
                referencedTranscriptNames = transcriptNames,
                matchedHeading = matchedHeading
            )
        }
    }

    fun getRootDocument(treeUriString: String?): DocumentFile? {
        if (treeUriString.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() && it.isDirectory }
    }

    suspend fun ensureVaultStructure(treeUriString: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Cannot access selected ObsidianVault folder. Please re-select it.")
            listOf(
                DIR_AUDIO_INBOX,
                DIR_DAILY_NOTES,
                DIR_ATOMIC_NODES,
                DIR_TEMPLATES,
                DIR_TEXT_INBOX,
                DIR_TRANSCRIPT_ARCHIVE,
                DIR_AUDIO_ARCHIVE
            ).forEach { dirName ->
                findOrCreateDir(root, dirName)
            }
            val atomicDir = findOrCreateDir(root, DIR_ATOMIC_NODES)
            listOf("Concepts", "Projects", "People").forEach { sub ->
                findOrCreateDir(atomicDir, sub)
            }
            root.name ?: "ObsidianVault"
        }
    }

    suspend fun ensureRequiredFolders(treeUriString: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder is not accessible.")
            listOf(
                DIR_AUDIO_INBOX,
                DIR_DAILY_NOTES,
                DIR_ATOMIC_NODES,
                DIR_TEMPLATES,
                DIR_TEXT_INBOX,
                DIR_TRANSCRIPT_ARCHIVE,
                DIR_AUDIO_ARCHIVE
            ).forEach { dirName ->
                findOrCreateDir(root, dirName)
            }
            Unit
        }
    }

    suspend fun listInboxAudioFiles(treeUriString: String?): List<InboxAudioFile> = withContext(Dispatchers.IO) {
        val root = getRootDocument(treeUriString) ?: return@withContext emptyList()
        val inbox = root.findFile(DIR_AUDIO_INBOX)?.takeIf { it.isDirectory } ?: return@withContext emptyList()
        inbox.listFiles()
            .filter { doc ->
                val name = doc.name ?: return@filter false
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                doc.isFile &&
                    ext in AUDIO_EXTENSIONS &&
                    !name.startsWith(".syncthing") &&
                    !name.startsWith(".") &&
                    doc.length() > 0L
            }
            .map { doc ->
                val name = doc.name ?: "recording.m4a"
                InboxAudioFile(
                    uri = doc.uri,
                    name = name,
                    sizeBytes = doc.length(),
                    lastModifiedMs = doc.lastModified(),
                    mimeType = guessAudioMimeType(name, doc.type)
                )
            }
            .sortedBy { it.lastModifiedMs }
    }

    suspend fun saveCachedAudioToInbox(
        treeUriString: String,
        tempFile: File,
        desiredFileName: String
    ): Result<InboxAudioFile> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder is not configured.")
            val inbox = findOrCreateDir(root, DIR_AUDIO_INBOX)
            val cleanName = if (desiredFileName.endsWith(".m4a", ignoreCase = true)) {
                desiredFileName
            } else {
                "$desiredFileName.m4a"
            }
            val existing = inbox.findFile(cleanName)
            existing?.delete()
            val doc = inbox.createFile("audio/mp4", cleanName)
                ?: throw IllegalStateException("Failed to create audio file in $DIR_AUDIO_INBOX")
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                tempFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("Cannot write audio stream to $cleanName")
            InboxAudioFile(
                uri = doc.uri,
                name = doc.name ?: cleanName,
                sizeBytes = doc.length().takeIf { it > 0 } ?: tempFile.length(),
                lastModifiedMs = System.currentTimeMillis(),
                mimeType = "audio/mp4"
            )
        }
    }

    suspend fun deleteInboxAudio(audioUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromSingleUri(context, audioUri)
        doc?.delete() ?: false
    }

    suspend fun listInboxTextFiles(treeUriString: String?): List<InboxTextFile> = withContext(Dispatchers.IO) {
        val root = getRootDocument(treeUriString) ?: return@withContext emptyList()
        val inbox = root.findFile(DIR_TEXT_INBOX)?.takeIf { it.isDirectory } ?: return@withContext emptyList()
        inbox.listFiles()
            .filter { doc ->
                val name = doc.name ?: return@filter false
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                doc.isFile &&
                    ext in TEXT_EXTENSIONS &&
                    !name.startsWith(".syncthing") &&
                    !name.startsWith(".") &&
                    doc.length() > 0L
            }
            .map { doc ->
                val name = doc.name ?: "note.txt"
                val preview = readDocumentText(doc.uri)
                    .trim()
                    .replace('\n', ' ')
                    .take(140)
                InboxTextFile(
                    uri = doc.uri,
                    name = name,
                    sizeBytes = doc.length(),
                    lastModifiedMs = doc.lastModified(),
                    previewText = preview
                )
            }
            .sortedBy { it.lastModifiedMs }
    }

    suspend fun saveTextToInbox(
        treeUriString: String,
        rawText: String,
        sessionTitle: String,
        originalExtension: String = "md"
    ): Result<InboxTextFile> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedCheck = rawText.trim()
            require(trimmedCheck.isNotEmpty()) { "Please enter or paste text before saving." }
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder is not configured.")
            val inbox = findOrCreateDir(root, DIR_TEXT_INBOX)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeTitle = sessionTitle
                .trim()
                .replace(Regex("""[^A-Za-z0-9_\- ]"""), "")
                .replace(Regex("""\s+"""), "_")
                .take(40)
                .ifBlank { "Note" }
            val ext = if (originalExtension.equals("txt", ignoreCase = true)) "txt" else "md"
            val desiredName = "${stamp}_${safeTitle}.$ext"
            val uniqueName = resolveUniqueFileName(inbox, desiredName)
            val mime = if (ext == "md") "text/markdown" else "text/plain"
            val doc = inbox.createFile(mime, uniqueName)
                ?: throw IllegalStateException("Failed to create text note in $DIR_TEXT_INBOX")
            // Store exact unmodified user text in UTF-8
            writeDocumentText(doc.uri, rawText)
            val actualName = doc.name ?: uniqueName
            InboxTextFile(
                uri = doc.uri,
                name = actualName,
                sizeBytes = rawText.toByteArray(Charsets.UTF_8).size.toLong(),
                lastModifiedMs = System.currentTimeMillis(),
                previewText = trimmedCheck.replace('\n', ' ').take(140)
            )
        }
    }

    suspend fun readTextByUri(uri: Uri): String = withContext(Dispatchers.IO) {
        readDocumentText(uri)
    }

    suspend fun deleteInboxDocument(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromSingleUri(context, uri)
        doc?.delete() ?: false
    }

    suspend fun scanExistingGraphNodes(treeUriString: String?): List<String> = withContext(Dispatchers.IO) {
        val root = getRootDocument(treeUriString) ?: return@withContext emptyList()
        val nodes = linkedSetOf<String>()
        walkMarkdownFiles(root, "") { relPath, doc ->
            val stem = doc.name?.removeSuffix(".md")?.removeSuffix(".MD") ?: return@walkMarkdownFiles
            if (!DAILY_DATE_REGEX.matches(stem) && !relPath.startsWith(".")) {
                nodes.add(stem)
            }
        }
        nodes.sorted().take(500)
    }

    suspend fun appendDmnEntryToDailyNote(
        treeUriString: String,
        dateStr: String,
        entryMarkdown: String,
        archivedAudioName: String?,
        archivedTranscriptName: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder not accessible")
            val dailyDir = findOrCreateDir(root, DIR_DAILY_NOTES)
            val fileName = "$dateStr.md"
            val existing = dailyDir.findFile(fileName)
            val targetDoc = existing ?: (dailyDir.createFile("text/markdown", fileName)
                ?: throw IllegalStateException("Could not create $fileName"))

            val existingText = if (existing != null) readDocumentText(existing.uri) else ""
            val header = if (existingText.isBlank()) {
                buildString {
                    append("---\n")
                    append("date: $dateStr\n")
                    append("type: daily-dmn\n")
                    append("tags:\n  - daily-note\n  - dmn\n")
                    append("---\n\n")
                    append("# $dateStr\n")
                }
            } else {
                existingText.trimEnd()
            }

            val formattedEntry = buildString {
                append(header)
                append("\n\n---\n")
                append(entryMarkdown.trim())
                if (!archivedAudioName.isNullOrBlank() || !archivedTranscriptName.isNullOrBlank()) {
                    append("\n")
                }
                if (!archivedAudioName.isNullOrBlank()) {
                    append("\n> **Original Audio:** ![[$DIR_AUDIO_ARCHIVE/$archivedAudioName]]")
                }
                if (!archivedTranscriptName.isNullOrBlank()) {
                    append("\n> **Transcript Archive:** ![[$DIR_TRANSCRIPT_ARCHIVE/$archivedTranscriptName]]")
                }
                append("\n")
            }

            writeDocumentText(targetDoc.uri, formattedEntry)
            "$DIR_DAILY_NOTES/$fileName"
        }
    }

    suspend fun archiveProcessedAudio(
        treeUriString: String,
        sourceUri: Uri,
        sourceName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder not accessible")
            val archiveDir = findOrCreateDir(root, DIR_AUDIO_ARCHIVE)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val archivedName = resolveUniqueFileName(archiveDir, "${stamp}_$sourceName")
            val mime = guessAudioMimeType(sourceName, null)
            val destDoc = archiveDir.createFile(mime, archivedName)
                ?: throw IllegalStateException("Could not create archived audio $archivedName")

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(destDoc.uri)?.use { out ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("Could not read source audio for archiving")

            // Remove original inbox file after copy succeeds
            DocumentFile.fromSingleUri(context, sourceUri)?.delete()
            destDoc.name ?: archivedName
        }
    }

    suspend fun archiveRawTranscript(
        treeUriString: String,
        rawText: String,
        preferredFileName: String,
        sourceUriToDelete: Uri? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder not accessible")
            val archiveDir = findOrCreateDir(root, DIR_TRANSCRIPT_ARCHIVE)
            val uniqueName = resolveUniqueFileName(archiveDir, preferredFileName)
            val ext = uniqueName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = if (ext == "md") "text/markdown" else "text/plain"
            val destDoc = archiveDir.createFile(mime, uniqueName)
                ?: throw IllegalStateException("Could not create transcript archive $uniqueName")

            // Preserve exact original format/content without altering whitespace or prose
            writeDocumentText(destDoc.uri, rawText)
            if (sourceUriToDelete != null) {
                DocumentFile.fromSingleUri(context, sourceUriToDelete)?.delete()
            }
            destDoc.name ?: uniqueName
        }
    }

    suspend fun listArchivedTranscripts(treeUriString: String?): List<ArchivedTranscript> = withContext(Dispatchers.IO) {
        val root = getRootDocument(treeUriString) ?: return@withContext emptyList()
        val archiveDir = root.findFile(DIR_TRANSCRIPT_ARCHIVE)?.takeIf { it.isDirectory }
            ?: return@withContext emptyList()

        val audioArchiveStems = root.findFile(DIR_AUDIO_ARCHIVE)
            ?.takeIf { it.isDirectory }
            ?.listFiles()
            ?.mapNotNull { it.name?.substringBeforeLast('.')?.lowercase(Locale.ROOT) }
            ?.toSet()
            ?: emptySet()

        val dailyAudioStems = mutableSetOf<String>()
        root.findFile(DIR_DAILY_NOTES)
            ?.takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.isFile && (it.name?.endsWith(".md", ignoreCase = true) == true) }
            ?.forEach { doc ->
                val text = readDocumentText(doc.uri)
                EMBED_AUDIO_REGEX.findAll(text).forEach { m ->
                    dailyAudioStems.add(m.groupValues[1].trim().substringBeforeLast('.').lowercase(Locale.ROOT))
                }
            }

        archiveDir.listFiles()
            .filter { doc ->
                val name = doc.name ?: return@filter false
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                doc.isFile &&
                    ext in TEXT_EXTENSIONS &&
                    !name.startsWith(".") &&
                    !name.startsWith(".syncthing")
            }
            .map { doc ->
                val name = doc.name ?: "transcript.txt"
                val stem = name.substringBeforeLast('.').lowercase(Locale.ROOT)
                val content = readDocumentText(doc.uri)
                val timestamp = parseArchiveTimestampMs(name) ?: doc.lastModified()
                ArchivedTranscript(
                    relativePath = "$DIR_TRANSCRIPT_ARCHIVE/$name",
                    fileName = name,
                    uri = doc.uri,
                    content = content,
                    sizeBytes = doc.length().takeIf { it > 0L } ?: content.toByteArray(Charsets.UTF_8).size.toLong(),
                    archivedAtMs = timestamp,
                    isFromAudio = stem in audioArchiveStems || stem in dailyAudioStems
                )
            }
            .sortedByDescending { it.archivedAtMs }
    }

    suspend fun readArchivedTranscriptByName(treeUriString: String, fileName: String): ArchivedTranscript? {
        val cleanName = fileName.substringAfterLast('/')
        return listArchivedTranscripts(treeUriString).firstOrNull {
            it.fileName.equals(cleanName, ignoreCase = true)
        }
    }

    suspend fun listArchivedAudioFiles(treeUriString: String?): List<ArchivedAudioItem> = withContext(Dispatchers.IO) {
        val root = getRootDocument(treeUriString) ?: return@withContext emptyList()
        val archiveDir = root.findFile(DIR_AUDIO_ARCHIVE)?.takeIf { it.isDirectory }
            ?: return@withContext emptyList()
        archiveDir.listFiles()
            .filter { doc ->
                val name = doc.name ?: return@filter false
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                doc.isFile &&
                    ext in AUDIO_EXTENSIONS &&
                    !name.startsWith(".") &&
                    !name.startsWith(".syncthing")
            }
            .map { doc ->
                val name = doc.name ?: "recording.m4a"
                val archivedAtMs = parseArchiveTimestampMs(name) ?: doc.lastModified()
                ArchivedAudioItem(
                    relativePath = "$DIR_AUDIO_ARCHIVE/$name",
                    fileName = name,
                    uri = doc.uri,
                    sizeBytes = doc.length(),
                    archivedAtMs = archivedAtMs
                )
            }
            .sortedByDescending { it.archivedAtMs }
    }

    suspend fun pruneExpiredArchivedAudio(
        treeUriString: String,
        retentionDays: Int
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cutoffMs = computeRetentionCutoffMs(retentionDays) ?: return@runCatching 0
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder is not accessible")
            val archiveDir = root.findFile(DIR_AUDIO_ARCHIVE)?.takeIf { it.isDirectory }
                ?: return@runCatching 0

            var deletedCount = 0
            archiveDir.listFiles().forEach { doc ->
                val name = doc.name ?: return@forEach
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (!doc.isFile || ext !in AUDIO_EXTENSIONS || name.startsWith(".")) return@forEach
                val archivedAtMs = parseArchiveTimestampMs(name) ?: doc.lastModified()
                if (archivedAtMs > 0L && archivedAtMs < cutoffMs) {
                    if (doc.delete()) {
                        deletedCount++
                    }
                }
            }
            deletedCount
        }
    }

    data class DeletedSessionArtifacts(
        val deletedPaths: List<String>,
        val audioNamesRemoved: List<String>,
        val transcriptNamesRemoved: List<String>,
        val dailyNoteDeletedCompletely: Boolean
    )

    suspend fun deleteCompleteDmnSession(
        treeUriString: String,
        dailyNotePath: String? = null,
        sessionHeading: String? = null,
        transcriptFileName: String? = null,
        audioFileName: String? = null
    ): Result<DeletedSessionArtifacts> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder is not accessible")

            val deletedPaths = mutableListOf<String>()
            val audioToDelete = linkedSetOf<String>()
            val transcriptsToDelete = linkedSetOf<String>()
            audioFileName?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }?.let { audioToDelete.add(it) }
            transcriptFileName?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }?.let { transcriptsToDelete.add(it) }

            val primaryMatchName = transcriptFileName ?: audioFileName
            var dailyNoteDeletedCompletely = false

            val dailyDocsToInspect = if (!dailyNotePath.isNullOrBlank()) {
                val doc = findFileByRelativePath(treeUriString, dailyNotePath)
                if (doc != null) listOf(dailyNotePath to doc) else emptyList()
            } else if (primaryMatchName != null) {
                root.findFile(DIR_DAILY_NOTES)
                    ?.takeIf { it.isDirectory }
                    ?.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
                    ?.mapNotNull { doc -> doc.name?.let { "$DIR_DAILY_NOTES/$it" to doc } }
                    ?: emptyList()
            } else {
                emptyList()
            }

            for ((relPath, dailyDoc) in dailyDocsToInspect) {
                val text = readDocumentText(dailyDoc.uri)
                val removal = removeDmnSessionFromMarkdown(
                    content = text,
                    sessionHeading = sessionHeading,
                    matchFileName = primaryMatchName ?: audioFileName
                )
                if (removal.removed) {
                    audioToDelete.addAll(removal.referencedAudioNames)
                    transcriptsToDelete.addAll(removal.referencedTranscriptNames)
                    if (removal.remainingMarkdown == null) {
                        if (dailyDoc.delete()) {
                            deletedPaths.add(relPath)
                            dailyNoteDeletedCompletely = true
                        }
                    } else {
                        writeDocumentText(dailyDoc.uri, removal.remainingMarkdown)
                    }
                    if (!dailyNotePath.isNullOrBlank()) break
                }
            }

            // Also derive companion stem (e.g. deleting transcript deletes same-stem audio & vice versa)
            val stems = (audioToDelete + transcriptsToDelete)
                .map { it.substringBeforeLast('.') }
                .filter { it.length >= 6 }
                .toSet()

            val transcriptDir = root.findFile(DIR_TRANSCRIPT_ARCHIVE)?.takeIf { it.isDirectory }
            val removedTranscripts = mutableListOf<String>()
            transcriptDir?.listFiles()?.forEach { doc ->
                val name = doc.name ?: return@forEach
                val stem = name.substringBeforeLast('.')
                if (transcriptsToDelete.any { it.equals(name, ignoreCase = true) } || stems.any { it.equals(stem, ignoreCase = true) }) {
                    if (doc.delete()) {
                        deletedPaths.add("$DIR_TRANSCRIPT_ARCHIVE/$name")
                        removedTranscripts.add(name)
                    }
                }
            }

            val audioDir = root.findFile(DIR_AUDIO_ARCHIVE)?.takeIf { it.isDirectory }
            val removedAudio = mutableListOf<String>()
            audioDir?.listFiles()?.forEach { doc ->
                val name = doc.name ?: return@forEach
                val stem = name.substringBeforeLast('.')
                if (audioToDelete.any { it.equals(name, ignoreCase = true) } || stems.any { it.equals(stem, ignoreCase = true) }) {
                    if (doc.delete()) {
                        deletedPaths.add("$DIR_AUDIO_ARCHIVE/$name")
                        removedAudio.add(name)
                    }
                }
            }

            DeletedSessionArtifacts(
                deletedPaths = deletedPaths.distinct(),
                audioNamesRemoved = removedAudio.distinct(),
                transcriptNamesRemoved = removedTranscripts.distinct(),
                dailyNoteDeletedCompletely = dailyNoteDeletedCompletely
            )
        }
    }

    suspend fun createAtomicNodeStub(
        treeUriString: String,
        title: String,
        subFolder: String = "Concepts"
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder not accessible")
            val atomicDir = findOrCreateDir(root, DIR_ATOMIC_NODES)
            val targetFolder = if (subFolder.isNotBlank()) findOrCreateDir(atomicDir, subFolder) else atomicDir
            val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val fileName = "$safeTitle.md"
            val existing = findMarkdownAnywhere(root, safeTitle)
            if (existing != null) return@runCatching existing

            val doc = targetFolder.createFile("text/markdown", fileName)
                ?: throw IllegalStateException("Could not create atomic note $fileName")
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val stubContent = """
                |---
                |created: $today
                |type: atomic-node
                |tags:
                |  - insight
                |---
                |
                |# $safeTitle
                |
                |## Core Summary
                |
                |## Linked Thoughts
                |- Created from Synapse DMN Knowledge Graph.
                |
            """.trimMargin()
            writeDocumentText(doc.uri, stubContent)
            if (subFolder.isNotBlank()) "$DIR_ATOMIC_NODES/$subFolder/$fileName" else "$DIR_ATOMIC_NODES/$fileName"
        }
    }

    suspend fun toggleMarkdownTask(
        treeUriString: String,
        relativePath: String,
        lineIndex: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = findFileByRelativePath(treeUriString, relativePath)
                ?: throw IllegalStateException("Note not found: $relativePath")
            val lines = readDocumentText(doc.uri).lines().toMutableList()
            if (lineIndex in lines.indices) {
                val line = lines[lineIndex]
                lines[lineIndex] = when {
                    line.contains("- [ ] ") -> line.replaceFirst("- [ ] ", "- [x] ")
                    line.contains("- [x] ") -> line.replaceFirst("- [x] ", "- [ ] ")
                    line.contains("- [X] ") -> line.replaceFirst("- [X] ", "- [ ] ")
                    line.contains("* [ ] ") -> line.replaceFirst("* [ ] ", "* [x] ")
                    line.contains("* [x] ") -> line.replaceFirst("* [x] ", "* [ ] ")
                    else -> line
                }
            }
            val updated = lines.joinToString("\n")
            writeDocumentText(doc.uri, updated)
            updated
        }
    }

    suspend fun readRelativeTextFile(treeUriString: String, relativePath: String): String? = withContext(Dispatchers.IO) {
        val doc = findFileByRelativePath(treeUriString, relativePath) ?: return@withContext null
        readDocumentText(doc.uri)
    }

    suspend fun findArchivedAudioUri(treeUriString: String, audioName: String): Uri? = withContext(Dispatchers.IO) {
        val root = getRootDocument(treeUriString) ?: return@withContext null
        val cleanName = audioName.substringAfterLast('/')
        val archive = root.findFile(DIR_AUDIO_ARCHIVE)
        archive?.findFile(cleanName)?.uri ?: root.findFile(DIR_AUDIO_INBOX)?.findFile(cleanName)?.uri
    }

    suspend fun parseKnowledgeGraph(treeUriString: String?): KnowledgeGraphData = withContext(Dispatchers.IO) {
        val root = getRootDocument(treeUriString) ?: return@withContext KnowledgeGraphData()
        val notes = mutableListOf<VaultNote>()

        walkMarkdownFiles(root, "") { relPath, doc ->
            val content = readDocumentText(doc.uri)
            val title = (doc.name ?: relPath.substringAfterLast('/'))
                .removeSuffix(".md")
                .removeSuffix(".MD")
            val folderType = when {
                relPath.startsWith("$DIR_DAILY_NOTES/") -> DIR_DAILY_NOTES
                relPath.startsWith("$DIR_ATOMIC_NODES/") -> DIR_ATOMIC_NODES
                else -> "Vault"
            }
            val tags = extractAllTags(content)
            val outgoing = WIKILINK_REGEX.findAll(content)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            val embeds = EMBED_AUDIO_REGEX.findAll(content)
                .map { it.groupValues[1].trim() }
                .distinct()
                .toList()
            val transcriptEmbeds = EMBED_TRANSCRIPT_REGEX.findAll(content)
                .map { it.groupValues[1].trim() }
                .distinct()
                .toList()
            val lines = content.lines()
            val openTasks = lines.count { TASK_OPEN_REGEX.matches(it) }
            val doneTasks = lines.count { TASK_DONE_REGEX.matches(it) }

            notes.add(
                VaultNote(
                    relativePath = relPath,
                    title = title,
                    folderType = folderType,
                    content = content,
                    lastModifiedMs = doc.lastModified(),
                    tags = tags,
                    outgoingLinks = outgoing,
                    pendingTasks = openTasks,
                    completedTasks = doneTasks,
                    embeddedAudioNames = embeds,
                    embeddedTranscriptNames = transcriptEmbeds
                )
            )
        }

        val notesById = notes.associateBy { normalizeId(it.title) }
        val incomingMap = mutableMapOf<String, MutableList<String>>()
        val ghostTitles = mutableMapOf<String, String>()
        val edges = linkedSetOf<GraphEdge>()

        notes.forEach { note ->
            val srcId = normalizeId(note.title)
            note.outgoingLinks.forEach { targetTitle ->
                val tgtId = normalizeId(targetTitle)
                if (srcId != tgtId) {
                    edges.add(GraphEdge(srcId, tgtId))
                    incomingMap.getOrPut(tgtId) { mutableListOf() }.add(note.title)
                    if (!notesById.containsKey(tgtId)) {
                        ghostTitles.putIfAbsent(tgtId, targetTitle)
                    }
                }
            }
        }

        val existingNodes = notes.map { note ->
            val id = normalizeId(note.title)
            val group = classifyNodeGroup(note.folderType, note.relativePath, note.tags)
            GraphNode(
                id = id,
                title = note.title,
                relativePath = note.relativePath,
                group = group,
                tags = note.tags,
                outgoingLinks = note.outgoingLinks,
                incomingLinks = incomingMap[id]?.distinct() ?: emptyList(),
                existsAsFile = true
            )
        }

        val ghostNodes = ghostTitles.map { (id, title) ->
            GraphNode(
                id = id,
                title = title,
                relativePath = null,
                group = NodeGroup.GHOST,
                tags = emptyList(),
                outgoingLinks = emptyList(),
                incomingLinks = incomingMap[id]?.distinct() ?: emptyList(),
                existsAsFile = false
            )
        }

        val allNodes = (existingNodes + ghostNodes).sortedByDescending { it.connectionCount }
        KnowledgeGraphData(
            nodes = allNodes,
            edges = edges.toList(),
            notesByPath = notes.associateBy { it.relativePath },
            totalDailyNotes = notes.count { it.folderType == DIR_DAILY_NOTES },
            totalAtomicNodes = notes.count { it.folderType == DIR_ATOMIC_NODES },
            totalGhostLinks = ghostNodes.size,
            totalPendingTasks = notes.sumOf { it.pendingTasks }
        )
    }

    suspend fun listAllSyncableFiles(treeUriString: String?): List<VaultFileEntry> = withContext(Dispatchers.IO) {
        val root = getRootDocument(treeUriString) ?: return@withContext emptyList()
        val result = mutableListOf<VaultFileEntry>()
        walkAllFiles(root, "") { relPath, doc ->
            // Skip Obsidian's volatile local workspace cache and Syncthing temp files
            val fileName = doc.name ?: return@walkAllFiles
            if (relPath.startsWith(".obsidian/") || fileName.startsWith(".syncthing") || fileName.startsWith(".")) {
                return@walkAllFiles
            }
            val mime = doc.type ?: guessMimeFromName(fileName)
            result.add(
                VaultFileEntry(
                    relativePath = relPath,
                    uri = doc.uri,
                    mimeType = mime,
                    sizeBytes = doc.length(),
                    lastModifiedMs = doc.lastModified()
                )
            )
        }
        result
    }

    suspend fun writeRelativeBinaryFile(
        treeUriString: String,
        relativePath: String,
        mimeType: String,
        bytesProvider: suspend (java.io.OutputStream) -> Unit
    ): Result<VaultFileEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val root = getRootDocument(treeUriString)
                ?: throw IllegalStateException("Vault folder not accessible")
            val segments = relativePath.split('/').filter { it.isNotBlank() }
            require(segments.isNotEmpty()) { "Invalid relative path: $relativePath" }
            var currentDir = root
            for (i in 0 until segments.size - 1) {
                currentDir = findOrCreateDir(currentDir, segments[i])
            }
            val fileName = segments.last()
            val existing = currentDir.findFile(fileName)
            val targetDoc = existing ?: (currentDir.createFile(mimeType, fileName)
                ?: throw IllegalStateException("Could not create $relativePath"))

            context.contentResolver.openOutputStream(targetDoc.uri, "wt")?.use { out ->
                bytesProvider(out)
            } ?: context.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                bytesProvider(out)
            } ?: throw IllegalStateException("Cannot open output stream for $relativePath")

            VaultFileEntry(
                relativePath = relativePath,
                uri = targetDoc.uri,
                mimeType = mimeType,
                sizeBytes = targetDoc.length(),
                lastModifiedMs = targetDoc.lastModified()
            )
        }
    }

    fun openInputStream(uri: Uri) = context.contentResolver.openInputStream(uri)

    private fun findFileByRelativePath(treeUriString: String, relativePath: String): DocumentFile? {
        val root = getRootDocument(treeUriString) ?: return null
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        var current: DocumentFile = root
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current.takeIf { it.isFile }
    }

    private fun findMarkdownAnywhere(dir: DocumentFile, titleStem: String): String? {
        return findMarkdownRelative(dir, "", titleStem)
    }

    private fun findMarkdownRelative(dir: DocumentFile, prefix: String, titleStem: String): String? {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (prefix.isEmpty() && child.isDirectory && name in SKIP_GRAPH_DIRS) continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                val found = findMarkdownRelative(child, rel, titleStem)
                if (found != null) return found
            } else if (child.isFile && name.equals("$titleStem.md", ignoreCase = true)) {
                return rel
            }
        }
        return null
    }

    private fun walkMarkdownFiles(
        dir: DocumentFile,
        prefix: String,
        onFile: (String, DocumentFile) -> Unit
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (prefix.isEmpty() && child.isDirectory && name in SKIP_GRAPH_DIRS) continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walkMarkdownFiles(child, rel, onFile)
            } else if (child.isFile && name.endsWith(".md", ignoreCase = true)) {
                onFile(rel, child)
            }
        }
    }

    private fun walkAllFiles(
        dir: DocumentFile,
        prefix: String,
        onFile: (String, DocumentFile) -> Unit
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walkAllFiles(child, rel, onFile)
            } else if (child.isFile) {
                onFile(rel, child)
            }
        }
    }

    private fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile {
        return parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: throw IllegalStateException("Unable to create folder $name inside ${parent.name}")
    }

    private fun resolveUniqueFileName(dir: DocumentFile, desiredName: String): String {
        if (dir.findFile(desiredName) == null) return desiredName
        val stem = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var idx = 1
        while (idx < 1000) {
            val candidate = if (ext.isNotEmpty()) "${stem}_$idx.$ext" else "${stem}_$idx"
            if (dir.findFile(candidate) == null) return candidate
            idx++
        }
        return "${System.currentTimeMillis()}_$desiredName"
    }

    private fun readDocumentText(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: ""
    }

    private fun writeDocumentText(uri: Uri, content: String) {
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Unable to write to document $uri")
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(content)
        }
    }

    private fun extractAllTags(content: String): List<String> {
        val tags = linkedSetOf<String>()
        INLINE_TAG_REGEX.findAll(content).forEach { match ->
            val tag = match.groupValues[1].trim()
            if (!tag.all { it.isDigit() } && !tag.startsWith("#")) {
                tags.add("#$tag")
            }
        }
        return tags.toList()
    }

    private fun classifyNodeGroup(folderType: String, relPath: String, tags: List<String>): NodeGroup {
        val lowerTags = tags.map { it.lowercase(Locale.ROOT) }
        return when {
            folderType == DIR_DAILY_NOTES -> NodeGroup.DAILY
            lowerTags.any { it.contains("goal") || it.contains("project") } ||
                relPath.contains("/Projects/", ignoreCase = true) -> NodeGroup.GOAL
            else -> NodeGroup.INSIGHT
        }
    }

    private fun normalizeId(title: String): String = title.trim().lowercase(Locale.ROOT)

    private fun guessAudioMimeType(name: String, providerType: String?): String {
        if (!providerType.isNullOrBlank() && providerType.startsWith("audio/")) return providerType
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "m4a", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            "ogg" -> "audio/ogg"
            else -> "audio/mp4"
        }
    }

    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext == "md") return "text/markdown"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

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
        const val DIR_AUDIO_ARCHIVE = "99_Audio_Archive"

        private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav", "webm", "ogg", "aac")
        private val DAILY_DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private val WIKILINK_REGEX = Regex("""(?<!!)\[\[([^\[\]|#]+)(?:#[^\[\]|]+)?(?:\|[^\[\]]+)?]]""")
        private val EMBED_AUDIO_REGEX = Regex("""!\[\[(?:99_Audio_Archive/)?([^\]|]+\.(?:m4a|mp3|wav|webm|ogg|aac))]]""", RegexOption.IGNORE_CASE)
        private val INLINE_TAG_REGEX = Regex("""(?<!\S)#([A-Za-z0-9_\-/]+)""")
        private val TASK_OPEN_REGEX = Regex("""^\s*[-*]\s+\[\s]\s+.*""")
        private val TASK_DONE_REGEX = Regex("""^\s*[-*]\s+\[[xX]]\s+.*""")
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
        archivedAudioName: String
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
                    append("# 📅 $dateStr\n")
                }
            } else {
                existingText.trimEnd()
            }

            val formattedEntry = buildString {
                append(header)
                append("\n\n---\n")
                append(entryMarkdown.trim())
                append("\n\n> 🎧 **Original Audio:** ![[$DIR_AUDIO_ARCHIVE/$archivedAudioName]]\n")
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
            val archivedName = "${stamp}_$sourceName"
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
            archivedName
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
                    embeddedAudioNames = embeds
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

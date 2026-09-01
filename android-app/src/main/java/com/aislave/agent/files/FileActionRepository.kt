package com.aislave.agent.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aislave.agent.transactions.MoveTransaction
import java.io.File

class FileActionRepository(private val context: Context) {
    private val resolver = context.contentResolver

    fun internalStorageRoot(): File {
        val primary = File("/storage/emulated/0")
        if (primary.exists() && primary.canRead()) return primary
        return android.os.Environment.getExternalStorageDirectory()
    }

    fun persistFolderGrant(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, flags)
    }

    fun folderName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name.ifBlank { "Internal Storage" }
        return DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "Folder"
    }

    fun listFiles(folderUri: Uri): List<FileItem> {
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        return folder.listFiles()
            .filter { it.isFile && it.canRead() }
            .map {
                FileItem(
                    uri = it.uri,
                    name = it.name ?: "Untitled",
                    mimeType = it.type,
                    size = it.length(),
                    parentFolderUri = folderUri,
                    path = it.name ?: "Untitled"
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun listFilesRecursive(rootFolderUri: Uri, maxFiles: Int = 500): List<FileItem> {
        if (rootFolderUri.scheme == "file") {
            return listDeviceFilesRecursive(File(rootFolderUri.path.orEmpty()), maxFiles)
        }
        val root = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return emptyList()
        val results = mutableListOf<FileItem>()
        collectFiles(folder = root, parentFolderUri = rootFolderUri, parentPath = "", results = results, maxFiles = maxFiles)
        return results.sortedBy { it.path.lowercase() }
    }

    fun findBestFolderMatch(rootFolderUri: Uri, query: String, maxFolders: Int = 5000): FolderItem? {
        if (rootFolderUri.scheme == "file") {
            return findBestDeviceFolderMatch(File(rootFolderUri.path.orEmpty()), query, maxFolders)
        }
        val root = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return null
        findDirectSafFolderMatch(root, query)?.let { return it }
        val folders = mutableListOf<FolderItem>()
        collectFolders(folder = root, path = root.name ?: "Storage", results = folders, maxFolders = maxFolders)
        val queryTokens = query.normalizedTokens()
        if (queryTokens.isEmpty()) return null

        return folders.mapNotNull { folder ->
            val nameTokens = folder.path.normalizedTokens()
            val folderNameTokens = folder.name.normalizedTokens()
            val compactPath = folder.path.lowercase().replace(Regex("[^a-z0-9]"), "")
            val compactName = folder.name.lowercase().replace(Regex("[^a-z0-9]"), "")
            val compactQuery = query.lowercase().replace(Regex("[^a-z0-9]"), "")
            val exactBoost = when {
                compactName == compactQuery -> 80
                compactPath.endsWith(compactQuery) -> 40
                compactPath.contains(compactQuery) -> 12
                else -> 0
            }
            val depthPenalty = folder.path.count { it == '/' }
            val score = queryTokens.count { token ->
                folderNameTokens.any { nameToken -> nameToken.matchesQueryToken(token) }
            } * 8 + queryTokens.count { token ->
                nameTokens.any { nameToken -> nameToken.matchesQueryToken(token) }
            } + exactBoost - depthPenalty
            if (score >= MIN_FOLDER_MATCH_SCORE) folder.copy(score = score) else null
        }.maxByOrNull { it.score }
    }

    fun findBestFileMatch(files: List<FileItem>, query: String): FileItemMatch? {
        return findFileMatches(files, query, limit = 1).firstOrNull()
    }

    fun findFileMatches(files: List<FileItem>, query: String, limit: Int = 8): List<FileItemMatch> {
        val queryTokens = query.normalizedTokens()
        if (queryTokens.isEmpty()) return emptyList()
        val compactQuery = query.lowercase().replace(Regex("[^a-z0-9]"), "")
        val longQueryTokens = queryTokens.filter { it.length >= 6 }

        return files.mapNotNull { file ->
            val searchableText = "${file.name} ${file.path}"
            val nameTokens = searchableText.normalizedTokens()
            val compactName = searchableText.lowercase().replace(Regex("[^a-z0-9]"), "")
            val hasRequiredLongMatch = longQueryTokens.isEmpty() || longQueryTokens.any { token ->
                nameTokens.any { nameToken -> nameToken.matchesQueryToken(token) }
            }
            if (!hasRequiredLongMatch) return@mapNotNull null

            val exactBoost = when {
                compactName == compactQuery -> 80
                file.name.lowercase().replace(Regex("[^a-z0-9]"), "") == compactQuery -> 70
                compactQuery.length >= 5 && compactName.contains(compactQuery) -> 40
                else -> 0
            }
            val tokenScore = queryTokens.count { token ->
                nameTokens.any { nameToken -> nameToken.matchesQueryToken(token) }
            } * 3
            val extensionBoost = if (query.substringAfterLast('.', "") == file.name.substringAfterLast('.', "")) 5 else 0
            val score = tokenScore + exactBoost + extensionBoost
            if (score >= minimumFileScore(queryTokens, compactQuery)) FileItemMatch(file, score) else null
        }
            .sortedWith(compareByDescending<FileItemMatch> { it.score }.thenBy { it.file.path.lowercase() })
            .take(limit)
    }

    fun searchFiles(folderUri: Uri, query: String): List<FileItemMatch> {
        val files = listFiles(folderUri)
        val queryTokens = query.normalizedTokens()
        if (queryTokens.isEmpty()) return files.map { FileItemMatch(it, 1) }

        return files.mapNotNull { file ->
            val nameTokens = file.name.normalizedTokens()
            val score = queryTokens.count { token ->
                nameTokens.any { nameToken -> nameToken.matchesQueryToken(token) }
            }
            if (score > 0) FileItemMatch(file, score) else null
        }.sortedWith(compareByDescending<FileItemMatch> { it.score }.thenBy { it.file.name.lowercase() })
    }

    fun copyFile(file: FileItem, destinationFolderUri: Uri): FileToolResult {
        if (file.uri.scheme == "file" && destinationFolderUri.scheme == "file") {
            return copyDeviceFile(file, destinationFolderUri)
        }
        val source = DocumentFile.fromSingleUri(context, file.uri)
            ?: return FileToolResult.failure("Source file is no longer available.")
        val destinationFolder = DocumentFile.fromTreeUri(context, destinationFolderUri)
            ?: return FileToolResult.failure("Destination folder is no longer available.")
        if (!destinationFolder.canWrite()) {
            return FileToolResult.failure("Destination folder is not writable.")
        }

        val destination = destinationFolder.createFile(
            file.mimeType ?: "application/octet-stream",
            uniqueName(destinationFolder, file.name)
        ) ?: return FileToolResult.failure("Could not create destination file.")

        val copied = copyBytes(source.uri, destination.uri)
        if (!copied) {
            destination.delete()
            return FileToolResult.failure("Could not copy file contents.")
        }

        return if (destination.exists()) {
            FileToolResult.success(destination.uri)
        } else {
            FileToolResult.failure("Copied file, but verification failed.")
        }
    }

    fun renameFile(fileUri: Uri, newName: String): FileToolResult {
        if (fileUri.scheme == "file") {
            return renameDeviceFile(fileUri, newName)
        }
        val file = DocumentFile.fromSingleUri(context, fileUri)
            ?: return FileToolResult.failure("File is no longer available.")
        if (!file.canWrite()) {
            return FileToolResult.failure("File is not writable.")
        }

        val renamed = file.renameTo(newName)
        return if (renamed && file.exists()) {
            FileToolResult.success(file.uri)
        } else {
            FileToolResult.failure("Could not verify rename.")
        }
    }

    fun createFolder(parentFolderUri: Uri, folderName: String): FileToolResult {
        if (parentFolderUri.scheme == "file") {
            return createDeviceFolder(parentFolderUri, folderName)
        }
        val parent = DocumentFile.fromTreeUri(context, parentFolderUri)
            ?: return FileToolResult.failure("Parent folder is no longer available.")
        if (!parent.canWrite()) {
            return FileToolResult.failure("Parent folder is not writable.")
        }
        val folder = parent.createDirectory(uniqueName(parent, folderName))
            ?: return FileToolResult.failure("Could not create folder.")
        return if (folder.exists() && folder.isDirectory) {
            FileToolResult.success(folder.uri)
        } else {
            FileToolResult.failure("Created folder, but verification failed.")
        }
    }

    fun deleteFile(fileUri: Uri): FileToolResult {
        if (fileUri.scheme == "file") {
            return deleteDeviceFile(fileUri)
        }
        val file = DocumentFile.fromSingleUri(context, fileUri)
            ?: return FileToolResult.failure("File is no longer available.")
        if (!file.canWrite()) {
            return FileToolResult.failure("File is not writable.")
        }
        val deleted = file.delete()
        return if (deleted && !file.exists()) {
            FileToolResult.success(fileUri)
        } else {
            FileToolResult.failure("Could not verify deletion.")
        }
    }

    fun moveFile(file: FileItem, destinationFolderUri: Uri): FileToolResult {
        if (file.uri.scheme == "file" && destinationFolderUri.scheme == "file") {
            return moveDeviceFile(file, destinationFolderUri)
        }
        val source = DocumentFile.fromSingleUri(context, file.uri)
            ?: return FileToolResult.failure("Source file is no longer available.")
        val destinationFolder = DocumentFile.fromTreeUri(context, destinationFolderUri)
            ?: return FileToolResult.failure("Destination folder is no longer available.")
        if (!destinationFolder.canWrite()) {
            return FileToolResult.failure("Destination folder is not writable.")
        }

        val destinationName = uniqueName(destinationFolder, file.name)
        val destination = destinationFolder.createFile(
            file.mimeType ?: "application/octet-stream",
            destinationName
        ) ?: return FileToolResult.failure("Could not create destination file.")

        val copied = copyBytes(source.uri, destination.uri)
        if (!copied) {
            destination.delete()
            return FileToolResult.failure("Could not copy file contents.")
        }

        val deleted = source.delete()
        val verified = destination.exists() && !source.exists()
        if (!deleted || !verified) {
            return FileToolResult.failure("Copied file, but could not verify source deletion.")
        }

        return FileToolResult.success(destination.uri)
    }

    fun undoMove(transaction: MoveTransaction): FileToolResult {
        val movedUri = Uri.parse(transaction.destinationUri)
        val sourceFolderUri = Uri.parse(transaction.sourceFolderUri)
        if (movedUri.scheme == "file" && sourceFolderUri.scheme == "file") {
            return moveDeviceFile(
                file = FileItem.fromFile(File(movedUri.path.orEmpty())),
                destinationFolderUri = sourceFolderUri
            )
        }
        val movedFile = DocumentFile.fromSingleUri(context, Uri.parse(transaction.destinationUri))
            ?: return FileToolResult.failure("Moved file is no longer available.")
        val originalFolder = DocumentFile.fromTreeUri(context, Uri.parse(transaction.sourceFolderUri))
            ?: return FileToolResult.failure("Original folder is no longer available.")
        if (!originalFolder.canWrite()) {
            return FileToolResult.failure("Original folder is not writable.")
        }

        val destinationName = uniqueName(originalFolder, transaction.fileName)
        val restored = originalFolder.createFile(
            movedFile.type ?: "application/octet-stream",
            destinationName
        ) ?: return FileToolResult.failure("Could not recreate original file.")

        val copied = copyBytes(movedFile.uri, restored.uri)
        if (!copied) {
            restored.delete()
            return FileToolResult.failure("Could not copy file back.")
        }

        val deleted = movedFile.delete()
        val verified = restored.exists() && !movedFile.exists()
        if (!deleted || !verified) {
            return FileToolResult.failure("Restored file, but could not verify cleanup.")
        }

        return FileToolResult.success(restored.uri)
    }

    private fun copyBytes(sourceUri: Uri, destinationUri: Uri): Boolean {
        return runCatching {
            resolver.openInputStream(sourceUri).use { input ->
                resolver.openOutputStream(destinationUri, "w").use { output ->
                    check(input != null) { "Unable to open source stream." }
                    check(output != null) { "Unable to open destination stream." }
                    input.copyTo(output)
                    output.flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun uniqueName(folder: DocumentFile, requestedName: String): String {
        if (folder.findFile(requestedName) == null) return requestedName

        val dotIndex = requestedName.lastIndexOf('.')
        val base = if (dotIndex > 0) requestedName.substring(0, dotIndex) else requestedName
        val extension = if (dotIndex > 0) requestedName.substring(dotIndex) else ""
        var counter = 1
        var candidate: String
        do {
            candidate = "$base ($counter)$extension"
            counter += 1
        } while (folder.findFile(candidate) != null)
        return candidate
    }

    private fun moveDeviceFile(file: FileItem, destinationFolderUri: Uri): FileToolResult {
        val source = File(file.uri.path.orEmpty())
        val destinationFolder = File(destinationFolderUri.path.orEmpty())
        if (!source.exists() || !source.isFile) {
            return FileToolResult.failure("Source file is no longer available.")
        }
        if (!destinationFolder.exists() || !destinationFolder.isDirectory || !destinationFolder.canWrite()) {
            return FileToolResult.failure("Destination folder is not writable.")
        }
        val destination = uniqueFile(destinationFolder, source.name)
        val copied = runCatching {
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            true
        }.getOrDefault(false)
        if (!copied) {
            destination.delete()
            return FileToolResult.failure("Could not copy file contents.")
        }

        val deleted = source.delete()
        return if (deleted && destination.exists() && !source.exists()) {
            FileToolResult.success(Uri.fromFile(destination))
        } else {
            FileToolResult.failure("Copied file, but could not verify source deletion.")
        }
    }

    private fun copyDeviceFile(file: FileItem, destinationFolderUri: Uri): FileToolResult {
        val source = File(file.uri.path.orEmpty())
        val destinationFolder = File(destinationFolderUri.path.orEmpty())
        if (!source.exists() || !source.isFile) {
            return FileToolResult.failure("Source file is no longer available.")
        }
        if (!destinationFolder.exists() || !destinationFolder.isDirectory || !destinationFolder.canWrite()) {
            return FileToolResult.failure("Destination folder is not writable.")
        }
        val destination = uniqueFile(destinationFolder, source.name)
        val copied = runCatching {
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            true
        }.getOrDefault(false)
        return if (copied && destination.exists() && source.exists()) {
            FileToolResult.success(Uri.fromFile(destination))
        } else {
            destination.delete()
            FileToolResult.failure("Could not verify copied file.")
        }
    }

    private fun renameDeviceFile(fileUri: Uri, newName: String): FileToolResult {
        val source = File(fileUri.path.orEmpty())
        if (!source.exists() || !source.isFile || !source.canWrite()) {
            return FileToolResult.failure("File is not writable.")
        }
        val requested = File(newName)
        val safeName = requested.name.ifBlank { return FileToolResult.failure("New file name is empty.") }
        val destination = uniqueFile(source.parentFile ?: return FileToolResult.failure("Parent folder is unavailable."), safeName)
        val renamed = source.renameTo(destination)
        return if (renamed && destination.exists() && !source.exists()) {
            FileToolResult.success(Uri.fromFile(destination))
        } else {
            FileToolResult.failure("Could not verify rename.")
        }
    }

    private fun createDeviceFolder(parentFolderUri: Uri, folderName: String): FileToolResult {
        val parent = File(parentFolderUri.path.orEmpty())
        if (!parent.exists() || !parent.isDirectory || !parent.canWrite()) {
            return FileToolResult.failure("Parent folder is not writable.")
        }
        val folder = uniqueFile(parent, folderName)
        val created = folder.mkdirs()
        return if (created && folder.exists() && folder.isDirectory) {
            FileToolResult.success(Uri.fromFile(folder))
        } else {
            FileToolResult.failure("Could not verify created folder.")
        }
    }

    private fun deleteDeviceFile(fileUri: Uri): FileToolResult {
        val file = File(fileUri.path.orEmpty())
        if (!file.exists() || !file.isFile || !file.canWrite()) {
            return FileToolResult.failure("File is not writable.")
        }
        val deleted = file.delete()
        return if (deleted && !file.exists()) {
            FileToolResult.success(fileUri)
        } else {
            FileToolResult.failure("Could not verify deletion.")
        }
    }
}

data class FileItemMatch(
    val file: FileItem,
    val score: Int
)

data class FolderItem(
    val uri: Uri,
    val name: String,
    val path: String,
    val score: Int = 0
)

data class FileItem(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val parentFolderUri: Uri,
    val path: String
) {
    val displaySize: String
        get() = when {
            size < 0 -> "Unknown size"
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }

    companion object {
        fun fromFile(file: File): FileItem {
            return FileItem(
                uri = Uri.fromFile(file),
                name = file.name,
                mimeType = null,
                size = file.length(),
                parentFolderUri = Uri.fromFile(file.parentFile ?: file),
                path = file.path
            )
        }
    }
}

private fun listDeviceFilesRecursive(root: File, maxFiles: Int): List<FileItem> {
    val results = mutableListOf<FileItem>()
    collectDeviceFiles(root, root.name.ifBlank { "Internal Storage" }, results, maxFiles)
    return results.sortedBy { it.path.lowercase() }
}

private fun findBestDeviceFolderMatch(root: File, query: String, maxFolders: Int): FolderItem? {
    findDirectDeviceFolderMatch(root, query)?.let { return it }
    val folders = mutableListOf<FolderItem>()
    collectDeviceFolders(root, root.name.ifBlank { "Internal Storage" }, folders, maxFolders)
    val queryTokens = query.normalizedTokens()
    return folders.mapNotNull { folder ->
        val nameTokens = folder.path.normalizedTokens()
        val folderNameTokens = folder.name.normalizedTokens()
        val compactPath = folder.path.lowercase().replace(Regex("[^a-z0-9]"), "")
        val compactName = folder.name.lowercase().replace(Regex("[^a-z0-9]"), "")
        val compactQuery = query.lowercase().replace(Regex("[^a-z0-9]"), "")
        val exactBoost = when {
            compactName == compactQuery -> 80
            compactPath.endsWith(compactQuery) -> 40
            compactPath.contains(compactQuery) -> 12
            else -> 0
        }
        val depthPenalty = folder.path.count { it == '/' }
        val score = queryTokens.count { token ->
            folderNameTokens.any { nameToken -> nameToken.matchesQueryToken(token) }
        } * 8 + queryTokens.count { token ->
            nameTokens.any { nameToken -> nameToken.matchesQueryToken(token) }
        } + exactBoost - depthPenalty
        if (score >= MIN_FOLDER_MATCH_SCORE) folder.copy(score = score) else null
    }.maxByOrNull { it.score }
}

private fun findDirectDeviceFolderMatch(root: File, query: String): FolderItem? {
    val normalizedQuery = query.folderLookupKey()
    if (normalizedQuery.isBlank()) return null

    return root.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.canRead() }
        .firstOrNull { it.name.folderLookupKey() == normalizedQuery }
        ?.let { folder ->
            FolderItem(
                uri = Uri.fromFile(folder),
                name = folder.name,
                path = "${root.name.ifBlank { "Internal Storage" }}/${folder.name}",
                score = 200
            )
        }
}

private fun findDirectSafFolderMatch(root: DocumentFile, query: String): FolderItem? {
    val normalizedQuery = query.folderLookupKey()
    if (normalizedQuery.isBlank()) return null

    return root.listFiles()
        .filter { it.isDirectory && it.canRead() }
        .firstOrNull { (it.name ?: "").folderLookupKey() == normalizedQuery }
        ?.let { folder ->
            FolderItem(
                uri = folder.uri,
                name = folder.name ?: "Folder",
                path = "${root.name ?: "Storage"}/${folder.name ?: "Folder"}",
                score = 200
            )
        }
}

private fun collectDeviceFiles(root: File, path: String, results: MutableList<FileItem>, maxFiles: Int) {
    if (results.size >= maxFiles || !root.canRead()) return
    root.listFiles()
        .orEmpty()
        .sortedWith(deviceFilePriorityComparator)
        .forEach { child ->
        if (results.size >= maxFiles) return
        val childPath = "$path/${child.name}"
        when {
            child.isFile && child.canRead() -> results += FileItem(
                uri = Uri.fromFile(child),
                name = child.name,
                mimeType = null,
                size = child.length(),
                parentFolderUri = Uri.fromFile(child.parentFile ?: root),
                path = childPath
            )
            child.isDirectory && child.canRead() -> collectDeviceFiles(child, childPath, results, maxFiles)
        }
    }
}

private fun collectDeviceFolders(root: File, path: String, results: MutableList<FolderItem>, maxFolders: Int) {
    if (results.size >= maxFolders || !root.canRead()) return
    results += FolderItem(Uri.fromFile(root), root.name.ifBlank { "Internal Storage" }, path)
    root.listFiles().orEmpty()
        .filter { it.isDirectory && it.canRead() }
        .sortedWith(deviceFilePriorityComparator)
        .forEach { child ->
            if (results.size >= maxFolders) return
            collectDeviceFolders(child, "$path/${child.name}", results, maxFolders)
        }
}

private val deviceFilePriorityComparator = compareBy<File> {
    when (it.name.lowercase()) {
        "documents" -> 0
        "download", "downloads" -> 1
        "dcim" -> 2
        "pictures" -> 3
        "music" -> 4
        "movies" -> 5
        "android" -> 20
        else -> 10
    }
}.thenBy { it.name.lowercase() }

private fun uniqueFile(folder: File, requestedName: String): File {
    var candidate = File(folder, requestedName)
    if (!candidate.exists()) return candidate

    val dotIndex = requestedName.lastIndexOf('.')
    val base = if (dotIndex > 0) requestedName.substring(0, dotIndex) else requestedName
    val extension = if (dotIndex > 0) requestedName.substring(dotIndex) else ""
    var counter = 1
    while (candidate.exists()) {
        candidate = File(folder, "$base ($counter)$extension")
        counter += 1
    }
    return candidate
}

private fun collectFiles(
    folder: DocumentFile,
    parentFolderUri: Uri,
    parentPath: String,
    results: MutableList<FileItem>,
    maxFiles: Int
) {
    if (results.size >= maxFiles) return

    folder.listFiles().forEach { child ->
        if (results.size >= maxFiles) return
        val name = child.name ?: "Untitled"
        val path = listOf(parentPath, name).filter { it.isNotBlank() }.joinToString("/")
        when {
            child.isFile && child.canRead() -> results += FileItem(
                uri = child.uri,
                name = name,
                mimeType = child.type,
                size = child.length(),
                parentFolderUri = parentFolderUri,
                path = path
            )
            child.isDirectory && child.canRead() -> collectFiles(
                folder = child,
                parentFolderUri = child.uri,
                parentPath = path,
                results = results,
                maxFiles = maxFiles
            )
        }
    }
}

private fun collectFolders(
    folder: DocumentFile,
    path: String,
    results: MutableList<FolderItem>,
    maxFolders: Int
) {
    if (results.size >= maxFolders) return

    results += FolderItem(
        uri = folder.uri,
        name = folder.name ?: "Folder",
        path = path
    )
    folder.listFiles()
        .filter { it.isDirectory && it.canRead() }
        .forEach { child ->
            if (results.size >= maxFolders) return
            val name = child.name ?: "Folder"
            collectFolders(
                folder = child,
                path = "$path/$name",
                results = results,
                maxFolders = maxFolders
            )
        }
}

data class FileToolResult(
    val isSuccess: Boolean,
    val destinationFileUri: Uri?,
    val message: String
) {
    companion object {
        fun success(destinationFileUri: Uri): FileToolResult {
            return FileToolResult(true, destinationFileUri, "Success")
        }

        fun failure(message: String): FileToolResult {
            return FileToolResult(false, null, message)
        }
    }
}

private fun String.normalizedTokens(): List<String> {
    return lowercase()
        .replace(Regex("[^a-z0-9.]+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim('.') }
        .filter { it.length >= 2 }
        .flatMap { token -> listOf(token) + semanticAliases[token].orEmpty() }
        .distinct()
}

private fun String.folderLookupKey(): String {
    return lowercase()
        .replace(Regex("\\b(folder|directory|dir)\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), "")
}

private fun minimumFileScore(queryTokens: List<String>, compactQuery: String): Int {
    return when {
        compactQuery.length >= 8 -> 18
        queryTokens.any { it.length >= 5 } -> 12
        else -> 8
    }
}

private const val MIN_FOLDER_MATCH_SCORE = 8

private val semanticAliases = mapOf(
    "assignment" to listOf("homework", "task", "work", "submission"),
    "assignemnt" to listOf("assignment", "homework", "task", "submission"),
    "homework" to listOf("assignment", "task", "submission"),
    "marks" to listOf("grades", "grade", "score", "scores", "result", "results"),
    "grade" to listOf("marks", "score", "result"),
    "grades" to listOf("marks", "scores", "results"),
    "bill" to listOf("invoice", "receipt", "payment"),
    "bills" to listOf("invoice", "receipt", "payment"),
    "invoice" to listOf("bill", "receipt", "payment"),
    "receipt" to listOf("bill", "invoice", "payment"),
    "photo" to listOf("image", "picture", "pic"),
    "image" to listOf("photo", "picture", "pic"),
    "picture" to listOf("photo", "image", "pic"),
    "college" to listOf("clg", "university", "semester", "sem"),
    "clg" to listOf("college", "university"),
    "certificate" to listOf("cert", "marksheet", "document"),
    "document" to listOf("doc", "pdf", "file")
)

private fun String.matchesQueryToken(queryToken: String): Boolean {
    return contains(queryToken) ||
        queryToken.contains(this) ||
        levenshteinDistance(this, queryToken) <= allowedTypoDistance(queryToken)
}

private fun allowedTypoDistance(token: String): Int {
    return when {
        token.length >= 8 -> 2
        token.length >= 5 -> 1
        else -> 0
    }
}

private fun levenshteinDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length

    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)

    for (i in 1..left.length) {
        current[0] = i
        for (j in 1..right.length) {
            val substitutionCost = if (left[i - 1] == right[j - 1]) 0 else 1
            current[j] = minOf(
                current[j - 1] + 1,
                previous[j] + 1,
                previous[j - 1] + substitutionCost
            )
        }
        val swap = previous
        previous = current
        current = swap
    }

    return previous[right.length]
}

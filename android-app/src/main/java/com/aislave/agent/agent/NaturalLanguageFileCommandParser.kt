package com.aislave.agent.agent

enum class FileAction {
    Move,
    Copy,
    Rename,
    Delete,
    CreateFolder,
    Search
}

class NaturalLanguageFileCommandParser {
    fun parse(input: String): ParseResult {
        val cleaned = input.trim()
        if (cleaned.isBlank()) {
            return ParseResult.Failure("Type a file command first.")
        }

        val normalized = cleaned.lowercase()
        return when {
            createFolderVerbs.any { normalized.containsLooseWord(it) } &&
                normalized.containsLooseWord("folder") -> parseCreateFolder(cleaned)
            renameVerbs.any { normalized.containsLooseWord(it) } -> parseRename(cleaned)
            copyVerbs.any { normalized.containsLooseWord(it) } -> parseTransfer(cleaned, FileAction.Copy)
            moveVerbs.any { normalized.containsLooseWord(it) } -> parseTransfer(cleaned, FileAction.Move)
            deleteVerbs.any { normalized.containsLooseWord(it) } -> parseSingleFile(cleaned, FileAction.Delete)
            searchVerbs.any { normalized.containsLooseWord(it) } -> parseSingleFile(cleaned, FileAction.Search)
            else -> ParseResult.Failure("I can understand file commands like move, copy, rename, delete, create folder, search, show, list, or find.")
        }
    }

    private fun parseTransfer(input: String, action: FileAction): ParseResult {
        val destinationMatch = findDestinationMarker(input)
        val sourceMatch = findSourceMarker(input, beforeIndex = destinationMatch?.range?.first ?: input.length)
        val destinationHint = destinationMatch
            ?.let { input.substring(it.range.last + 1).cleanupPhrase() }
            ?.takeIf { it.isNotBlank() }
        val sourceHint = sourceMatch
            ?.let { match ->
                val start = match.range.last + 1
                val end = destinationMatch?.range?.first ?: input.length
                input.substring(start, end).cleanupPhrase()
            }
            ?.takeIf { it.isNotBlank() }
        val fileQuery = extractFileQuery(input, sourceMatch, destinationMatch, moveVerbs + copyVerbs)
        if (fileQuery.isBlank()) {
            return ParseResult.Failure("I could not identify which file to ${action.name.lowercase()}.")
        }
        if (destinationHint.isNullOrBlank()) {
            return ParseResult.Failure("I found the file request, but not the destination folder.")
        }
        return ParseResult.Success(
            FileCommand(
                originalText = input,
                action = action,
                fileQuery = fileQuery,
                sourceHint = sourceHint,
                destinationHint = destinationHint
            )
        )
    }

    private fun parseRename(input: String): ParseResult {
        val renameMarker = findLastMarker(input, renameTargetMarkers)
            ?: return ParseResult.Failure("Tell me the new name, for example: rename old.pdf to new.pdf.")
        val sourceMatch = findSourceMarker(input, beforeIndex = renameMarker.range.first)
        val fileQuery = extractFileQuery(input, sourceMatch, renameMarker, renameVerbs)
        val sourceHint = sourceMatch
            ?.let { input.substring(it.range.last + 1, renameMarker.range.first).cleanupPhrase() }
            ?.takeIf { it.isNotBlank() }
        val newName = input.substring(renameMarker.range.last + 1).cleanupPhrase()
        if (fileQuery.isBlank() || newName.isBlank()) {
            return ParseResult.Failure("I could not identify both the current file and the new name.")
        }
        return ParseResult.Success(
            FileCommand(
                originalText = input,
                action = FileAction.Rename,
                fileQuery = fileQuery,
                sourceHint = sourceHint,
                newName = newName
            )
        )
    }

    private fun parseCreateFolder(input: String): ParseResult {
        val parentMarker = findLastMarker(input, sourceMarkers)
        val folderNamePart = if (parentMarker == null) input else input.substring(0, parentMarker.range.first)
        val parentHint = parentMarker
            ?.let { input.substring(it.range.last + 1).cleanupPhrase() }
            ?.takeIf { it.isNotBlank() }
        var folderName = folderNamePart
        createFolderVerbs.forEach { verb ->
            folderName = folderName.replace(Regex("\\b${Regex.escape(verb)}\\w*\\b", RegexOption.IGNORE_CASE), " ")
        }
        folderName = folderName
            .replace(Regex("\\b(folder|directory|dir|new)\\b", RegexOption.IGNORE_CASE), " ")
            .cleanupPhrase()
        if (folderName.isBlank()) {
            return ParseResult.Failure("I could not identify the folder name to create.")
        }
        return ParseResult.Success(
            FileCommand(
                originalText = input,
                action = FileAction.CreateFolder,
                folderName = folderName,
                destinationHint = parentHint
            )
        )
    }

    private fun parseSingleFile(input: String, action: FileAction): ParseResult {
        val sourceMatch = findSourceMarker(input, beforeIndex = input.length)
        val sourceHint = sourceMatch
            ?.let { input.substring(it.range.last + 1).cleanupPhrase() }
            ?.takeIf { it.isNotBlank() }
        val actionVerbs = if (action == FileAction.Delete) deleteVerbs else searchVerbs
        val fileQuery = extractFileQuery(input, sourceMatch, null, actionVerbs)
        if (fileQuery.isBlank()) {
            return ParseResult.Failure("I could not identify which file to ${action.name.lowercase()}.")
        }
        return ParseResult.Success(
            FileCommand(
                originalText = input,
                action = action,
                fileQuery = fileQuery,
                sourceHint = sourceHint
            )
        )
    }

    private fun extractFileQuery(
        input: String,
        sourceMatch: MatchResult?,
        stopMatch: MatchResult?,
        actionVerbs: List<String>
    ): String {
        val firstBoundary = listOfNotNull(sourceMatch?.range?.first, stopMatch?.range?.first)
            .minOrNull()
            ?: input.length
        var result = input.substring(0, firstBoundary)
        actionVerbs.forEach { verb ->
            result = result.replace(Regex("\\b${Regex.escape(verb)}\\w*\\b", RegexOption.IGNORE_CASE), " ")
        }
        fillerWords.forEach { word ->
            result = result.replace(Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE), " ")
        }
        return result.cleanupPhrase()
    }

    private fun findLastMarker(input: String, markers: List<String>): MatchResult? {
        return markers
            .flatMap { marker -> Regex("\\b${Regex.escape(marker)}\\b", RegexOption.IGNORE_CASE).findAll(input).toList() }
            .maxByOrNull { it.range.first }
    }

    private fun findDestinationMarker(input: String): MatchResult? {
        return findLastMarker(input, strongDestinationMarkers) ?: findLastMarker(input, weakDestinationMarkers)
    }

    private fun findSourceMarker(input: String, beforeIndex: Int): MatchResult? {
        return sourceMarkers
            .flatMap { marker -> Regex("\\b${Regex.escape(marker)}\\b", RegexOption.IGNORE_CASE).findAll(input).toList() }
            .filter { it.range.first < beforeIndex }
            .maxByOrNull { it.range.first }
    }

    private fun String.containsLooseWord(word: String): Boolean {
        return Regex("\\b${Regex.escape(word)}\\w*\\b", RegexOption.IGNORE_CASE).containsMatchIn(this)
    }

    private fun String.cleanupPhrase(): String {
        return replace(Regex("[\"'`]+"), " ")
            .replace(Regex("\\b(can you|could you|kindly|please|yaar|bro)\\b", RegexOption.IGNORE_CASE), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim('.', ',', ';', ':', ' ')
    }

    private companion object {
        val moveVerbs = listOf("move", "shift", "transfer", "put", "send")
        val copyVerbs = listOf("copy", "duplicate")
        val renameVerbs = listOf("rename", "renaming")
        val deleteVerbs = listOf("delete", "remove", "trash")
        val createFolderVerbs = listOf("create", "make", "add")
        val searchVerbs = listOf("search", "find", "show", "list", "locate")
        val strongDestinationMarkers = listOf("to", "into")
        val weakDestinationMarkers = listOf("inside", "in")
        val sourceMarkers = listOf("from", "in", "inside", "within", "under")
        val renameTargetMarkers = listOf("to", "as")
        val fillerWords = listOf(
            "the",
            "file",
            "document",
            "pdf",
            "image",
            "photo",
            "video",
            "please",
            "my",
            "this",
            "that",
            "folder",
            "directory",
            "named",
            "called"
        )
    }
}

data class FileCommand(
    val originalText: String,
    val action: FileAction,
    val fileQuery: String? = null,
    val sourceHint: String? = null,
    val destinationHint: String? = null,
    val newName: String? = null,
    val folderName: String? = null
)

sealed interface ParseResult {
    data class Success(val command: FileCommand) : ParseResult
    data class Failure(val message: String) : ParseResult
}

package com.aislave.agent.agent

import com.aislave.agent.core.V1ToolRegistry

interface FileCommandInterpreter {
    suspend fun interpret(input: String): ParseResult
}

class LocalFileCommandInterpreter(
    private val parser: NaturalLanguageFileCommandParser = NaturalLanguageFileCommandParser()
) : FileCommandInterpreter {
    override suspend fun interpret(input: String): ParseResult {
        return parser.parse(input)
    }
}

class HybridFileCommandInterpreter(
    private val local: FileCommandInterpreter,
    private val backend: BackendFileCommandInterpreter
) {
    suspend fun interpret(input: String, backendUrl: String): ParseResult {
        if (backendUrl.isBlank()) return local.interpret(input)

        return when (val backendResult = backend.interpret(input = input, backendUrl = backendUrl)) {
            is ParseResult.Success -> backendResult
            is ParseResult.Failure -> {
                val localResult = local.interpret(input)
                when (localResult) {
                    is ParseResult.Success -> localResult
                    is ParseResult.Failure -> ParseResult.Failure("${backendResult.message} I also tried the offline parser, but it could not understand this command: ${localResult.message}")
                }
            }
        }
    }

    companion object {
        fun toolContext(): String {
            val fileTools = listOf(
                V1ToolRegistry.searchFiles,
                V1ToolRegistry.moveFile,
                V1ToolRegistry.copyFile,
                V1ToolRegistry.renameFile,
                V1ToolRegistry.createFolder,
                V1ToolRegistry.deleteFile
            )
            return fileTools.joinToString(separator = "\n") { tool ->
                "- ${tool.name}: ${tool.description} Risk=${tool.risk}. Confirmation=${tool.requiresConfirmation}. Verification=${tool.verification}"
            }
        }
    }
}

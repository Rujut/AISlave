package com.aislave.agent.agent

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL

class BackendFileCommandInterpreter : FileCommandInterpreter {
    override suspend fun interpret(input: String): ParseResult {
        return ParseResult.Failure("Backend URL is required.")
    }

    suspend fun interpret(input: String, backendUrl: String): ParseResult {
        return runCatching {
            val endpoint = "${backendUrl.trim().trimEnd('/')}/v1/interpret"
            val response = callBackend(endpoint, input)
            val json = JSONObject(response)
            val commandJson = json.optJSONObject("command") ?: json
            validateCommand(commandJson, originalText = input)
        }.getOrElse { error ->
            ParseResult.Failure(backendErrorMessage(error, backendUrl))
        }
    }

    private fun backendErrorMessage(error: Throwable, backendUrl: String): String {
        return when (error) {
            is ConnectException -> "AI server is not reachable at $backendUrl. Start the backend for development, or build the APK with your hosted backend URL."
            is UnknownHostException -> "AI server address cannot be found: $backendUrl."
            is SocketTimeoutException -> "AI server timed out at $backendUrl."
            else -> "AI server failed: ${error.message ?: "unknown error"}."
        }
    }

    private fun callBackend(endpoint: String, input: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        val body = JSONObject()
            .put("command", input)
            .put("toolContext", HybridFileCommandInterpreter.toolContext())
            .toString()

        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(body)
        }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseBody = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        if (status !in 200..299) {
            throw IllegalStateException("HTTP $status: ${responseBody.take(180)}")
        }
        return responseBody
    }

    private fun validateCommand(json: JSONObject, originalText: String): ParseResult {
        val action = when (json.optString("action")) {
            "move" -> FileAction.Move
            "copy" -> FileAction.Copy
            "rename" -> FileAction.Rename
            "delete" -> FileAction.Delete
            "create_folder" -> FileAction.CreateFolder
            "search" -> FileAction.Search
            else -> return ParseResult.Failure("Backend returned an unsupported action.")
        }

        val command = FileCommand(
            originalText = originalText,
            action = action,
            fileQuery = json.cleanNullable("fileQuery"),
            sourceHint = json.cleanNullable("sourceHint"),
            destinationHint = json.cleanNullable("destinationHint"),
            newName = json.cleanNullable("newName"),
            folderName = json.cleanNullable("folderName")
        )

        val missing = when (action) {
            FileAction.Move, FileAction.Copy -> command.fileQuery.isNullOrBlank() || command.destinationHint.isNullOrBlank()
            FileAction.Rename -> command.fileQuery.isNullOrBlank() || command.newName.isNullOrBlank()
            FileAction.Delete, FileAction.Search -> command.fileQuery.isNullOrBlank()
            FileAction.CreateFolder -> command.folderName.isNullOrBlank()
        }

        return if (missing) {
            ParseResult.Failure("Backend AI did not return the required fields for ${json.optString("action")}.")
        } else {
            ParseResult.Success(command)
        }
    }

    private fun JSONObject.cleanNullable(key: String): String? {
        if (isNull(key)) return null
        return optString(key)
            .trim()
            .trim('.', ',', ';', ':', '"', '\'', '`', ' ')
            .takeIf { it.isNotBlank() }
    }
}

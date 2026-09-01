package com.aislave.agent.transactions

import android.content.Context
import org.json.JSONObject

class FileMoveTransactionStore(context: Context) {
    private val preferences = context.getSharedPreferences("file_move_transactions", Context.MODE_PRIVATE)

    fun latest(): MoveTransaction? {
        val raw = preferences.getString(KEY_LATEST, null) ?: return null
        return runCatching { MoveTransaction.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun record(transaction: MoveTransaction) {
        preferences.edit()
            .putString(KEY_LATEST, transaction.toJson().toString())
            .apply()
    }

    private companion object {
        const val KEY_LATEST = "latest"
    }
}

data class MoveTransaction(
    val id: String,
    val fileName: String,
    val originalUri: String,
    val sourceFolderUri: String,
    val destinationUri: String,
    val destinationFolderUri: String,
    val createdAt: String,
    val undone: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("fileName", fileName)
            .put("originalUri", originalUri)
            .put("sourceFolderUri", sourceFolderUri)
            .put("destinationUri", destinationUri)
            .put("destinationFolderUri", destinationFolderUri)
            .put("createdAt", createdAt)
            .put("undone", undone)
    }

    companion object {
        fun fromJson(json: JSONObject): MoveTransaction {
            return MoveTransaction(
                id = json.getString("id"),
                fileName = json.getString("fileName"),
                originalUri = json.getString("originalUri"),
                sourceFolderUri = json.getString("sourceFolderUri"),
                destinationUri = json.getString("destinationUri"),
                destinationFolderUri = json.getString("destinationFolderUri"),
                createdAt = json.getString("createdAt"),
                undone = json.optBoolean("undone", false)
            )
        }
    }
}

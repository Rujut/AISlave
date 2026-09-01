package com.aislave.agent.agent

import android.net.Uri

enum class PlannedFileAction {
    Move,
    Copy,
    Rename,
    Delete,
    CreateFolder
}

data class AgentPlan(
    val title: String,
    val steps: List<String>,
    val toolName: String,
    val risk: String,
    val requiresAccess: List<String>,
    val canUndo: Boolean,
    val action: PlannedFileAction,
    val selectedFileUri: Uri? = null,
    val destinationFolderUri: Uri? = null,
    val targetFolderUri: Uri? = null,
    val newName: String? = null,
    val folderName: String? = null,
    val clearCommandAfterExecution: Boolean
)

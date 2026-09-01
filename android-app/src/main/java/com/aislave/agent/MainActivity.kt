package com.aislave.agent

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aislave.agent.agent.AgentPlan
import com.aislave.agent.agent.AiSettingsStore
import com.aislave.agent.agent.BackendFileCommandInterpreter
import com.aislave.agent.agent.FileAction
import com.aislave.agent.agent.FileCommand
import com.aislave.agent.agent.HybridFileCommandInterpreter
import com.aislave.agent.agent.LocalFileCommandInterpreter
import com.aislave.agent.agent.ParseResult
import com.aislave.agent.agent.PlannedFileAction
import com.aislave.agent.files.FileActionRepository
import com.aislave.agent.files.FileItem
import com.aislave.agent.files.FolderItem
import com.aislave.agent.transactions.FileMoveTransactionStore
import com.aislave.agent.transactions.MoveTransaction
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AISlaveTheme {
                AgentApp()
            }
        }
    }
}

@Composable
private fun AISlaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF145C4B),
            secondary = Color(0xFF725C16),
            tertiary = Color(0xFF365F91),
            background = Color(0xFFFAFAF7),
            surface = Color.White
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val limitedAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let { viewModel.setLimitedAccess(it) } }
    )
    val fullAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { viewModel.refreshFullAccessState() }
    )

    Scaffold(topBar = { TopAppBar(title = { Text("Android Action Agent") }) }) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Milestone 2", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (state.accessMode == AccessMode.NotAsked) {
                        "Choose storage access to start."
                    } else {
                        "Type a command, review the plan, then confirm execution."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (state.accessMode == AccessMode.NotAsked) {
                    AccessPanel(
                        onFullAccess = { fullAccessLauncher.launch(createFullAccessIntent()) },
                        onLimitedAccess = { limitedAccessLauncher.launch(null) },
                        onDenied = viewModel::denyAccess
                    )
                } else {
                    AccessStatus(accessMode = state.accessMode, scopeName = state.storageScopeName)
                }

                CommandPanel(
                    commandText = state.commandText,
                    canRun = state.canRunCommand,
                    onCommandChange = viewModel::setCommandText,
                    onRun = viewModel::runCommand
                )

                state.pendingPlan?.let { plan ->
                    PlanPanel(
                        plan = plan,
                        onConfirm = viewModel::confirmPlan,
                        onCancel = viewModel::cancelPlan
                    )
                }

                FileListPanel(
                    files = state.files,
                    selectedUri = state.selectedFileUri,
                    canUseSelection = state.canUseSelectedCandidate,
                    onSelect = viewModel::selectFile,
                    onUseSelection = viewModel::planSelectedCandidate
                )

                OutlinedButton(enabled = state.canUndo, onClick = viewModel::undoLastMove) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null)
                    Text("Undo")
                }

                StatusPanel(message = state.message, transaction = state.lastTransaction)
            }
        }
    }
}

private fun createFullAccessIntent(): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.aislave.agent"))
    }
}

@Composable
private fun AccessPanel(
    onFullAccess: () -> Unit,
    onLimitedAccess: () -> Unit,
    onDenied: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Storage permission", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onFullAccess) { Text("Full") }
                OutlinedButton(onClick = onLimitedAccess) { Text("Limited") }
                TextButton(onClick = onDenied) { Text("Denied") }
            }
        }
    }
}

@Composable
private fun AccessStatus(accessMode: AccessMode, scopeName: String?) {
    Text(
        text = when (accessMode) {
            AccessMode.Full -> "Access: Full (${scopeName ?: "Internal Storage"})"
            AccessMode.Limited -> "Access: Limited (${scopeName ?: "Selected folder"})"
            AccessMode.Denied -> "Access: Denied"
            AccessMode.NotAsked -> ""
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun CommandPanel(
    commandText: String,
    canRun: Boolean,
    onCommandChange: (String) -> Unit,
    onRun: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Command", style = MaterialTheme.typography.titleMedium)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = commandText,
                    onValueChange = onCommandChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 92.dp),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 5,
                    placeholder = { Text("Move keetu.ml from Documents to Rrr\nCopy marks.pdf to College\nRename old.txt to new.txt\nFind assignment in Documents") }
                )
                Button(
                    enabled = canRun,
                    onClick = onRun,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Text("Run")
                }
            }
        }
    }
}

@Composable
private fun PlanPanel(plan: AgentPlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F7F1))) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Plan", style = MaterialTheme.typography.titleMedium)
            Text(plan.title, fontWeight = FontWeight.SemiBold)
            plan.steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "Tool: ${plan.toolName} | Risk: ${plan.risk} | Undo: ${if (plan.canUndo) "Yes" else "No"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onConfirm) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Text("Confirm")
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun FileListPanel(
    files: List<FileItem>,
    selectedUri: Uri?,
    canUseSelection: Boolean,
    onSelect: (Uri) -> Unit,
    onUseSelection: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Matching source files", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty()) {
                Text("Run a command to show similar file matches.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(files, key = { it.uri.toString() }) { file ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedUri == file.uri, onClick = { onSelect(file.uri) })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "${file.displaySize} | ${file.path}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(enabled = canUseSelection, onClick = onUseSelection) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
                    Text("Use Selected")
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(message: String, transaction: MoveTransaction?) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                Text(text = message, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (transaction != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {}, enabled = false) {
                        Icon(Icons.Outlined.History, contentDescription = null)
                    }
                    Text(text = "Last transaction: ${transaction.fileName}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

enum class AccessMode {
    NotAsked,
    Full,
    Limited,
    Denied
}

data class MainUiState(
    val accessMode: AccessMode = AccessMode.NotAsked,
    val storageRootUri: Uri? = null,
    val storageScopeName: String? = null,
    val files: List<FileItem> = emptyList(),
    val selectedFileUri: Uri? = null,
    val pendingDestinationFolderUri: Uri? = null,
    val pendingDestinationName: String? = null,
    val pendingCommand: FileCommand? = null,
    val useBackendAi: Boolean = true,
    val backendUrl: String = AiSettingsStore.DEFAULT_BACKEND_URL,
    val commandText: String = "",
    val pendingPlan: AgentPlan? = null,
    val message: String = "Ready",
    val lastTransaction: MoveTransaction? = null
) {
    val canUndo: Boolean = lastTransaction?.undone == false
    val canRunCommand: Boolean = commandText.isNotBlank() &&
        accessMode != AccessMode.Denied &&
        storageRootUri != null
    val canUseSelectedCandidate: Boolean = selectedFileUri != null && pendingCommand != null
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FileActionRepository(application)
    private val transactionStore = FileMoveTransactionStore(application)
    private val aiSettingsStore = AiSettingsStore(application)
    private val commandInterpreter = HybridFileCommandInterpreter(
        local = LocalFileCommandInterpreter(),
        backend = BackendFileCommandInterpreter()
    )
    private val _state = MutableStateFlow(
        MainUiState(
            backendUrl = aiSettingsStore.backendUrl(),
            lastTransaction = transactionStore.latest()
        )
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        refreshFullAccessState()
    }

    fun refreshFullAccessState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            val internalStorageRoot = repository.internalStorageRoot()
            _state.value = _state.value.copy(
                accessMode = AccessMode.Full,
                storageRootUri = Uri.fromFile(internalStorageRoot),
                storageScopeName = "Internal Storage (${internalStorageRoot.path})",
                files = emptyList(),
                selectedFileUri = null,
                pendingDestinationFolderUri = null,
                pendingDestinationName = null,
                pendingCommand = null,
                pendingPlan = null,
                message = "Full storage access granted. Search root: ${internalStorageRoot.path}"
            )
        } else {
            _state.value = _state.value.copy(message = "Full access is not granted. Choose Full again or use Limited access.")
        }
    }

    fun setLimitedAccess(uri: Uri) {
        repository.persistFolderGrant(uri)
        val folderName = repository.folderName(uri)
        _state.value = _state.value.copy(
            accessMode = AccessMode.Limited,
            storageRootUri = uri,
            storageScopeName = folderName,
            files = emptyList(),
            selectedFileUri = null,
            pendingDestinationFolderUri = null,
            pendingDestinationName = null,
            pendingCommand = null,
            pendingPlan = null,
            message = "Limited access granted. Run a command to search inside $folderName."
        )
    }

    fun denyAccess() {
        _state.value = _state.value.copy(
            accessMode = AccessMode.Denied,
            storageRootUri = null,
            storageScopeName = null,
            files = emptyList(),
            selectedFileUri = null,
            pendingDestinationFolderUri = null,
            pendingDestinationName = null,
            pendingCommand = null,
            pendingPlan = null,
            message = "Storage access denied."
        )
    }

    fun setCommandText(text: String) {
        _state.value = _state.value.copy(
            commandText = text,
            files = emptyList(),
            selectedFileUri = null,
            pendingDestinationFolderUri = null,
            pendingDestinationName = null,
            pendingCommand = null,
            pendingPlan = null
        )
    }

    fun selectFile(uri: Uri) {
        _state.value = _state.value.copy(selectedFileUri = uri, pendingPlan = null)
    }

    fun runCommand() {
        val snapshot = _state.value
        val rootUri = snapshot.storageRootUri
        if (rootUri == null) {
            _state.value = snapshot.copy(message = "Choose Full, Limited, or Denied first.")
            return
        }
        _state.value = snapshot.copy(message = "Searching granted storage...")

        viewModelScope.launch {
            val backendUrl = if (snapshot.useBackendAi) snapshot.backendUrl else ""
            val modeLabel = if (backendUrl.isBlank()) "local parser" else "backend AI"
            _state.value = _state.value.copy(message = "Understanding command with $modeLabel...")
            when (val parsed = commandInterpreter.interpret(snapshot.commandText, backendUrl)) {
                is ParseResult.Failure -> _state.value = _state.value.copy(message = parsed.message)
                is ParseResult.Success -> handleParsedCommand(rootUri, parsed.command)
                is ParseResult.Chat -> _state.value = _state.value.copy(
                    files = emptyList(),
                    selectedFileUri = null,
                    pendingDestinationFolderUri = null,
                    pendingDestinationName = null,
                    pendingCommand = null,
                    pendingPlan = null,
                    message = parsed.message
                )
            }
        }
    }

    private suspend fun handleParsedCommand(rootUri: Uri, command: FileCommand) {
        when (command.action) {
            FileAction.CreateFolder -> planCreateFolder(rootUri, command)
            FileAction.Search -> searchOnly(rootUri, command)
            FileAction.Move,
            FileAction.Copy,
            FileAction.Rename,
            FileAction.Delete -> buildFilePlanOrCandidates(rootUri, command)
        }
    }

    private suspend fun searchOnly(rootUri: Uri, command: FileCommand) {
        val sourceRoot = resolveSourceRoot(rootUri, command) ?: return
        val resolvedFiles = withContext(Dispatchers.IO) {
            repository.listFilesRecursive(sourceRoot.uri, maxFiles = if (command.sourceHint != null) 10000 else 50000)
        }
        val matches = withContext(Dispatchers.IO) {
            repository.findFileMatches(resolvedFiles, command.fileQuery.orEmpty(), limit = 12)
        }
        _state.value = _state.value.copy(
            files = matches.map { it.file },
            selectedFileUri = null,
            pendingDestinationFolderUri = null,
            pendingDestinationName = null,
            pendingCommand = null,
            pendingPlan = null,
            message = if (matches.isEmpty()) {
                "No matching files found for \"${command.fileQuery}\". Scanned ${resolvedFiles.size} files in ${sourceRoot.name}."
            } else {
                "Found ${matches.size} relevant file match(es) in ${sourceRoot.name}. Scanned ${resolvedFiles.size} files."
            }
        )
    }

    private suspend fun planCreateFolder(rootUri: Uri, command: FileCommand) {
        val parentFolder = withContext(Dispatchers.IO) {
            if (command.destinationHint.isNullOrBlank()) {
                FolderItem(rootUri, repository.folderName(rootUri), repository.folderName(rootUri), score = 200)
            } else {
                repository.findBestFolderMatch(rootUri, command.destinationHint)
            }
        }
        if (parentFolder == null) {
            _state.value = _state.value.copy(message = "I could not find parent folder \"${command.destinationHint}\".")
            return
        }
        _state.value = _state.value.copy(
            files = emptyList(),
            selectedFileUri = null,
            pendingDestinationFolderUri = null,
            pendingDestinationName = null,
            pendingCommand = null,
            pendingPlan = createFolderPlan(
                parentFolder = parentFolder,
                folderName = command.folderName.orEmpty()
            ),
            message = "Plan ready to create folder."
        )
    }

    private suspend fun buildFilePlanOrCandidates(rootUri: Uri, command: FileCommand) {
        val sourceRoot = resolveSourceRoot(rootUri, command) ?: return
        val destinationFolder = if (command.action == FileAction.Move || command.action == FileAction.Copy) {
            withContext(Dispatchers.IO) {
                command.destinationHint?.let { repository.findBestFolderMatch(rootUri, it) }
            }
        } else {
            null
        }
        if ((command.action == FileAction.Move || command.action == FileAction.Copy) && destinationFolder == null) {
            _state.value = _state.value.copy(
                files = emptyList(),
                selectedFileUri = null,
                pendingCommand = null,
                message = "I could not find destination folder \"${command.destinationHint}\"."
            )
            return
        }

        val resolvedFiles = withContext(Dispatchers.IO) {
            repository.listFilesRecursive(sourceRoot.uri, maxFiles = if (command.sourceHint != null) 10000 else 50000)
        }
        val matches = withContext(Dispatchers.IO) {
            repository.findFileMatches(resolvedFiles, command.fileQuery.orEmpty(), limit = 8)
        }
        if (matches.isEmpty()) {
            _state.value = _state.value.copy(
                files = emptyList(),
                selectedFileUri = null,
                pendingCommand = null,
                message = "No similar source files found for \"${command.fileQuery}\". Scanned ${resolvedFiles.size} files in ${sourceRoot.name}."
            )
            return
        }

        val bestScore = matches.first().score
        val topMatches = matches.filter { it.score == bestScore }
        if (topMatches.size == 1) {
            val file = topMatches.first().file
            _state.value = _state.value.copy(
                files = listOf(file),
                selectedFileUri = file.uri,
                pendingDestinationFolderUri = destinationFolder?.uri,
                pendingDestinationName = destinationFolder?.name,
                pendingCommand = command,
                pendingPlan = createPlanForCommand(file, destinationFolder, command),
                message = "Unique match found. Plan ready."
            )
        } else {
            _state.value = _state.value.copy(
                files = topMatches.map { it.file },
                selectedFileUri = null,
                pendingDestinationFolderUri = destinationFolder?.uri,
                pendingDestinationName = destinationFolder?.name,
                pendingCommand = command,
                pendingPlan = null,
                message = "Multiple similar files found. Select the correct source file."
            )
        }
    }

    private suspend fun resolveSourceRoot(rootUri: Uri, command: FileCommand): FolderItem? {
        val sourceRoot = withContext(Dispatchers.IO) {
            if (command.sourceHint.isNullOrBlank()) {
                FolderItem(rootUri, repository.folderName(rootUri), repository.folderName(rootUri), score = 200)
            } else {
                repository.findBestFolderMatch(rootUri, command.sourceHint)
            }
        }
        if (!command.sourceHint.isNullOrBlank() && sourceRoot == null) {
            _state.value = _state.value.copy(
                files = emptyList(),
                selectedFileUri = null,
                pendingCommand = null,
                message = "I could not find source folder \"${command.sourceHint}\"."
            )
            return null
        }
        return sourceRoot
    }

    fun planSelectedCandidate() {
        val snapshot = _state.value
        val file = snapshot.files.firstOrNull { it.uri == snapshot.selectedFileUri }
        val command = snapshot.pendingCommand
        if (file == null || command == null) {
            _state.value = snapshot.copy(message = "Select one source file first.")
            return
        }
        _state.value = snapshot.copy(
            pendingPlan = createPlanForCommand(
                file = file,
                destinationFolder = snapshot.pendingDestinationFolderUri?.let { uri ->
                    FolderItem(uri, snapshot.pendingDestinationName ?: "Destination", snapshot.pendingDestinationName ?: "Destination")
                },
                command = command
            ),
            message = "Plan ready for selected source file."
        )
    }

    fun confirmPlan() {
        val snapshot = _state.value
        val plan = snapshot.pendingPlan ?: return
        val file = plan.selectedFileUri?.let { uri -> snapshot.files.firstOrNull { it.uri == uri } }
        when (plan.action) {
            PlannedFileAction.Move -> {
                if (file == null || plan.destinationFolderUri == null) {
                    _state.value = snapshot.copy(pendingPlan = null, message = "Planned move target is no longer available.")
                    return
                }
                moveFile(file, plan.destinationFolderUri, clearCommand = plan.clearCommandAfterExecution)
            }
            PlannedFileAction.Copy -> executeSimpleFileTool("Copied and verified: ${file?.name}", plan.clearCommandAfterExecution) {
                if (file == null || plan.destinationFolderUri == null) {
                    repositoryFailure("Planned copy target is no longer available.")
                } else {
                    repository.copyFile(file, plan.destinationFolderUri)
                }
            }
            PlannedFileAction.Rename -> executeSimpleFileTool("Renamed and verified: ${plan.newName}", plan.clearCommandAfterExecution) {
                if (plan.selectedFileUri == null || plan.newName.isNullOrBlank()) {
                    repositoryFailure("Planned rename target is no longer available.")
                } else {
                    repository.renameFile(plan.selectedFileUri, plan.newName)
                }
            }
            PlannedFileAction.Delete -> executeSimpleFileTool("Deleted and verified: ${file?.name}", plan.clearCommandAfterExecution) {
                if (plan.selectedFileUri == null) {
                    repositoryFailure("Planned delete target is no longer available.")
                } else {
                    repository.deleteFile(plan.selectedFileUri)
                }
            }
            PlannedFileAction.CreateFolder -> executeSimpleFileTool("Folder created and verified: ${plan.folderName}", plan.clearCommandAfterExecution) {
                if (plan.targetFolderUri == null || plan.folderName.isNullOrBlank()) {
                    repositoryFailure("Planned parent folder is no longer available.")
                } else {
                    repository.createFolder(plan.targetFolderUri, plan.folderName)
                }
            }
        }
    }

    fun cancelPlan() {
        _state.value = _state.value.copy(pendingPlan = null, message = "Plan cancelled.")
    }

    private fun createPlanForCommand(file: FileItem, destinationFolder: FolderItem?, command: FileCommand): AgentPlan {
        return when (command.action) {
            FileAction.Move -> AgentPlan(
                title = "Move ${file.name} to ${destinationFolder?.name ?: "destination"}",
                steps = listOf(
                    "Search the resolved source area for files similar to \"${command.fileQuery}\".",
                    "Resolved source file: ${file.path}.",
                    "Resolved destination folder: ${destinationFolder?.name ?: "destination"}.",
                    "Create a copy in the destination folder.",
                    "Delete the original only after the copy succeeds.",
                    "Verify destination exists and source is absent.",
                    "Record an undoable transaction."
                ),
                toolName = "move_file",
                risk = "Medium",
                requiresAccess = listOf("Granted storage read/write"),
                canUndo = true,
                action = PlannedFileAction.Move,
                selectedFileUri = file.uri,
                destinationFolderUri = destinationFolder?.uri,
                clearCommandAfterExecution = true
            )
            FileAction.Copy -> AgentPlan(
                title = "Copy ${file.name} to ${destinationFolder?.name ?: "destination"}",
                steps = listOf(
                    "Search the resolved source area for files similar to \"${command.fileQuery}\".",
                    "Resolved source file: ${file.path}.",
                    "Resolved destination folder: ${destinationFolder?.name ?: "destination"}.",
                    "Create a duplicate file in the destination folder.",
                    "Verify the copy exists and keep the original unchanged."
                ),
                toolName = "copy_file",
                risk = "Low",
                requiresAccess = listOf("Granted storage read/write"),
                canUndo = false,
                action = PlannedFileAction.Copy,
                selectedFileUri = file.uri,
                destinationFolderUri = destinationFolder?.uri,
                clearCommandAfterExecution = true
            )
            FileAction.Rename -> AgentPlan(
                title = "Rename ${file.name} to ${command.newName}",
                steps = listOf(
                    "Search the resolved source area for files similar to \"${command.fileQuery}\".",
                    "Resolved source file: ${file.path}.",
                    "Rename the file to ${command.newName}.",
                    "Verify the renamed file exists."
                ),
                toolName = "rename_file",
                risk = "Medium",
                requiresAccess = listOf("Granted storage write"),
                canUndo = false,
                action = PlannedFileAction.Rename,
                selectedFileUri = file.uri,
                newName = command.newName,
                clearCommandAfterExecution = true
            )
            FileAction.Delete -> AgentPlan(
                title = "Delete ${file.name}",
                steps = listOf(
                    "Search the resolved source area for files similar to \"${command.fileQuery}\".",
                    "Resolved source file: ${file.path}.",
                    "Delete the selected file.",
                    "Verify the file is absent."
                ),
                toolName = "delete_file",
                risk = "High",
                requiresAccess = listOf("Granted storage write"),
                canUndo = false,
                action = PlannedFileAction.Delete,
                selectedFileUri = file.uri,
                clearCommandAfterExecution = true
            )
            else -> error("Search and create-folder do not use source-file plans.")
        }
    }

    private fun createFolderPlan(parentFolder: FolderItem, folderName: String): AgentPlan {
        return AgentPlan(
            title = "Create folder $folderName in ${parentFolder.name}",
            steps = listOf(
                "Resolve parent folder: ${parentFolder.path}.",
                "Create a new folder named $folderName.",
                "Verify the folder exists."
            ),
            toolName = "create_folder",
            risk = "Low",
            requiresAccess = listOf("Granted storage write"),
            canUndo = false,
            action = PlannedFileAction.CreateFolder,
            targetFolderUri = parentFolder.uri,
            folderName = folderName,
            clearCommandAfterExecution = true
        )
    }

    private fun executeSimpleFileTool(successMessage: String, clearCommand: Boolean, block: suspend () -> com.aislave.agent.files.FileToolResult) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            if (result.isSuccess) {
                _state.value = _state.value.copy(
                    files = emptyList(),
                    selectedFileUri = null,
                    pendingDestinationFolderUri = null,
                    pendingDestinationName = null,
                    pendingCommand = null,
                    commandText = if (clearCommand) "" else _state.value.commandText,
                    pendingPlan = null,
                    message = successMessage
                )
            } else {
                _state.value = _state.value.copy(message = result.message)
            }
        }
    }

    private fun repositoryFailure(message: String): com.aislave.agent.files.FileToolResult {
        return com.aislave.agent.files.FileToolResult.failure(message)
    }

    private fun moveFile(selectedFile: FileItem, destinationFolderUri: Uri, clearCommand: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.moveFile(selectedFile, destinationFolderUri)
            }
            if (result.isSuccess) {
                val verifiedDestinationUri = result.destinationFileUri ?: return@launch
                val transaction = MoveTransaction(
                    id = Instant.now().toEpochMilli().toString(),
                    fileName = selectedFile.name,
                    originalUri = selectedFile.uri.toString(),
                    sourceFolderUri = selectedFile.parentFolderUri.toString(),
                    destinationUri = verifiedDestinationUri.toString(),
                    destinationFolderUri = destinationFolderUri.toString(),
                    createdAt = Instant.now().toString()
                )
                transactionStore.record(transaction)
                _state.value = _state.value.copy(
                    files = emptyList(),
                    selectedFileUri = null,
                    pendingDestinationFolderUri = null,
                    pendingDestinationName = null,
                    pendingCommand = null,
                    commandText = if (clearCommand) "" else _state.value.commandText,
                    pendingPlan = null,
                    lastTransaction = transaction,
                    message = "Moved and verified: ${selectedFile.name}"
                )
            } else {
                _state.value = _state.value.copy(message = result.message)
            }
        }
    }

    fun undoLastMove() {
        val transaction = _state.value.lastTransaction ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.undoMove(transaction)
            }
            if (result.isSuccess) {
                val updated = transaction.copy(undone = true)
                transactionStore.record(updated)
                _state.value = _state.value.copy(
                    lastTransaction = updated,
                    message = "Undo complete: ${transaction.fileName}"
                )
            } else {
                _state.value = _state.value.copy(message = result.message)
            }
        }
    }
}

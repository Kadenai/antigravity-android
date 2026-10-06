package com.example.antigravity.ui.chat

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.antigravity.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.ConnectionState
import com.example.antigravity.data.settings.SettingsRepository
import com.example.antigravity.data.settings.TerminalSettings
import com.example.antigravity.data.terminal.TermuxLauncher
import com.example.antigravity.ui.chat.components.ChatBubble
import com.example.antigravity.ui.chat.components.ChatInputBar
import com.example.antigravity.ui.chat.components.FilePickerDialog
import com.example.antigravity.ui.chat.components.RawTerminalDialog
import com.example.antigravity.ui.chat.components.SettingsDialog
import com.example.antigravity.ui.chat.components.StatusHeader

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = run {
        val context = LocalContext.current.applicationContext
        viewModel(
            factory = remember {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        val prefs = context.getSharedPreferences("antigravity_settings", Context.MODE_PRIVATE)
                        val settingsRepo = SettingsRepository(prefs)
                        return ChatViewModel(settingsRepository = settingsRepo, terminalLauncher = TermuxLauncher(context)) as T
                    }
                }
            }
        )
    }
) {
    val messages by viewModel.messages.collectAsState()
    val status by viewModel.status.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val rawTerminalLogs by viewModel.rawTerminalLogs.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val files by viewModel.files.collectAsState()
    val draftFiles by viewModel.draftFiles.collectAsState()
    val editingMessageId by viewModel.editingMessageId.collectAsState()
    val fileSelectionBusy by viewModel.fileSelectionBusy.collectAsState()
    val fileError by viewModel.fileError.collectAsState()
    val busy = messages.any { it.isStreaming } || status.agyState == AGYState.RUNNING ||
        status.connectionState in listOf(ConnectionState.PREPARING, ConnectionState.CONNECTING)
    val connected = status.connectionState == ConnectionState.CONNECTED && status.agyState != AGYState.ERROR
    var showFilePicker by remember { mutableStateOf(false) }
    LaunchedEffect(files) { if (files?.selected?.isNotEmpty() == true) showFilePicker = false }
    val context = LocalContext.current
    var pendingSettings by remember { mutableStateOf<TerminalSettings?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingSettings?.let { settingsToSave -> viewModel.saveSettings(settingsToSave) }
        pendingSettings = null
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resume() }

    var showSettingsDialog by remember { mutableStateOf(settings.workspaces.isEmpty()) }
    var showRawTerminalDialog by remember { mutableStateOf(false) }
    var selectedRawLogs by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    // Auto-scroll suave quando nova mensagem é adicionada
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Auto-scroll instantâneo durante o streaming apenas se o usuário já estiver próximo do fim
    LaunchedEffect(messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= messages.size - 2) {
                listState.scrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            StatusHeader(
                status = status,
                onOpenSettings = { showSettingsDialog = true },
                onOpenRawTerminal = {
                    selectedRawLogs = null
                    showRawTerminalDialog = true
                },
                onReconnect = { viewModel.reconnect() },
                onNewConversation = { viewModel.clearChat() }
            )
        },
        bottomBar = {
            ChatInputBar(
                inputText = inputText,
                onInputChange = { viewModel.onInputChange(it) },
                onSend = { viewModel.sendPrompt() },
                onInterrupt = { viewModel.sendInterrupt() },
                isRunning = busy,
                isConnected = connected,
                files = draftFiles,
                isEditing = editingMessageId != null,
                fileSelectionBusy = fileSelectionBusy,
                onAttachFile = { showFilePicker = true; viewModel.listFiles(settings.workspace.ifEmpty { "~" }) },
                onRemoveFile = viewModel::removeDraftFile,
                onCancelEdit = viewModel::cancelEdit,
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding()
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_antigravity_logo),
                        contentDescription = "Antigravity",
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Antigravity",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Assistente de código e agente autônomo.\nEnvie uma mensagem para começar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        onEdit = if (message.isUser && connected && !busy && !fileSelectionBusy) ({ viewModel.editMessage(message.id) }) else null,
                        onViewRawLogs = { logs ->
                            selectedRawLogs = logs
                            showRawTerminalDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            settings = settings,
            directories = workspaces,
            busy = status.agyState == AGYState.RUNNING,
            connected = status.connectionState == ConnectionState.CONNECTED && status.agyState != AGYState.ERROR,
            onBrowse = viewModel::listWorkspaces,
            onValidate = { viewModel.validateWorkspace(); showSettingsDialog = false },
            onOpenTermux = {
                context.packageManager.getLaunchIntentForPackage("com.termux")?.let { context.startActivity(it) }
                    ?: context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/termux/termux-app#installation".toUri()))
            },
            onOpenPermissions = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
            onDismiss = { showSettingsDialog = false },
            onSaveSettings = { updated ->
                if (updated.autoStart) {
                    pendingSettings = updated
                    permissionLauncher.launch(TermuxLauncher.PERMISSION)
                } else viewModel.saveSettings(updated)
                showSettingsDialog = false
            }
        )
    }

    if (showFilePicker) {
        FilePickerDialog(listing = files, initialPath = settings.workspace.ifEmpty { "~" }, busy = fileSelectionBusy,
            error = fileError, onOpen = viewModel::listFiles, onDismiss = { showFilePicker = false })
    }

    if (showRawTerminalDialog) {
        RawTerminalDialog(
            logs = selectedRawLogs ?: rawTerminalLogs,
            onDismiss = { showRawTerminalDialog = false },
            onClearLogs = {
                viewModel.clearRawLogs()
                selectedRawLogs = ""
            }
        )
    }
}

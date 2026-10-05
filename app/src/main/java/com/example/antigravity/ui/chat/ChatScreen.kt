package com.example.antigravity.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.ConnectionState
import com.example.antigravity.ui.chat.components.ChatBubble
import com.example.antigravity.ui.chat.components.ChatInputBar
import com.example.antigravity.ui.chat.components.RawTerminalDialog
import com.example.antigravity.ui.chat.components.SettingsDialog
import com.example.antigravity.ui.chat.components.StatusHeader

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val status by viewModel.status.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val rawTerminalLogs by viewModel.rawTerminalLogs.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRawTerminalDialog by remember { mutableStateOf(false) }
    var selectedRawLogs by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    // Auto-scroll para a última mensagem
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            StatusHeader(
                status = status,
                onOpenSettings = { showSettingsDialog = true },
                onOpenRawTerminal = {
                    selectedRawLogs = rawTerminalLogs
                    showRawTerminalDialog = true
                },
                onReconnect = { viewModel.reconnect() }
            )
        },
        bottomBar = {
            ChatInputBar(
                inputText = inputText,
                onInputChange = { viewModel.onInputChange(it) },
                onSend = { viewModel.sendPrompt() },
                onInterrupt = { viewModel.sendInterrupt() },
                isRunning = status.agyState == AGYState.RUNNING,
                isConnected = status.connectionState == ConnectionState.CONNECTED,
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding()
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            items(messages, key = { it.id }) { message ->
                ChatBubble(
                    message = message,
                    onViewRawLogs = { logs ->
                        selectedRawLogs = logs
                        showRawTerminalDialog = true
                    }
                )
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentUrl = status.serverUrl,
            onDismiss = { showSettingsDialog = false },
            onSaveUrl = { newUrl ->
                viewModel.updateServerUrl(newUrl)
                showSettingsDialog = false
            }
        )
    }

    if (showRawTerminalDialog) {
        RawTerminalDialog(
            logs = selectedRawLogs,
            onDismiss = { showRawTerminalDialog = false },
            onClearLogs = {
                viewModel.clearRawLogs()
                selectedRawLogs = ""
            }
        )
    }
}

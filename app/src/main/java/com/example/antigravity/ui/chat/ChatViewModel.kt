package com.example.antigravity.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.antigravity.data.client.AgyClient
import com.example.antigravity.data.client.AgyClientFactory
import com.example.antigravity.data.client.BridgeEvent
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.ChatMessage
import com.example.antigravity.data.network.AnsiCleaner
import com.example.antigravity.data.network.WebSocketBridgeClient
import com.example.antigravity.data.settings.ClientMode
import com.example.antigravity.data.settings.SettingsRepository
import com.example.antigravity.data.workspace.WorkspaceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    initialClient: AgyClient? = null,
    private val clientFactory: AgyClientFactory? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val workspaceManager: WorkspaceManager? = null
) : ViewModel() {

    private val _clientMode = MutableStateFlow(
        settingsRepository?.getMode() ?: ClientMode.NATIVE
    )
    val clientMode: StateFlow<ClientMode> = _clientMode.asStateFlow()

    private val _workspacePath = MutableStateFlow(
        workspaceManager?.getWorkspacePath() ?: ""
    )
    val workspacePath: StateFlow<String> = _workspacePath.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                content = if ((settingsRepository?.getMode() ?: ClientMode.NATIVE) == ClientMode.NATIVE) {
                    "👋 **Bem-vindo ao Antigravity Android!**\n\nModo Nativo Standalone ativo. O Antigravity CLI executará diretamente no seu dispositivo."
                } else {
                    "👋 **Bem-vindo ao Antigravity Android!**\n\nConecte-se à ponte no Termux para começar. Execute `python agy_bridge.py` no seu Termux."
                },
                isUser = false
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _rawTerminalLogs = MutableStateFlow("")
    val rawTerminalLogs: StateFlow<String> = _rawTerminalLogs.asStateFlow()

    private var activeClient: AgyClient? = null
    private var statusJob: Job? = null
    private var eventsJob: Job? = null
    private var activeAssistantMsgId: String? = null

    init {
        val client = initialClient
            ?: clientFactory?.createClient(_clientMode.value)
            ?: WebSocketBridgeClient()

        activeClient = client
        attachClient(client)

        viewModelScope.launch {
            client.start()
        }
    }

    private fun attachClient(client: AgyClient) {
        statusJob?.cancel()
        eventsJob?.cancel()

        statusJob = viewModelScope.launch {
            client.status.collect { newStatus ->
                _status.value = newStatus
            }
        }

        eventsJob = viewModelScope.launch {
            client.incomingEvents.collect { event ->
                handleBridgeEvent(event)
            }
        }
    }

    fun switchClientMode(mode: ClientMode) {
        _clientMode.value = mode
        settingsRepository?.setMode(mode)

        viewModelScope.launch {
            activeClient?.stop()
            val newClient = clientFactory?.createClient(mode) ?: WebSocketBridgeClient()
            activeClient = newClient
            attachClient(newClient)
            newClient.start()
        }
    }

    fun updateWorkspacePath(path: String?) {
        workspaceManager?.setCustomWorkspacePath(path)
        settingsRepository?.setWorkspacePath(path)
        _workspacePath.value = workspaceManager?.getWorkspacePath() ?: (path ?: "")
    }

    private fun handleBridgeEvent(event: BridgeEvent) {
        when (event) {
            is BridgeEvent.Output -> {
                val rawChunk = event.chunk
                if (rawChunk.isEmpty()) return

                _rawTerminalLogs.update { it + rawChunk }

                val cleanChunk = AnsiCleaner.clean(rawChunk)
                if (cleanChunk.isEmpty()) return

                val targetId = activeAssistantMsgId
                if (targetId != null) {
                    _messages.update { currentList ->
                        currentList.map { message ->
                            if (message.id == targetId) {
                                message.copy(
                                    content = message.content + cleanChunk,
                                    rawLogs = message.rawLogs + rawChunk
                                )
                            } else {
                                message
                            }
                        }
                    }
                }
            }
            is BridgeEvent.TurnComplete -> {
                finishActiveStreaming()
            }
            is BridgeEvent.Status -> {
                if (event.state == AGYState.IDLE) {
                    finishActiveStreaming()
                }
            }
            is BridgeEvent.Error -> {
                val targetId = activeAssistantMsgId
                if (targetId != null) {
                    _messages.update { currentList ->
                        currentList.map { message ->
                            if (message.id == targetId) {
                                message.copy(
                                    content = (message.content + "\n⚠️ Erro: ${event.message}").trimStart(),
                                    isStreaming = false
                                )
                            } else message
                        }
                    }
                    activeAssistantMsgId = null
                } else {
                    finishActiveStreaming()
                }
            }
        }
    }

    fun onInputChange(newText: String) {
        _inputText.value = newText
    }

    fun sendPrompt() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        finishActiveStreaming()

        val userMsg = ChatMessage(
            content = text,
            isUser = true
        )

        val assistantMsgId = UUID.randomUUID().toString()
        val assistantMsg = ChatMessage(
            id = assistantMsgId,
            content = "",
            isUser = false,
            isStreaming = true
        )
        activeAssistantMsgId = assistantMsgId

        _messages.update { it + userMsg + assistantMsg }
        _inputText.value = ""

        val client = activeClient ?: return
        viewModelScope.launch {
            val sent = client.sendPrompt(text)
            if (!sent) {
                _messages.update { currentList ->
                    currentList.map { message ->
                        if (message.id == assistantMsgId) {
                            message.copy(
                                content = "⚠️ Falha ao enviar para o Antigravity. Verifique se o serviço está em execução.",
                                isStreaming = false
                            )
                        } else message
                    }
                }
                activeAssistantMsgId = null
            }
        }
    }

    fun sendInterrupt() {
        val client = activeClient
        viewModelScope.launch {
            client?.interrupt()
        }
        finishActiveStreaming()
    }

    fun reconnect() {
        val client = activeClient
        viewModelScope.launch {
            client?.start()
        }
    }

    fun updateServerUrl(newUrl: String) {
        settingsRepository?.setServerUrl(newUrl)
        (activeClient as? WebSocketBridgeClient)?.updateServerUrl(newUrl)
    }

    fun clearRawLogs() {
        _rawTerminalLogs.value = ""
    }

    fun clearChat() {
        finishActiveStreaming()
        _messages.value = emptyList()
    }

    private fun finishActiveStreaming() {
        val targetId = activeAssistantMsgId ?: return
        _messages.update { currentList ->
            currentList.map { message ->
                if (message.id == targetId) {
                    message.copy(isStreaming = false)
                } else message
            }
        }
        activeAssistantMsgId = null
    }

    override fun onCleared() {
        super.onCleared()
        statusJob?.cancel()
        eventsJob?.cancel()
        val client = activeClient
        viewModelScope.launch {
            client?.stop()
        }
    }
}

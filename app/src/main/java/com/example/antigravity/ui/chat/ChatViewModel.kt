package com.example.antigravity.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.antigravity.data.client.AgyClient
import com.example.antigravity.data.client.BridgeEvent
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.ChatMessage
import com.example.antigravity.data.network.AnsiCleaner
import com.example.antigravity.data.network.WebSocketBridgeClient
import com.example.antigravity.data.settings.SettingsRepository
import com.example.antigravity.data.settings.TerminalSettings
import com.example.antigravity.data.model.ClientConfigureMessage
import com.example.antigravity.data.model.ConnectionState
import com.example.antigravity.data.model.PromptHistory
import com.example.antigravity.data.terminal.TermuxLauncher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    initialClient: AgyClient? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val terminalLauncher: TermuxLauncher? = null
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                content = "👋 **Antigravity Android**\n\nConfigure o Termux e selecione seu workspace em ⚙️. Após instalar Python e autenticar o AGY no terminal, o app pode iniciar e reutilizar a sessão automaticamente. O botão de console mostra os logs dessa sessão.",
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
    private val _settings = MutableStateFlow(settingsRepository?.getTerminalSettings() ?: TerminalSettings())
    val settings = _settings.asStateFlow()
    private val _workspaces = MutableStateFlow<BridgeEvent.Workspaces?>(null)
    val workspaces = _workspaces.asStateFlow()
    private val _files = MutableStateFlow<BridgeEvent.Files?>(null)
    val files = _files.asStateFlow()
    private val _fileSelectionBusy = MutableStateFlow(false)
    val fileSelectionBusy = _fileSelectionBusy.asStateFlow()
    private val _fileError = MutableStateFlow("")
    val fileError = _fileError.asStateFlow()
    private val _draftFiles = MutableStateFlow<List<String>>(emptyList())
    val draftFiles = _draftFiles.asStateFlow()
    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId = _editingMessageId.asStateFlow()
    private var savedDraft: Pair<String, List<String>>? = null

    private var activeClient: AgyClient? = null
    private var statusJob: Job? = null
    private var eventsJob: Job? = null
    private var activeAssistantMsgId: String? = null

    init {
        val client = initialClient ?: createClient()

        activeClient = client
        attachClient(client)

        viewModelScope.launch {
            client.start()
        }
        if (terminalLauncher != null) viewModelScope.launch {
            TermuxLauncher.failures.collect { diagnostic ->
                (activeClient as? WebSocketBridgeClient)?.disconnect()
                (activeClient as? WebSocketBridgeClient)?.reportFailure(diagnostic)
            }
        }
    }

    private fun createClient(): WebSocketBridgeClient {
        val settings = _settings.value
        return WebSocketBridgeClient(settings.serverUrl,
            configuration = ClientConfigureMessage(workspace = settings.workspace, automatic = settings.automatic),
            prepareTerminal = if (settings.autoStart) ({
                checkNotNull(terminalLauncher) { "Inicialização do Termux indisponível" }.launch()
            }) else null)
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
                        val lastIdx = currentList.lastIndex
                        if (lastIdx >= 0 && currentList[lastIdx].id == targetId) {
                            val last = currentList[lastIdx]
                            val mutable = currentList.toMutableList()
                            mutable[lastIdx] = last.copy(
                                content = last.content + cleanChunk,
                                rawLogs = last.rawLogs + rawChunk
                            )
                            mutable
                        } else {
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
                } else {
                    val streaming = _status.value.agyState == AGYState.RUNNING
                    val message = ChatMessage(content = cleanChunk, rawLogs = rawChunk, isUser = false, isStreaming = streaming)
                    if (streaming) activeAssistantMsgId = message.id
                    _messages.update { it + message }
                }
            }
            is BridgeEvent.TurnComplete -> {
                finishActiveStreaming()
            }
            is BridgeEvent.Status -> {
                if (event.message.isNotEmpty()) _rawTerminalLogs.update { it + "\n[${event.state}] ${event.message}\n" }
            }
            is BridgeEvent.Workspaces -> _workspaces.value = event
            is BridgeEvent.Files -> {
                _fileSelectionBusy.value = false
                _files.value = event
                if (event.selected.isNotEmpty()) _draftFiles.update { (it + event.selected).distinct() }
            }
            is BridgeEvent.Session -> {
                if (event.changed) {
                    finishActiveStreaming()
                    _editingMessageId.value = null
                    savedDraft = null
                    _inputText.value = ""
                    _draftFiles.value = emptyList()
                    _files.value = null
                    _fileSelectionBusy.value = false
                    _fileError.value = ""
                    _messages.value = listOf(ChatMessage(content = "Workspace: `${event.workspace}`\nModo ${if (event.automatic) "automático" else "accept-edits"}. Nova conversa pronta.", isUser = false))
                }
                val updated = _settings.value.copy(workspace = event.workspace,
                    workspaces = (_settings.value.workspaces + event.workspace).distinct())
                _settings.value = updated
                settingsRepository?.saveTerminalSettings(updated)
            }
            is BridgeEvent.Error -> {
                if (_fileSelectionBusy.value) {
                    _fileError.value = event.message
                    _fileSelectionBusy.value = false
                }
                val diagnostic = "⚠️ Erro: ${event.message}"
                _rawTerminalLogs.update { it + "\n$diagnostic\n" }
                val targetId = activeAssistantMsgId
                if (targetId != null) {
                    _messages.update { currentList ->
                        currentList.map { message ->
                            if (message.id == targetId) {
                                message.copy(
                                    content = (message.content + "\n$diagnostic").trimStart(),
                                    rawLogs = message.rawLogs + "\n$diagnostic\n",
                                    isStreaming = false
                                )
                            } else message
                        }
                    }
                    activeAssistantMsgId = null
                } else {
                    _messages.update {
                        if (it.lastOrNull()?.content == diagnostic) it
                        else it + ChatMessage(content = diagnostic, rawLogs = diagnostic, isUser = false)
                    }
                }
            }
        }
    }

    fun onInputChange(newText: String) {
        _inputText.value = newText
    }

    fun editMessage(id: String) {
        if (_fileSelectionBusy.value || activeAssistantMsgId != null || _status.value.agyState == AGYState.RUNNING) return
        val message = _messages.value.firstOrNull { it.id == id && it.isUser } ?: return
        if (_editingMessageId.value == null) savedDraft = _inputText.value to _draftFiles.value
        _editingMessageId.value = id
        _inputText.value = message.content
        _draftFiles.value = message.files
    }

    fun cancelEdit() {
        if (_fileSelectionBusy.value) return
        savedDraft?.let { (text, files) -> _inputText.value = text; _draftFiles.value = files }
        savedDraft = null
        _editingMessageId.value = null
    }

    fun removeDraftFile(path: String) { _draftFiles.update { it - path } }

    fun listFiles(path: String) {
        if (_fileSelectionBusy.value || activeAssistantMsgId != null || _status.value.agyState == AGYState.RUNNING) return
        _fileSelectionBusy.value = true
        _fileError.value = ""
        if ((activeClient as? WebSocketBridgeClient)?.listFiles(path) != true) {
            handleBridgeEvent(BridgeEvent.Error("Conecte a ponte para selecionar arquivos acessíveis ao terminal."))
        }
    }

    fun sendPrompt() {
        val text = _inputText.value
        if (_fileSelectionBusy.value || (text.isBlank() && _draftFiles.value.isEmpty()) || activeAssistantMsgId != null || _status.value.agyState == AGYState.RUNNING) return

        val previousMessages = _messages.value
        val editId = _editingMessageId.value
        val editIndex = previousMessages.indexOfFirst { it.id == editId && it.isUser }
        if (editId != null && editIndex < 0) return
        val prefix = if (editIndex >= 0) previousMessages.take(editIndex) else previousMessages
        val firstUser = prefix.indexOfFirst { it.isUser }
        val history = if (editIndex >= 0) prefix.drop(if (firstUser < 0) prefix.size else firstUser).map {
            PromptHistory(if (it.isUser) "user" else "assistant", it.prompt())
        } else null

        val userMsg = ChatMessage(
            id = editId ?: UUID.randomUUID().toString(),
            content = text,
            isUser = true,
            files = _draftFiles.value
        )

        val assistantMsgId = UUID.randomUUID().toString()
        val assistantMsg = ChatMessage(
            id = assistantMsgId,
            content = "",
            isUser = false,
            isStreaming = true
        )
        activeAssistantMsgId = assistantMsgId

        _messages.value = prefix + userMsg + assistantMsg
        _inputText.value = ""
        _draftFiles.value = emptyList()
        _editingMessageId.value = null

        val client = activeClient ?: return
        viewModelScope.launch {
            val sent = client.sendPrompt(userMsg.prompt(), history)
            if (!sent) {
                if (editId != null) {
                    _messages.value = previousMessages
                    _inputText.value = text
                    _draftFiles.value = userMsg.files
                    _editingMessageId.value = editId
                    activeAssistantMsgId = null
                    handleBridgeEvent(BridgeEvent.Error("Falha ao reenviar a edição. Reconecte e tente novamente."))
                    return@launch
                }
                _messages.update { currentList ->
                    currentList.map { message ->
                        if (message.id == assistantMsgId && message.isStreaming) {
                            message.copy(
                                content = "⚠️ Falha ao enviar para o Antigravity. Verifique se o serviço está em execução.",
                                isStreaming = false
                            )
                        } else message
                    }
                }
                activeAssistantMsgId = null
            } else savedDraft = null
        }
    }

    fun sendInterrupt() {
        if (_status.value.connectionState in listOf(ConnectionState.PREPARING, ConnectionState.CONNECTING)) {
            (activeClient as? WebSocketBridgeClient)?.disconnect()
            return
        }
        val client = activeClient
        viewModelScope.launch {
            client?.interrupt()
        }
    }

    fun reconnect() {
        if (_status.value.agyState == AGYState.RUNNING) return
        finishActiveStreaming()
        val client = activeClient
        viewModelScope.launch {
            client?.start()
        }
    }

    fun resume() {
        if (_status.value.connectionState in listOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) reconnect()
    }

    fun saveSettings(settings: TerminalSettings) {
        if (_fileSelectionBusy.value || _status.value.agyState == AGYState.RUNNING || activeAssistantMsgId != null) {
            return
        }
        try {
            settingsRepository?.saveTerminalSettings(settings)
            _settings.value = settings.copy(workspaces = (settings.workspaces + settings.workspace).filter { it.isNotBlank() }.distinct())
            _workspaces.value = null
            activeClient?.stop()
            val client = createClient()
            activeClient = client
            attachClient(client)
            viewModelScope.launch { client.start() }
        } catch (e: IllegalArgumentException) {
            handleBridgeEvent(BridgeEvent.Error(e.message ?: "Configuração inválida"))
        }
    }

    fun listWorkspaces(root: String) {
        if ((activeClient as? WebSocketBridgeClient)?.listWorkspaces(root) != true) {
            handleBridgeEvent(BridgeEvent.Error("Conecte a ponte para navegar nas pastas do terminal. Você também pode informar um caminho absoluto."))
        }
    }

    fun validateWorkspace() {
        if (_fileSelectionBusy.value || _status.value.agyState == AGYState.RUNNING || activeAssistantMsgId != null) return
        val message = ChatMessage(content = "Validando acesso com AGY...\n", isUser = false, isStreaming = true)
        activeAssistantMsgId = message.id
        _messages.update { it + message }
        if ((activeClient as? WebSocketBridgeClient)?.validateWorkspace() != true) {
            handleBridgeEvent(BridgeEvent.Error("Conecte a ponte antes de validar o workspace"))
        }
    }

    fun clearRawLogs() {
        _rawTerminalLogs.value = ""
    }

    fun clearChat() {
        if (_fileSelectionBusy.value || _status.value.agyState == AGYState.RUNNING || activeAssistantMsgId != null) return
        if ((activeClient as? WebSocketBridgeClient)?.newConversation() != true) {
            handleBridgeEvent(BridgeEvent.Error("Conecte a ponte para iniciar uma nova conversa"))
        }
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
        activeClient?.stop()
    }
}

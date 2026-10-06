package com.example.antigravity.data.network

import com.example.antigravity.data.client.AgyClient
import com.example.antigravity.data.client.BridgeEvent
import com.example.antigravity.data.client.StreamType
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.BridgeMessage
import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.ClientInterruptMessage
import com.example.antigravity.data.model.ClientPromptMessage
import com.example.antigravity.data.model.ClientConfigureMessage
import com.example.antigravity.data.model.ClientWorkspaceMessage
import com.example.antigravity.data.model.PromptHistory
import com.example.antigravity.data.model.ConnectionState
import com.example.antigravity.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class WebSocketBridgeClient(
    private var currentUrl: String = "ws://127.0.0.1:8765",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val configuration: ClientConfigureMessage? = null,
    private val prepareTerminal: (suspend () -> Unit)? = null
) : AgyClient {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var isIntentionalDisconnect = false
    @Volatile private var connectionGeneration = 0
    private var terminalAttempted = false
    private var startupDeadline = 0L
    private var wasConnected = false
    private var configurationJob: Job? = null

    private val _status = MutableStateFlow(BridgeStatus())
    override val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
    override val incomingEvents: SharedFlow<BridgeEvent> = _incomingEvents.asSharedFlow()

    private val eventChannel = Channel<BridgeEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in eventChannel) {
                _incomingEvents.emit(event)
            }
        }
    }

    override suspend fun start() {
        if (_status.value.connectionState in listOf(ConnectionState.CONNECTING, ConnectionState.PREPARING)) return
        terminalAttempted = false
        startupDeadline = 0
        connect(currentUrl)
    }

    fun connect(url: String = currentUrl) {
        currentUrl = url
        isIntentionalDisconnect = false
        reconnectJob?.cancel()
        configurationJob?.cancel()
        val generation = ++connectionGeneration
        wasConnected = false
        webSocket?.cancel()
        webSocket = null

        _status.value = _status.value.copy(
            connectionState = ConnectionState.CONNECTING,
            agyState = AGYState.IDLE,
            serverUrl = url,
            statusMessage = "Conectando à ponte do terminal..."
        )

        val request = try {
            require(SettingsRepository.isValidServerUrl(url)) { "A ponte deve estar no loopback do celular" }
            Request.Builder()
            .url(url)
            .build()
        } catch (e: IllegalArgumentException) {
            _status.value = _status.value.copy(connectionState = ConnectionState.ERROR, statusMessage = "Endereço da ponte inválido")
            eventChannel.trySend(BridgeEvent.Error("Endereço da ponte inválido: $url", e))
            return
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration) return
                wasConnected = true
                if (configuration != null) {
                    waitForSession(generation)
                    return
                }
                _status.value = _status.value.copy(
                    connectionState = ConnectionState.CONNECTED,
                    statusMessage = "Conectado com sucesso"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration) return
                handleIncomingText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration) return
                webSocket.close(1000, null)
                _status.value = _status.value.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    statusMessage = "Conexão encerrada: $reason"
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration) return
                _status.value = _status.value.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    agyState = AGYState.IDLE,
                    statusMessage = "Desconectado"
                )
                eventChannel.trySend(BridgeEvent.Error("Conexão com a ponte encerrada"))
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration) return
                val errorMsg = "Falha na conexão: ${t.localizedMessage ?: "Erro desconhecido"}"
                if (wasConnected) eventChannel.trySend(BridgeEvent.Error("Conexão perdida durante a sessão: ${t.localizedMessage}", t))
                if (prepareTerminal != null && !terminalAttempted) {
                    terminalAttempted = true
                    reconnectJob = scope.launch {
                        _status.value = _status.value.copy(connectionState = ConnectionState.PREPARING, statusMessage = "Preparando ponte no Termux...")
                        try {
                            prepareTerminal.invoke()
                            startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                            delay(500)
                            if (generation == connectionGeneration) connect(currentUrl)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (generation == connectionGeneration) reportFailure(e.message ?: "Falha ao iniciar Termux", e)
                        }
                    }
                } else if (prepareTerminal != null && System.nanoTime() < startupDeadline) {
                    scheduleReconnect(500)
                } else {
                    reportFailure(if (prepareTerminal != null) "$errorMsg. Confira Python, AGY, permissão RUN_COMMAND e allow-external-apps=true no Termux." else errorMsg, t)
                    if (prepareTerminal == null) scheduleReconnect()
                }
            }
        })
    }

    private fun handleIncomingText(text: String) {
        try {
            val msg = json.decodeFromString<BridgeMessage>(text)
            when (msg.type) {
                "hello" -> {
                    if (msg.protocol != 4) {
                        disconnect()
                        reportFailure("Ponte antiga em execução (protocolo ${msg.protocol}). Encerre a ponte no Termux e reabra o app para instalar a atualização. Se ela roda em segundo plano, encerrar o Termux encerra também suas outras sessões.")
                    } else if (configuration != null) webSocket?.send(json.encodeToString(configuration))
                }
                "session" -> {
                    if (configuration != null && configuration.automatic != msg.automatic) {
                        reportFailure("A ponte confirmou um modo diferente do configurado. Confira a versão da ponte e do AGY.")
                        return
                    }
                    configurationJob?.cancel()
                    _status.value = _status.value.copy(connectionState = ConnectionState.CONNECTED, workspace = msg.workspace.orEmpty(),
                        automatic = msg.automatic, conversationId = msg.conversation_id)
                    eventChannel.trySend(BridgeEvent.Session(msg.workspace.orEmpty(), msg.automatic, msg.changed, msg.conversation_id))
                }
                "workspaces" -> eventChannel.trySend(BridgeEvent.Workspaces(msg.root, msg.parent, msg.paths))
                "files" -> eventChannel.trySend(BridgeEvent.Files(msg.root, msg.parent, msg.paths, msg.files, msg.selected))
                "status" -> {
                    val agyState = when (msg.state) {
                        "running" -> AGYState.RUNNING
                        "idle" -> AGYState.IDLE
                        "interrupted" -> AGYState.INTERRUPTED
                        "error" -> AGYState.ERROR
                        else -> _status.value.agyState
                    }
                    val msgText = msg.message ?: _status.value.statusMessage
                    _status.value = _status.value.copy(
                        connectionState = if (msg.state == "error" && _status.value.connectionState != ConnectionState.CONNECTED) ConnectionState.ERROR else _status.value.connectionState,
                        agyState = agyState,
                        statusMessage = msgText
                    )
                    eventChannel.trySend(BridgeEvent.Status(agyState, msgText))
                    if (msg.state == "error") {
                        configurationJob?.cancel()
                        eventChannel.trySend(BridgeEvent.Error(msg.message ?: "Erro no AGY do terminal"))
                    }
                }
                "turn_complete" -> {
                    _status.value = _status.value.copy(
                        agyState = if (_status.value.agyState == AGYState.ERROR) AGYState.ERROR else AGYState.IDLE,
                        statusMessage = if (_status.value.agyState == AGYState.ERROR) _status.value.statusMessage else "Pronto para próxima mensagem"
                    )
                    eventChannel.trySend(BridgeEvent.TurnComplete)
                }
                "output" -> {
                    val streamType = if (msg.stream == "stderr") StreamType.STDERR else StreamType.STDOUT
                    val chunk = msg.chunk ?: ""
                    eventChannel.trySend(BridgeEvent.Output(streamType, chunk))
                }
                "error" -> {
                    if (_status.value.connectionState != ConnectionState.CONNECTED || _status.value.agyState == AGYState.RUNNING) {
                        configurationJob?.cancel()
                        reportFailure(msg.message ?: "Erro na ponte")
                    } else eventChannel.trySend(BridgeEvent.Error(msg.message ?: "Erro na ponte"))
                }
            }
        } catch (e: Exception) {
            eventChannel.trySend(BridgeEvent.Error("Mensagem inválida recebida da ponte", e))
        }
    }

    override suspend fun sendPrompt(prompt: String, history: List<PromptHistory>?): Boolean {
        val ws = webSocket ?: return false
        if (_status.value.connectionState != ConnectionState.CONNECTED || _status.value.agyState !in listOf(AGYState.IDLE, AGYState.INTERRUPTED)) return false
        val message = ClientPromptMessage(content = prompt, history = history)
        val jsonPayload = json.encodeToString(message)
        _status.value = _status.value.copy(agyState = AGYState.RUNNING, statusMessage = "Antigravity processando...")
        val sent = ws.send(jsonPayload)
        if (!sent) _status.value = _status.value.copy(agyState = AGYState.IDLE)
        return sent
    }

    override suspend fun interrupt(): Boolean {
        return sendInterrupt()
    }

    fun sendInterrupt(): Boolean {
        val ws = webSocket ?: return false
        val message = ClientInterruptMessage()
        val jsonPayload = json.encodeToString(message)
        val sent = ws.send(jsonPayload)
        if (sent) {
            _status.value = _status.value.copy(
                statusMessage = "Enviando cancelamento (SIGINT)..."
            )
        }
        return sent
    }

    override fun stop() {
        disconnect()
        eventChannel.cancel()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    fun disconnect() {
        isIntentionalDisconnect = true
        connectionGeneration++
        reconnectJob?.cancel()
        configurationJob?.cancel()
        webSocket?.cancel()
        webSocket = null
        _status.value = _status.value.copy(
            connectionState = ConnectionState.DISCONNECTED,
            agyState = AGYState.IDLE,
            statusMessage = "Desconectado pelo usuário"
        )
    }

    fun reportFailure(message: String, cause: Throwable? = null) {
        configurationJob?.cancel()
        _status.value = _status.value.copy(connectionState = ConnectionState.ERROR, agyState = AGYState.ERROR, statusMessage = message)
        eventChannel.trySend(BridgeEvent.Error(message, cause))
    }

    fun listWorkspaces(root: String): Boolean = webSocket?.send(json.encodeToString(ClientWorkspaceMessage(root = root))) == true

    fun listFiles(path: String): Boolean = _status.value.connectionState == ConnectionState.CONNECTED &&
        webSocket?.send(json.encodeToString(ClientWorkspaceMessage(type = "files", root = path))) == true

    fun newConversation(): Boolean {
        if (_status.value.agyState == AGYState.RUNNING || _status.value.connectionState != ConnectionState.CONNECTED) return false
        _status.value = _status.value.copy(connectionState = ConnectionState.CONNECTING, statusMessage = "Preparando nova conversa...")
        waitForSession(connectionGeneration)
        val sent = webSocket?.send(json.encodeToString((configuration ?: ClientConfigureMessage(workspace = _status.value.workspace,
            automatic = _status.value.automatic)).copy(new_conversation = true))) == true
        if (!sent) reportFailure("Falha ao iniciar nova conversa. Reconecte a ponte.")
        return sent
    }

    fun validateWorkspace(): Boolean {
        if (_status.value.agyState != AGYState.IDLE || _status.value.connectionState != ConnectionState.CONNECTED) return false
        _status.value = _status.value.copy(agyState = AGYState.RUNNING, statusMessage = "Validando acesso com AGY...")
        val sent = webSocket?.send("{\"type\":\"validate_workspace\"}") == true
        if (!sent) _status.value = _status.value.copy(agyState = AGYState.IDLE)
        return sent
    }

    private fun waitForSession(generation: Int) {
        configurationJob?.cancel()
        configurationJob = scope.launch {
            delay(45000)
            if (generation == connectionGeneration && _status.value.connectionState != ConnectionState.CONNECTED) {
                reportFailure("A ponte não confirmou a sessão. Atualize agy_bridge.py, confira login e logs do Termux.")
            }
        }
    }

    private fun scheduleReconnect(waitMs: Long = 3000) {
        if (isIntentionalDisconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(waitMs)
            if (!isIntentionalDisconnect && _status.value.connectionState != ConnectionState.CONNECTED) {
                connect(currentUrl)
            }
        }
    }
}

package com.example.antigravity.data.network

import com.example.antigravity.data.client.AgyClient
import com.example.antigravity.data.client.BridgeEvent
import com.example.antigravity.data.client.StreamType
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.BridgeMessage
import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.ClientInterruptMessage
import com.example.antigravity.data.model.ClientPromptMessage
import com.example.antigravity.data.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class WebSocketBridgeClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : AgyClient {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var webSocket: WebSocket? = null
    private var currentUrl = "ws://127.0.0.1:8765"
    private var reconnectJob: Job? = null
    private var isIntentionalDisconnect = false

    private val _status = MutableStateFlow(BridgeStatus())
    override val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
    override val incomingEvents: SharedFlow<BridgeEvent> = _incomingEvents.asSharedFlow()

    private val _messages = MutableSharedFlow<BridgeMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<BridgeMessage> = _messages.asSharedFlow()

    override suspend fun start() {
        connect(currentUrl)
    }

    fun connect(url: String = currentUrl) {
        currentUrl = url
        isIntentionalDisconnect = false
        reconnectJob?.cancel()

        _status.value = _status.value.copy(
            connectionState = ConnectionState.CONNECTING,
            serverUrl = url,
            statusMessage = "Conectando ao Termux..."
        )

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket?.cancel()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = _status.value.copy(
                    connectionState = ConnectionState.CONNECTED,
                    statusMessage = "Conectado com sucesso"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _status.value = _status.value.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    statusMessage = "Conexão encerrada: $reason"
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = _status.value.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    statusMessage = "Desconectado"
                )
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = "Falha na conexão: ${t.localizedMessage ?: "Erro desconhecido"}"
                _status.value = _status.value.copy(
                    connectionState = ConnectionState.ERROR,
                    statusMessage = errorMsg
                )
                scope.launch {
                    _incomingEvents.emit(BridgeEvent.Error(errorMsg, t))
                }
                scheduleReconnect()
            }
        })
    }

    private fun handleIncomingText(text: String) {
        try {
            val msg = json.decodeFromString<BridgeMessage>(text)
            when (msg.type) {
                "status" -> {
                    val agyState = when (msg.state) {
                        "running" -> AGYState.RUNNING
                        "idle" -> AGYState.IDLE
                        "interrupted" -> AGYState.INTERRUPTED
                        else -> _status.value.agyState
                    }
                    val msgText = msg.message ?: _status.value.statusMessage
                    _status.value = _status.value.copy(
                        agyState = agyState,
                        statusMessage = msgText
                    )
                    scope.launch {
                        _incomingEvents.emit(BridgeEvent.Status(agyState, msgText))
                    }
                }
                "turn_complete" -> {
                    _status.value = _status.value.copy(
                        agyState = AGYState.IDLE,
                        statusMessage = "Pronto para próxima mensagem"
                    )
                    scope.launch {
                        _incomingEvents.emit(BridgeEvent.TurnComplete)
                    }
                }
                "output" -> {
                    val streamType = if (msg.stream == "stderr") StreamType.STDERR else StreamType.STDOUT
                    val chunk = msg.chunk ?: ""
                    scope.launch {
                        _incomingEvents.emit(BridgeEvent.Output(streamType, chunk))
                    }
                }
            }
            scope.launch {
                _messages.emit(msg)
            }
        } catch (e: Exception) {
            // Se não for JSON estruturado, trata como chunk de output direto
            val outputEvent = BridgeEvent.Output(StreamType.STDOUT, text)
            scope.launch {
                _incomingEvents.emit(outputEvent)
                _messages.emit(BridgeMessage(type = "output", chunk = text))
            }
        }
    }

    override suspend fun sendPrompt(prompt: String): Boolean {
        val ws = webSocket ?: return false
        val message = ClientPromptMessage(content = prompt)
        val jsonPayload = json.encodeToString(message)
        val sent = ws.send(jsonPayload)
        if (sent) {
            _status.value = _status.value.copy(
                agyState = AGYState.RUNNING,
                statusMessage = "Antigravity processando..."
            )
        }
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

    override suspend fun stop() {
        disconnect()
    }

    fun disconnect() {
        isIntentionalDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _status.value = _status.value.copy(
            connectionState = ConnectionState.DISCONNECTED,
            statusMessage = "Desconectado pelo usuário"
        )
    }

    fun updateServerUrl(newUrl: String) {
        disconnect()
        connect(newUrl)
    }

    private fun scheduleReconnect() {
        if (isIntentionalDisconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            if (!isIntentionalDisconnect && _status.value.connectionState != ConnectionState.CONNECTED) {
                connect(currentUrl)
            }
        }
    }
}

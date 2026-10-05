package com.example.antigravity.data.network

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
) {
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
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<BridgeMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<BridgeMessage> = _messages.asSharedFlow()

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
                _status.value = _status.value.copy(
                    connectionState = ConnectionState.ERROR,
                    statusMessage = "Falha na conexão: ${t.localizedMessage ?: "Erro desconhecido"}"
                )
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
                    _status.value = _status.value.copy(
                        agyState = agyState,
                        statusMessage = msg.message ?: _status.value.statusMessage
                    )
                }
                "turn_complete" -> {
                    _status.value = _status.value.copy(
                        agyState = AGYState.IDLE,
                        statusMessage = "Pronto para próxima mensagem"
                    )
                }
            }
            scope.launch {
                _messages.emit(msg)
            }
        } catch (e: Exception) {
            // Se não for JSON estruturado, trata como chunk de output direto
            scope.launch {
                _messages.emit(BridgeMessage(type = "output", chunk = text))
            }
        }
    }

    fun sendPrompt(prompt: String): Boolean {
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

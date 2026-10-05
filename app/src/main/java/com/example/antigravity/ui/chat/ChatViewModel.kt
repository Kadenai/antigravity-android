package com.example.antigravity.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.ChatMessage
import com.example.antigravity.data.network.AnsiCleaner
import com.example.antigravity.data.network.WebSocketBridgeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val client: WebSocketBridgeClient = WebSocketBridgeClient()
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                content = "👋 **Bem-vindo ao Antigravity Android!**\n\nConecte-se à ponte no Termux para começar. Execute `python agy_bridge.py` no seu Termux.",
                isUser = false
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    val status: StateFlow<BridgeStatus> = client.status

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _rawTerminalLogs = MutableStateFlow("")
    val rawTerminalLogs: StateFlow<String> = _rawTerminalLogs.asStateFlow()

    private var activeAssistantMsgId: String? = null

    init {
        // Inicia conexão inicial
        client.connect()

        viewModelScope.launch {
            client.messages.collect { msg ->
                handleBridgeMessage(msg)
            }
        }
    }

    private fun handleBridgeMessage(msg: com.example.antigravity.data.model.BridgeMessage) {
        when (msg.type) {
            "output" -> {
                val rawChunk = msg.chunk ?: ""
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
            "turn_complete" -> {
                finishActiveStreaming()
            }
            "status" -> {
                if (msg.state == "idle") {
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

        // Finaliza qualquer mensagem anterior se ainda estiver em streaming
        finishActiveStreaming()

        // 1. Adiciona mensagem do usuário
        val userMsg = ChatMessage(
            content = text,
            isUser = true
        )

        // 2. Cria mensagem vazia de resposta do assistente com indicador de streaming
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

        // 3. Envia para a ponte do Termux
        val sent = client.sendPrompt(text)
        if (!sent) {
            _messages.update { currentList ->
                currentList.map { message ->
                    if (message.id == assistantMsgId) {
                        message.copy(
                            content = "⚠️ Falha ao enviar para o Termux. Verifique se a ponte está em execução.",
                            isStreaming = false
                        )
                    } else message
                }
            }
            activeAssistantMsgId = null
        }
    }

    fun sendInterrupt() {
        client.sendInterrupt()
        finishActiveStreaming()
    }

    fun reconnect() {
        client.connect()
    }

    fun updateServerUrl(newUrl: String) {
        client.disconnect()
        client.connect(newUrl)
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
        client.disconnect()
    }
}

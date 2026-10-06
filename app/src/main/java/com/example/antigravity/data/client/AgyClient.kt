package com.example.antigravity.data.client

import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.PromptHistory
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface unificada para clientes do motor Antigravity.
 * Conecta o frontend ao CLI executado no terminal por uma ponte WebSocket.
 */
interface AgyClient {
    /**
     * Estado consolidado de conexão e execução do motor.
     */
    val status: StateFlow<BridgeStatus>

    /**
     * Fluxo compartilhado de eventos recebidos do motor (chunks de saída, mudanças de estado, turn_complete).
     */
    val incomingEvents: SharedFlow<BridgeEvent>

    /**
     * Inicia a conexão com a ponte do terminal.
     */
    suspend fun start()

    /**
     * Envia o texto de prompt para o Antigravity.
     * @return true se o envio foi bem-sucedido, false caso contrário.
     */
    suspend fun sendPrompt(prompt: String, history: List<PromptHistory>? = null): Boolean

    /**
     * Envia um sinal de interrupção (SIGINT / Ctrl+C) para interromper o processamento atual.
     * @return true se o sinal foi despachado, false caso contrário.
     */
    suspend fun interrupt(): Boolean

    /**
     * Libera a conexão e os recursos do cliente, inclusive durante onCleared.
     */
    fun stop()
}

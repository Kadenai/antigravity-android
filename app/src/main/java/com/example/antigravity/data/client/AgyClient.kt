package com.example.antigravity.data.client

import com.example.antigravity.data.model.BridgeStatus
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface unificada para clientes do motor Antigravity.
 * Permite alternar de forma transparente entre o executor local nativo
 * (ProcessBuilder) e o cliente WebSocket remoto (Termux/PC).
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
     * Inicia a conexão com o servidor ou spawna o processo nativo.
     */
    suspend fun start()

    /**
     * Envia o texto de prompt para o Antigravity.
     * @return true se o envio foi bem-sucedido, false caso contrário.
     */
    suspend fun sendPrompt(prompt: String): Boolean

    /**
     * Envia um sinal de interrupção (SIGINT / Ctrl+C) para interromper o processamento atual.
     * @return true se o sinal foi despachado, false caso contrário.
     */
    suspend fun interrupt(): Boolean

    /**
     * Encerra o processo ou fecha a conexão de forma graciosa.
     */
    suspend fun stop()
}

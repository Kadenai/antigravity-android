package com.example.antigravity.data.model

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class AGYState {
    IDLE,
    RUNNING,
    INTERRUPTED
}

data class BridgeStatus(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val agyState: AGYState = AGYState.IDLE,
    val statusMessage: String = "",
    val serverUrl: String = "ws://127.0.0.1:8765"
)

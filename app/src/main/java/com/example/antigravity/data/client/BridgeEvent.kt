package com.example.antigravity.data.client

import com.example.antigravity.data.model.AGYState

typealias BridgeState = AGYState

enum class StreamType {
    STDOUT,
    STDERR
}

sealed interface BridgeEvent {
    data class Output(
        val stream: StreamType,
        val chunk: String
    ) : BridgeEvent

    data class Status(
        val state: AGYState,
        val message: String = ""
    ) : BridgeEvent

    data object TurnComplete : BridgeEvent

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : BridgeEvent
}

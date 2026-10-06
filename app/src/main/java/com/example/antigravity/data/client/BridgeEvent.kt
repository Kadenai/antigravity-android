package com.example.antigravity.data.client

import com.example.antigravity.data.model.AGYState

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

    data class Session(val workspace: String, val automatic: Boolean, val changed: Boolean, val conversationId: String) : BridgeEvent
    data class Workspaces(val root: String, val parent: String, val paths: List<String>) : BridgeEvent
    data class Files(val root: String, val parent: String, val paths: List<String>, val files: List<String>, val selected: String) : BridgeEvent

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : BridgeEvent
}

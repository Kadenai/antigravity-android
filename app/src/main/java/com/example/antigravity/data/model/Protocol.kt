package com.example.antigravity.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ClientPromptMessage(
    val type: String = "prompt",
    val content: String
)

@Serializable
data class ClientInterruptMessage(
    val type: String = "interrupt"
)

@Serializable
data class BridgeMessage(
    val type: String,
    val state: String? = null,
    val stream: String? = null,
    val chunk: String? = null,
    val message: String? = null
)

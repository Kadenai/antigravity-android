package com.example.antigravity.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ClientPromptMessage(
    val type: String = "prompt",
    val content: String,
    val history: List<PromptHistory>? = null
)

@Serializable
data class PromptHistory(val role: String, val content: String)

@Serializable
data class ClientInterruptMessage(
    val type: String = "interrupt"
)

@Serializable
data class ClientConfigureMessage(
    val type: String = "configure",
    val workspace: String = "",
    val automatic: Boolean = false,
    val new_conversation: Boolean = false
)

@Serializable
data class ClientWorkspaceMessage(val type: String = "workspaces", val root: String = "~")

@Serializable
data class BridgeMessage(
    val type: String,
    val state: String? = null,
    val stream: String? = null,
    val chunk: String? = null,
    val message: String? = null,
    val protocol: Int? = null,
    val workspace: String? = null,
    val automatic: Boolean = false,
    val conversation_id: String = "",
    val changed: Boolean = false,
    val cli_version: String = "",
    val root: String = "",
    val parent: String = "",
    val paths: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val selected: String = ""
)

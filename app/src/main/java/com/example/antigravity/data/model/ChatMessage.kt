package com.example.antigravity.data.model

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val rawLogs: String = "",
    val files: List<String> = emptyList()
) {
    fun prompt(): String = content + if (files.isEmpty()) "" else
        "\n\nArquivos referenciados (cada linha @ contém um caminho completo; leia conforme necessário):\n" +
            files.joinToString("\n") { "@$it" }
}

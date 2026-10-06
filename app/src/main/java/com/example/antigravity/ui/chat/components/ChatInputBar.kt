package com.example.antigravity.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    isRunning: Boolean,
    isConnected: Boolean,
    files: List<String>,
    isEditing: Boolean,
    fileSelectionBusy: Boolean,
    onAttachFile: () -> Unit,
    onRemoveFile: (String) -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            if (isEditing) Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Reenviar apaga as mensagens posteriores.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancelEdit, enabled = !isRunning && !fileSelectionBusy) { Text("Cancelar edição") }
            }
            if (files.isNotEmpty()) FileReferences(files, onRemoveFile)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = onAttachFile, enabled = isConnected && !isRunning && !fileSelectionBusy) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Referenciar arquivo")
                }
                TextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = {
                        Text(
                            text = if (isConnected) "Digite um comando ou mensagem..." else "Conecte-se à ponte do terminal",
                            fontSize = 14.sp
                        )
                    },
                    enabled = isConnected,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Transparent),
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    maxLines = 5
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (isRunning) {
                    // Botão de Interrupção (Stop / SIGINT)
                    IconButton(
                        onClick = onInterrupt,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Cancelar execução"
                        )
                    }
                } else {
                    // Botão de Enviar
                    val canSend = isConnected && !fileSelectionBusy && (inputText.isNotBlank() || files.isNotEmpty())
                    IconButton(
                        onClick = onSend,
                        enabled = canSend,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (isEditing) "Reenviar mensagem editada" else "Enviar mensagem"
                        )
                    }
                }
            }
        }
    }
}

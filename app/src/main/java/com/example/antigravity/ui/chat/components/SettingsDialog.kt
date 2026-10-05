package com.example.antigravity.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antigravity.data.settings.ClientMode

@Composable
fun SettingsDialog(
    currentMode: ClientMode,
    currentUrl: String,
    currentWorkspace: String,
    onDismiss: () -> Unit,
    onSaveSettings: (ClientMode, String, String) -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentMode) }
    var urlText by remember { mutableStateOf(currentUrl) }
    var workspaceText by remember { mutableStateOf(currentWorkspace) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Configurações do Antigravity")
        },
        text = {
            Column {
                Text(
                    text = "Modo de Execução:",
                    fontSize = 14.sp,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedMode = ClientMode.NATIVE }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = selectedMode == ClientMode.NATIVE,
                        onClick = { selectedMode = ClientMode.NATIVE }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Nativo Standalone", fontSize = 14.sp)
                        Text(
                            "Executa direto no app sem Termux",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedMode = ClientMode.REMOTE }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = selectedMode == ClientMode.REMOTE,
                        onClick = { selectedMode = ClientMode.REMOTE }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Remoto WebSocket", fontSize = 14.sp)
                        Text(
                            "Conecta à ponte Termux ou PC",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedMode == ClientMode.REMOTE) {
                    Text(
                        text = "Endereço WebSocket do Termux:",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        placeholder = { Text("ws://127.0.0.1:8765") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = "Diretório de Trabalho (Workspace):",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = workspaceText,
                    onValueChange = { workspaceText = it },
                    placeholder = { Text("Padrão: workspace interno") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Deixe vazio para usar o diretório padrão do aplicativo.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveSettings(
                        selectedMode,
                        urlText.trim(),
                        workspaceText.trim()
                    )
                }
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

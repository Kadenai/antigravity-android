package com.example.antigravity.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.example.antigravity.data.settings.SettingsRepository
import com.example.antigravity.data.settings.TerminalSettings
import com.example.antigravity.data.client.BridgeEvent

@Composable
fun SettingsDialog(
    settings: TerminalSettings,
    directories: BridgeEvent.Workspaces?,
    busy: Boolean,
    connected: Boolean,
    onBrowse: (String) -> Unit,
    onValidate: () -> Unit,
    onOpenTermux: () -> Unit,
    onOpenPermissions: () -> Unit,
    onDismiss: () -> Unit,
    onSaveSettings: (TerminalSettings) -> Unit
) {
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var automatic by remember { mutableStateOf(settings.automatic) }
    var url by remember { mutableStateOf(settings.serverUrl) }
    var workspace by remember { mutableStateOf(settings.workspace) }
    var savedPaths by remember { mutableStateOf(settings.workspaces) }
    val validUrl = SettingsRepository.isValidServerUrl(url)
    val validWorkspace = SettingsRepository.isValidWorkspace(workspace)
    val unchangedSession = workspace == settings.workspace && automatic == settings.automatic && url == settings.serverUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal e workspaces") },
        text = {
            Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState())) {
                Text("Configuração inicial no Termux: instale Python, instale o AGY e faça login executando agy. Ative o acesso externo e recarregue as configurações:")
                SelectionContainer {
                    Text("pkg install python\nmkdir -p ~/.termux\nprintf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties\ntermux-reload-settings")
                }
                Row {
                    TextButton(onClick = onOpenTermux) { Text("Abrir Termux") }
                    TextButton(onClick = onOpenPermissions) { Text("Permissão Android") }
                }
                Text("Conceda Executar comandos no Termux ao Antigravity Android. Para arquivos compartilhados, execute termux-setup-storage e autorize o próprio Termux. Mantenha o Termux ativo; se o Android o encerrar, ajuste suas restrições de bateria.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoStart, enabled = !busy, onCheckedChange = {
                        autoStart = it
                        if (it) { url = SettingsRepository.DEFAULT_SERVER_URL; if (workspace.isEmpty()) workspace = "~" }
                    })
                    Text("Iniciar ou reutilizar Termux ao abrir")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = automatic, enabled = !busy, onCheckedChange = { automatic = it })
                    Text("Modo automático: aprovar todas as ferramentas")
                }
                Text("Ativar o modo automático permite ao AGY editar arquivos e executar comandos sem confirmações do CLI, com os acessos do terminal. Permissões e login do Android continuam necessários.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, enabled = !autoStart && !busy,
                    label = { Text("Ponte local") }, isError = !validUrl, singleLine = true,
                    supportingText = { Text("ws://127.0.0.1:8765 · execução somente no celular") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = workspace, onValueChange = { workspace = it }, enabled = !busy,
                    label = { Text("Workspace no terminal") }, placeholder = { Text("~/projetos/meu-app") },
                    isError = !validWorkspace, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Caminho absoluto ou ~/pasta. Vazio usa a pasta atual da ponte. URIs content:// não são caminhos do CLI.") })
                savedPaths.forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { workspace = path }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(path) }
                        TextButton(onClick = { savedPaths = savedPaths - path }, enabled = !busy) { Text("Remover") }
                    }
                }
                Row {
                    TextButton(onClick = { onBrowse(workspace.ifEmpty { "~" }) }, enabled = connected && validWorkspace && !busy) { Text("Navegar") }
                    TextButton(onClick = { onBrowse("/storage/emulated/0") }, enabled = connected && !busy) { Text("Compartilhado") }
                }
                directories?.let { listing ->
                    HorizontalDivider()
                    Text(listing.root)
                    Row {
                        TextButton(onClick = { onBrowse(listing.parent) }, enabled = !busy) { Text("Pasta acima") }
                        TextButton(onClick = { workspace = listing.root }, enabled = !busy) { Text("Usar esta pasta") }
                    }
                    listing.paths.forEach { path ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onBrowse(path) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(path.substringAfterLast('/')) }
                            TextButton(onClick = { workspace = path }, enabled = !busy) { Text("Selecionar") }
                        }
                    }
                    if (listing.paths.isEmpty()) Text("Nenhuma subpasta. Pastas inacessíveis serão informadas ao selecionar.")
                }
                Text("Salvar mantém a pasta e inicia uma conversa separada ao trocar workspace ou modo. O acesso inicial é conferido pelo terminal e pelo cwd informado pelo CLI; valide as ferramentas com o AGY abaixo.")
                OutlinedButton(onClick = onValidate, enabled = connected && !busy && unchangedSession, modifier = Modifier.fillMaxWidth()) {
                    Text("Validar leitura, escrita e execução com AGY")
                }
                if (!unchangedSession) Text("Salve e conecte a sessão antes de validar o novo workspace.")
                if (busy) Text("Pare o turno antes de alterar a sessão.")
            }
        },
        confirmButton = {
            Button(enabled = validUrl && validWorkspace && !busy, onClick = {
                onSaveSettings(TerminalSettings(url, autoStart, automatic, workspace, savedPaths))
            }) { Text("Salvar e conectar") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

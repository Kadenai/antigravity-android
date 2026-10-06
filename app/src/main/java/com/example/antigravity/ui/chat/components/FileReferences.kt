package com.example.antigravity.ui.chat.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.antigravity.data.client.BridgeEvent

@Composable
fun FileReferences(files: List<String>, onRemove: ((String) -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { path ->
            val name = path.substringAfterLast('/')
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.padding(start = 8.dp, end = if (onRemove == null) 8.dp else 0.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = "Referência @$path", modifier = Modifier.size(18.dp))
                    Column(Modifier.padding(horizontal = 6.dp).widthIn(max = 160.dp)) {
                        Text(name.substringAfterLast('.', "ARQUIVO").uppercase().take(10), style = MaterialTheme.typography.labelSmall)
                        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (onRemove != null) IconButton(onClick = { onRemove(path) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remover $name", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun FilePickerDialog(listing: BridgeEvent.Files?, initialPath: String, busy: Boolean, error: String,
                     onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var path by remember(listing?.root) { mutableStateOf(listing?.root?.ifEmpty { initialPath } ?: initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Referenciar arquivo") },
        text = {
            Column {
                Text("Selecione um arquivo do terminal. O chat envia apenas @caminho, sem copiar o arquivo.")
                OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Pasta ou arquivo") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { onOpen(path) }, enabled = !busy && path.isNotBlank()) { Text("Abrir") }
                }
                Row {
                    TextButton(onClick = { onOpen("~") }, enabled = !busy) { Text("Terminal") }
                    TextButton(onClick = { onOpen("/storage/emulated/0") }, enabled = !busy) { Text("Compartilhado") }
                }
                if (busy) Text("Consultando o terminal…")
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                if (listing != null && listing.selected.isEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        item {
                            TextButton(onClick = { onOpen(listing.parent) }, enabled = !busy) { Text("↑ Pasta acima") }
                        }
                        items(listing.paths, key = { "dir:$it" }) { directory ->
                            TextButton(onClick = { onOpen(directory) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("📁 ${directory.substringAfterLast('/')}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        items(listing.files, key = { "file:$it" }) { file ->
                            TextButton(onClick = { onOpen(file) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(file.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (listing.paths.isEmpty() && listing.files.isEmpty()) item { Text("Pasta vazia") }
                    }
                }
                Text("Para arquivos compartilhados, autorize o Termux com termux-setup-storage.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Fechar") } }
    )
}

package com.example.antigravity.ui.chat.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antigravity.R
import com.example.antigravity.data.model.AGYState
import com.example.antigravity.data.model.BridgeStatus
import com.example.antigravity.data.model.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusHeader(
    status: BridgeStatus,
    onOpenSettings: () -> Unit,
    onOpenRawTerminal: () -> Unit,
    onReconnect: () -> Unit,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.ic_antigravity_logo),
                    contentDescription = "Antigravity Logo",
                    modifier = Modifier
                        .size(32.dp)
                        .padding(end = 8.dp)
                )
                Column {
                    Text(
                        text = "Antigravity",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    ConnectionStatusChip(
                        status = status,
                        onReconnect = onReconnect
                    )
                    if (status.workspace.isNotEmpty()) Text(status.workspace, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        actions = {
            IconButton(onClick = onNewConversation, enabled = status.connectionState == ConnectionState.CONNECTED && status.agyState != AGYState.RUNNING) {
                Icon(Icons.Default.Add, contentDescription = "Nova conversa")
            }
            IconButton(onClick = onOpenRawTerminal) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Ver saída do terminal"
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Configurações de conexão"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
    )
}

@Composable
fun ConnectionStatusChip(
    status: BridgeStatus,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (dotColor, text) = when (status.connectionState) {
        ConnectionState.CONNECTED -> {
            if (status.agyState == AGYState.ERROR) {
                Color(0xFFE63946) to "Erro no AGY (toque p/ reconectar)"
            } else if (status.agyState == AGYState.RUNNING) {
                Color(0xFFFFB703) to "Antigravity em execução..."
            } else {
                Color(0xFF2EC4B6) to "Conectado ao terminal"
            }
        }
        ConnectionState.CONNECTING -> {
            Color(0xFFFFB703) to "Conectando ao terminal..."
        }
        ConnectionState.PREPARING -> Color(0xFFFFB703) to "Preparando Termux..."
        ConnectionState.DISCONNECTED -> {
            Color(0xFFE63946) to "Desconectado (toque p/ reconectar)"
        }
        ConnectionState.ERROR -> {
            Color(0xFFE63946) to "Erro (toque p/ reconectar)"
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                enabled = status.connectionState != ConnectionState.CONNECTED || status.agyState == AGYState.ERROR,
                onClick = onReconnect
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

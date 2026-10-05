package com.example.antigravity.data.client

import android.content.Context
import com.example.antigravity.data.network.WebSocketBridgeClient
import com.example.antigravity.data.settings.ClientMode
import com.example.antigravity.data.settings.SettingsRepository
import com.example.antigravity.data.workspace.WorkspaceManager
import java.io.File

interface AgyClientFactory {
    fun createClient(mode: ClientMode): AgyClient
}

class DefaultAgyClientFactory(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val workspaceManager: WorkspaceManager
) : AgyClientFactory {

    override fun createClient(mode: ClientMode): AgyClient {
        return when (mode) {
            ClientMode.NATIVE -> {
                val binaryFile = File(context.applicationInfo.nativeLibraryDir, "libantigravity.so")
                NativeAgyClient(
                    binaryFile = binaryFile,
                    workspaceManager = workspaceManager
                )
            }
            ClientMode.REMOTE -> {
                WebSocketBridgeClient(
                    currentUrl = settingsRepository.getServerUrl()
                )
            }
        }
    }
}

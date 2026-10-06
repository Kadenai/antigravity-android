package com.example.antigravity.data.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TerminalSettings(
    val serverUrl: String = SettingsRepository.DEFAULT_SERVER_URL,
    val autoStart: Boolean = false,
    val automatic: Boolean = false,
    val workspace: String = "",
    val workspaces: List<String> = emptyList()
)

class SettingsRepository(
    private val preferences: SharedPreferences
) {
    companion object {
        private const val KEY_SERVER_URL = "key_server_url"

        const val DEFAULT_SERVER_URL = "ws://127.0.0.1:8765"

        fun isValidServerUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "ws" && uri.host in listOf("127.0.0.1", "localhost", "[::1]", "::1") &&
                uri.port in 1..65535 && uri.fragment == null && uri.userInfo == null &&
                uri.query == null && (uri.path.isNullOrEmpty() || uri.path == "/")
        }.getOrDefault(false)

        fun isValidWorkspace(path: String): Boolean = (path.isEmpty() || path == "~" || path.startsWith("~/") || path.startsWith("/")) &&
            '\u0000' !in path && '\n' !in path && '\r' !in path
    }

    fun getServerUrl(): String {
        return preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
            ?.takeIf { isValidServerUrl(it) } ?: DEFAULT_SERVER_URL
    }

    fun setServerUrl(url: String) {
        val trimmed = url.trim()
        require(isValidServerUrl(trimmed)) { "Use uma ponte local ws://127.0.0.1:porta" }
        preferences.edit { putString(KEY_SERVER_URL, trimmed) }
    }

    fun getTerminalSettings(): TerminalSettings {
        val stored = preferences.getString("terminal_settings", null)
        val settings = runCatching { Json.decodeFromString<TerminalSettings>(stored ?: "{}") }.getOrDefault(TerminalSettings())
        return settings.copy(serverUrl = getServerUrl(), workspace = settings.workspace.takeIf(::isValidWorkspace) ?: "",
            workspaces = settings.workspaces.filter(::isValidWorkspace))
    }

    fun saveTerminalSettings(settings: TerminalSettings) {
        require(isValidWorkspace(settings.workspace)) { "Use um caminho absoluto ou ~/pasta do terminal" }
        require(!settings.autoStart || settings.serverUrl == DEFAULT_SERVER_URL) { "Início pelo Termux usa ws://127.0.0.1:8765" }
        setServerUrl(settings.serverUrl)
        val saved = settings.copy(workspaces = (settings.workspaces + settings.workspace).filter { it.isNotBlank() }.distinct())
        preferences.edit { putString("terminal_settings", Json.encodeToString(saved)) }
    }
}

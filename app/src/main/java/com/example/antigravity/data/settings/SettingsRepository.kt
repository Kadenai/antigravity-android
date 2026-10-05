package com.example.antigravity.data.settings

import android.content.SharedPreferences

enum class ClientMode {
    NATIVE,
    REMOTE
}

class SettingsRepository(
    private val preferences: SharedPreferences
) {
    companion object {
        private const val KEY_CLIENT_MODE = "key_client_mode"
        private const val KEY_SERVER_URL = "key_server_url"
        private const val KEY_WORKSPACE_PATH = "key_workspace_path"

        const val DEFAULT_SERVER_URL = "ws://127.0.0.1:8765"
    }

    fun getMode(): ClientMode {
        val modeStr = preferences.getString(KEY_CLIENT_MODE, ClientMode.NATIVE.name)
        return try {
            ClientMode.valueOf(modeStr ?: ClientMode.NATIVE.name)
        } catch (_: IllegalArgumentException) {
            ClientMode.NATIVE
        }
    }

    fun setMode(mode: ClientMode) {
        preferences.edit().putString(KEY_CLIENT_MODE, mode.name).apply()
    }

    fun getServerUrl(): String {
        return preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun setServerUrl(url: String) {
        preferences.edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    fun getWorkspacePath(): String? {
        val path = preferences.getString(KEY_WORKSPACE_PATH, null)
        return if (path.isNullOrBlank()) null else path
    }

    fun setWorkspacePath(path: String?) {
        if (path.isNullOrBlank()) {
            preferences.edit().remove(KEY_WORKSPACE_PATH).apply()
        } else {
            preferences.edit().putString(KEY_WORKSPACE_PATH, path.trim()).apply()
        }
    }
}

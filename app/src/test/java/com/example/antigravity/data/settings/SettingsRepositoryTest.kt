package com.example.antigravity.data.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        repository = SettingsRepository(fakePrefs)
    }

    @Test
    fun defaultSettings_returnsLocalBridgeUrl() {
        assertEquals("ws://127.0.0.1:8765", repository.getServerUrl())
    }

    @Test
    fun invalidSavedUrl_fallsBackToLocalBridge() {
        fakePrefs.edit().putString("key_server_url", "local://native").apply()
        assertEquals(SettingsRepository.DEFAULT_SERVER_URL, repository.getServerUrl())
    }

    @Test
    fun setServerUrl_updatesAndPersistsUrl() {
        repository.setServerUrl("  ws://127.0.0.1:9000  ")
        assertEquals("ws://127.0.0.1:9000", repository.getServerUrl())

        val newRepo = SettingsRepository(fakePrefs)
        assertEquals("ws://127.0.0.1:9000", newRepo.getServerUrl())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidUrl_isRejected() {
        repository.setServerUrl("http://localhost")
    }

    @Test
    fun terminalSettings_persistExplicitModeAndWorkspaces() {
        assertFalse(repository.getTerminalSettings().automatic)
        assertFalse(repository.getTerminalSettings().autoStart)
        val settings = TerminalSettings(autoStart = true, automatic = true, workspace = "~/projetos/α",
            workspaces = listOf("/storage/emulated/0/Projects", "~/projetos/α"))
        repository.saveTerminalSettings(settings)
        assertEquals(settings, SettingsRepository(fakePrefs).getTerminalSettings())
        assertFalse(SettingsRepository.isValidWorkspace("content://documents/tree/primary"))
        assertFalse(SettingsRepository.isValidWorkspace("relative/path"))
        assertTrue(SettingsRepository.isValidWorkspace("/storage/emulated/0/Meu Projeto"))
        assertFalse(SettingsRepository.isValidServerUrl("ws://192.168.1.100:8765"))
        assertFalse(SettingsRepository.isValidServerUrl("ws://127.0.0.1:8765#fragment"))
    }
}

class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data

    override fun getString(key: String?, defValue: String?): String? {
        return (data[key] as? String) ?: defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (data[key] as? MutableSet<String>) ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(data)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) temp[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) temp[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) data.clear()
            for ((k, v) in temp) {
                if (v == null) data.remove(k) else data[k] = v
            }
        }
    }
}

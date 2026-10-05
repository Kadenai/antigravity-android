package com.example.antigravity.data.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun defaultSettings_returnsNativeModeAndDefaultUrl() {
        assertEquals(ClientMode.NATIVE, repository.getMode())
        assertEquals("ws://127.0.0.1:8765", repository.getServerUrl())
        assertNull(repository.getWorkspacePath())
    }

    @Test
    fun setMode_updatesAndPersistsMode() {
        repository.setMode(ClientMode.REMOTE)
        assertEquals(ClientMode.REMOTE, repository.getMode())

        // Ensure newly instantiated repository reads from the same preferences
        val newRepo = SettingsRepository(fakePrefs)
        assertEquals(ClientMode.REMOTE, newRepo.getMode())
    }

    @Test
    fun setServerUrl_updatesAndPersistsUrl() {
        repository.setServerUrl("ws://192.168.1.100:8765")
        assertEquals("ws://192.168.1.100:8765", repository.getServerUrl())

        val newRepo = SettingsRepository(fakePrefs)
        assertEquals("ws://192.168.1.100:8765", newRepo.getServerUrl())
    }

    @Test
    fun setWorkspacePath_updatesAndPersistsPath() {
        repository.setWorkspacePath("/custom/workspace")
        assertEquals("/custom/workspace", repository.getWorkspacePath())

        val newRepo = SettingsRepository(fakePrefs)
        assertEquals("/custom/workspace", newRepo.getWorkspacePath())
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

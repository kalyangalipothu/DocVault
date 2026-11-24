package com.example.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.docVaultDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "docvault_prefs"
)

enum class DarkModeOption {
    SYSTEM, LIGHT, DARK
}

data class UserAppPreferences(
    val darkMode: DarkModeOption = DarkModeOption.SYSTEM,
    val biometricAppLock: Boolean = false,
    val sortDescending: Boolean = true,
    val gridView: Boolean = false,
    val wrappedVaultKey: String = "",
    val wrappedVaultKeyIv: String = ""
)

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        val SORT_DESCENDING = booleanPreferencesKey("sort_descending")
        val GRID_VIEW = booleanPreferencesKey("grid_view")
        val WRAPPED_VAULT_KEY = stringPreferencesKey("wrapped_vault_key")
        val WRAPPED_VAULT_KEY_IV = stringPreferencesKey("wrapped_vault_key_iv")
    }

    val preferencesFlow: Flow<UserAppPreferences> = context.docVaultDataStore.data.map { prefs ->
        val modeRaw = prefs[Keys.DARK_MODE] ?: DarkModeOption.SYSTEM.name
        val darkMode = DarkModeOption.entries.firstOrNull { it.name == modeRaw }
            ?: DarkModeOption.SYSTEM
        UserAppPreferences(
            darkMode = darkMode,
            biometricAppLock = prefs[Keys.BIOMETRIC_LOCK] ?: false,
            sortDescending = prefs[Keys.SORT_DESCENDING] ?: true,
            gridView = prefs[Keys.GRID_VIEW] ?: false,
            wrappedVaultKey = prefs[Keys.WRAPPED_VAULT_KEY] ?: "",
            wrappedVaultKeyIv = prefs[Keys.WRAPPED_VAULT_KEY_IV] ?: ""
        )
    }

    suspend fun setDarkMode(option: DarkModeOption) {
        context.docVaultDataStore.edit { it[Keys.DARK_MODE] = option.name }
    }

    suspend fun setBiometricAppLock(enabled: Boolean) {
        context.docVaultDataStore.edit { it[Keys.BIOMETRIC_LOCK] = enabled }
    }

    suspend fun setSortDescending(descending: Boolean) {
        context.docVaultDataStore.edit { it[Keys.SORT_DESCENDING] = descending }
    }

    suspend fun setGridView(grid: Boolean) {
        context.docVaultDataStore.edit { it[Keys.GRID_VIEW] = grid }
    }

    suspend fun saveWrappedVaultKey(ciphertext: String, iv: String) {
        context.docVaultDataStore.edit {
            it[Keys.WRAPPED_VAULT_KEY] = ciphertext
            it[Keys.WRAPPED_VAULT_KEY_IV] = iv
        }
    }

    suspend fun clearWrappedVaultKey() {
        context.docVaultDataStore.edit {
            it.remove(Keys.WRAPPED_VAULT_KEY)
            it.remove(Keys.WRAPPED_VAULT_KEY_IV)
        }
    }
}

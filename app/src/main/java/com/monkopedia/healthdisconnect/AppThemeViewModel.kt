package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

class AppThemeViewModel(app: Application) : AndroidViewModel(app) {
    private val dataStore by lazy { context.appThemeDataStore }

    val themeMode: StateFlow<AppThemeMode> = dataStore.data
        .map { prefs ->
            prefs[themeModeKey]
                ?.let { raw -> runCatching { AppThemeMode.valueOf(raw) }.getOrNull() }
                ?: AppThemeMode.SYSTEM
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppThemeMode.SYSTEM
        )

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[themeModeKey] = mode.name
            }
        }
    }

    companion object {
        private val themeModeKey = stringPreferencesKey("theme_mode")
        val Context.appThemeDataStore by preferencesDataStore("app_theme")
    }
}


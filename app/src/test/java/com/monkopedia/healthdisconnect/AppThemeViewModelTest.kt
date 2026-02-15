package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.monkopedia.healthdisconnect.AppThemeViewModel.Companion.appThemeDataStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppThemeViewModelTest {

    @Test
    fun `themeMode persists across new view model instance`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetThemeDataStore(app)
        val themeModeKey = stringPreferencesKey("theme_mode")

        app.appThemeDataStore.edit { prefs ->
            prefs[themeModeKey] = AppThemeMode.LIGHT.name
        }

        val raw = app.appThemeDataStore.data.first { it.contains(themeModeKey) }
        assertEquals(AppThemeMode.LIGHT.name, raw[themeModeKey])

        val mapped = app.appThemeDataStore.data
            .map { prefs ->
                prefs[themeModeKey]
                    ?.let { rawMode -> runCatching { AppThemeMode.valueOf(rawMode) }.getOrNull() }
                    ?: AppThemeMode.SYSTEM
            }
            .first()
        assertEquals(AppThemeMode.LIGHT, mapped)

        val next = AppThemeViewModel(app)
        val persisted = withTimeout(10_000) {
            next.themeMode.first { it == AppThemeMode.LIGHT }
        }
        assertEquals(AppThemeMode.LIGHT, persisted)
    }

    @Test
    fun `invalid stored theme falls back to system`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        resetThemeDataStore(app)
        val themeModeKey = stringPreferencesKey("theme_mode")

        app.appThemeDataStore.edit { prefs ->
            prefs[themeModeKey] = "NOT_A_THEME"
        }

        val viewModel = AppThemeViewModel(app)
        assertEquals(
            AppThemeMode.SYSTEM,
            withTimeout(10_000) { viewModel.themeMode.first { it == AppThemeMode.SYSTEM } }
        )
    }

    private suspend fun resetThemeDataStore(app: Application) {
        app.appThemeDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

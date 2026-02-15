package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppThemePersistenceIntegrationTest {
    @Test
    fun themeModePersistsAcrossViewModelInstances() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = AppThemeViewModel(app)
        first.setThemeMode(AppThemeMode.DARK)
        assertEquals(AppThemeMode.DARK, first.themeMode.first { it == AppThemeMode.DARK })

        val second = AppThemeViewModel(app)
        assertEquals(AppThemeMode.DARK, second.themeMode.first { it == AppThemeMode.DARK })
    }

    @Test
    fun themeModePersistsForEachSupportedValue() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        for (mode in AppThemeMode.values()) {
            val writer = AppThemeViewModel(app)
            writer.setThemeMode(mode)
            assertEquals(mode, writer.themeMode.first { it == mode })

            val reader = AppThemeViewModel(app)
            assertEquals(mode, reader.themeMode.first { it == mode })
        }
    }
}

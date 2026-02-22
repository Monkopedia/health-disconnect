package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.model.WidgetUpdateWindow
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import java.lang.reflect.Field
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetConfigurationTest {
    private lateinit var app: Application
    private lateinit var appDb: AppDatabase
    private lateinit var instanceField: Field
    private var originalDatabase: AppDatabase? = null

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        appDb = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        instanceField = AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }
        originalDatabase = instanceField.get(null) as AppDatabase?
        instanceField.set(null, appDb)
        app.unbindWidgets(app.widgetBindingsSnapshot().keys.toIntArray())
    }

    @After
    fun tearDown() = runBlocking {
        app.unbindWidgets(app.widgetBindingsSnapshot().keys.toIntArray())
        appDb.clearAllTables()
        instanceField.set(null, originalDatabase)
        appDb.close()
    }

    @Test
    fun configureWidgetForView_bindsWidgetAndAppliesWindowOverride() = runBlocking {
        val viewId = 10
        val widgetId = 4001
        appDb.dataViewInfoDao().insert(DataViewInfoEntity(id = viewId, name = "Weight", ordering = 1))
        appDb.dataViewDao().insert(
            encodeDataViewEntity(
                DataView(
                    id = viewId,
                    type = ViewType.CHART,
                    records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
                    chartSettings = ChartSettings(widgetUpdateWindow = WidgetUpdateWindow.HOURS_24)
                )
            )
        )

        val configured = configureWidgetForView(
            context = app,
            appWidgetId = widgetId,
            viewId = viewId,
            updateWindowOverride = WidgetUpdateWindow.HOURS_6,
            refreshImmediately = false
        )

        assertTrue(configured)
        assertEquals(viewId, app.widgetViewId(widgetId))
        val updatedView = decodeDataViewEntity(appDb.dataViewDao().getById(viewId)!!)
        assertEquals(WidgetUpdateWindow.HOURS_6, updatedView.chartSettings.widgetUpdateWindow)
    }

    @Test
    fun configureWidgetForView_returnsFalseForMissingView() = runBlocking {
        val configured = configureWidgetForView(
            context = app,
            appWidgetId = 5001,
            viewId = 999,
            refreshImmediately = false
        )

        assertFalse(configured)
        assertEquals(null, app.widgetViewId(5001))
    }
}

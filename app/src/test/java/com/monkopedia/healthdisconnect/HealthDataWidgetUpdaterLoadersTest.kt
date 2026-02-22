package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import java.lang.reflect.Field
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthDataWidgetUpdaterLoadersTest {
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
    fun `series loader maps successful reads to success result`() = runBlocking {
        val expected = listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "lb",
                points = emptyList()
            )
        )
        val loader = HealthDataWidgetUpdater.DefaultWidgetSeriesLoader { _, _ -> expected }

        val result = loader.loadSeries(app, sampleView(1))

        assertTrue(result is HealthDataWidgetUpdater.WidgetSeriesLoadResult.Success)
        assertEquals(
            expected,
            (result as HealthDataWidgetUpdater.WidgetSeriesLoadResult.Success).seriesList
        )
    }

    @Test
    fun `series loader maps permission errors explicitly`() = runBlocking {
        val denied = setOf("weight")
        val loader = HealthDataWidgetUpdater.DefaultWidgetSeriesLoader { _, _ ->
            throw HealthDataPermissionDeniedException(denied)
        }

        val result = loader.loadSeries(app, sampleView(1))

        assertTrue(result is HealthDataWidgetUpdater.WidgetSeriesLoadResult.PermissionDenied)
        assertEquals(
            denied,
            (result as HealthDataWidgetUpdater.WidgetSeriesLoadResult.PermissionDenied).deniedRecordTypes
        )
    }

    @Test
    fun `series loader maps generic failures without pretending data is empty`() = runBlocking {
        val expected = IllegalStateException("boom")
        val loader = HealthDataWidgetUpdater.DefaultWidgetSeriesLoader { _, _ -> throw expected }

        val result = loader.loadSeries(app, sampleView(1))

        assertTrue(result is HealthDataWidgetUpdater.WidgetSeriesLoadResult.Failure)
        assertEquals(
            expected,
            (result as HealthDataWidgetUpdater.WidgetSeriesLoadResult.Failure).exception
        )
    }

    @Test
    fun `series loader rethrows cancellation`() {
        val loader = HealthDataWidgetUpdater.DefaultWidgetSeriesLoader { _, _ ->
            throw CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { loader.loadSeries(app, sampleView(1)) }
        }
    }

    @Test
    fun `binding loader returns unbound when widget has no binding`() = runBlocking {
        val result = HealthDataWidgetUpdater.RoomWidgetBindingLoader.loadBinding(app, 501)

        assertTrue(result is HealthDataWidgetUpdater.WidgetBindingLoadResult.Unbound)
    }

    @Test
    fun `binding loader returns missing state when linked room rows are absent`() = runBlocking {
        val widgetId = 502
        app.bindWidgetToView(widgetId, viewId = 77)

        val result = HealthDataWidgetUpdater.RoomWidgetBindingLoader.loadBinding(app, widgetId)

        assertTrue(result is HealthDataWidgetUpdater.WidgetBindingLoadResult.MissingBoundView)
        assertEquals(
            77,
            (result as HealthDataWidgetUpdater.WidgetBindingLoadResult.MissingBoundView).viewId
        )
    }

    @Test
    fun `binding loader returns bound view data when both room rows exist`() = runBlocking {
        val widgetId = 503
        val viewId = 78
        app.bindWidgetToView(widgetId, viewId)
        appDb.dataViewInfoDao().insert(DataViewInfoEntity(id = viewId, name = "Weight", ordering = 1))
        appDb.dataViewDao().insert(encodeDataViewEntity(sampleView(viewId)))

        val result = HealthDataWidgetUpdater.RoomWidgetBindingLoader.loadBinding(app, widgetId)

        assertTrue(result is HealthDataWidgetUpdater.WidgetBindingLoadResult.BoundView)
        val bound = result as HealthDataWidgetUpdater.WidgetBindingLoadResult.BoundView
        assertEquals(viewId, bound.viewId)
        assertEquals("Weight", bound.title)
        assertEquals(viewId, bound.view.id)
        assertTrue(bound.view.records.any { it.fqn == WeightRecord::class.qualifiedName })
    }

    @Test
    fun `binding loader hasAnyViews reflects room view-info rows`() = runBlocking {
        assertFalse(HealthDataWidgetUpdater.RoomWidgetBindingLoader.hasAnyViews(app))
        appDb.dataViewInfoDao().insert(DataViewInfoEntity(id = 90, name = "Distance", ordering = 1))
        assertTrue(HealthDataWidgetUpdater.RoomWidgetBindingLoader.hasAnyViews(app))
    }

    private fun sampleView(id: Int): DataView {
        return DataView(
            id = id,
            type = ViewType.CHART,
            records = listOf(RecordSelection(WeightRecord::class), RecordSelection(DistanceRecord::class)),
            chartSettings = ChartSettings(timeWindow = TimeWindow.DAYS_30)
        )
    }
}

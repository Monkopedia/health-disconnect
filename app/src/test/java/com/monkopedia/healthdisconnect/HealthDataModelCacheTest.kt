package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthDataModelCacheTest {

    @Before
    fun setUp() {
        clearSharedCaches()
    }

    @Test
    fun `cache key changes with records and time window`() {
        val model = HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false
        )

        val primary = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(StepsRecord::class),
                RecordSelection(DistanceRecord::class)
            ),
            chartSettings = ChartSettings(timeWindow = TimeWindow.DAYS_30)
        )
        val reordered = primary.copy(records = primary.records.reversed())
        val differentTimeWindow = primary.copy(chartSettings = ChartSettings(timeWindow = TimeWindow.ALL))
        val differentRecordSet = primary.copy(
            records = listOf(RecordSelection(DistanceRecord::class)),
            chartSettings = ChartSettings(timeWindow = TimeWindow.DAYS_30)
        )

        assertEquals(
            "record order should not affect cache key",
            cacheKey(model, primary),
            cacheKey(model, reordered)
        )
        assertNotEquals(
            cacheKey(model, primary),
            cacheKey(model, differentTimeWindow)
        )
        assertNotEquals(
            cacheKey(model, primary),
            cacheKey(model, differentRecordSet)
        )
    }

    @Test
    fun `refresh tick invalidates record cache state`() = runBlocking {
        val callCount = AtomicInteger(0)
        val model = HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false,
            recordLoaderOverride = { _, onPartialUpdate ->
                callCount.incrementAndGet()
                val records = listOf(stepsRecord(callCount.get().toLong()))
                onPartialUpdate?.invoke(records)
                records
            }
        )
        val view = DataView(
            id = 2,
            type = ViewType.CHART,
            records = listOf(RecordSelection(StepsRecord::class)),
            chartSettings = ChartSettings(timeWindow = TimeWindow.YEAR_1)
        )

        val firstTickRecords = loadRecords(model, view, 0)
        assertEquals(1, firstTickRecords.size)
        assertEquals(1, callCount.get())

        val cachedRecords = loadRecords(model, view, 0)
        assertEquals(1, callCount.get())
        assertEquals(firstTickRecords, cachedRecords)

        loadRecords(model, view, 1)
        withTimeout(5_000) {
            while (callCount.get() < 2) {
                kotlinx.coroutines.yield()
            }
        }
        assertEquals(2, callCount.get())
        val refreshedRecords = loadRecords(model, view, 1)
        assertEquals(1, refreshedRecords.size)
    }

    private suspend fun loadRecords(model: HealthDataModel, view: DataView, refreshTick: Int): List<Record> =
        withTimeout(5_000) {
            model.collectData(view, refreshTick)
                .first { it.isNotEmpty() }
        }

    private fun cacheKey(model: HealthDataModel, view: DataView): String {
        val method = HealthDataModel::class.java.getDeclaredMethod("cacheKey", DataView::class.java)
        method.isAccessible = true
        return method.invoke(model, view) as String
    }

    private fun clearSharedCaches() {
        clearMutableMapCache("sharedRecordCache")
        clearStateFlowCache("sharedMetricsWithData")
    }

    private fun clearMutableMapCache(fieldName: String) {
        val field: Field = HealthDataModel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(null) as MutableMap<String, *>
        cache.clear()
    }

    private fun clearStateFlowCache(fieldName: String) {
        val field: Field = HealthDataModel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(null) as MutableStateFlow<List<KClass<out Record>>?>
        stateFlow.value = null
    }

    private fun stepsRecord(marker: Long): StepsRecord {
        val record = mockk<StepsRecord>(relaxed = true)
        every { record.startTime } returns java.time.Instant.ofEpochSecond(marker)
        every { record.endTime } returns java.time.Instant.ofEpochSecond(marker)
        every { record.count } returns marker
        return record
    }
}

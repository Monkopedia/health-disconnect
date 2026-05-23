package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.KClass

@RunWith(RobolectricTestRunner::class)
class HealthDataModelRecentMetricDataTest {

    private class FakeGateway(private val records: List<Record>) : HealthConnectGateway {
        var lastStart: Instant? = null
        var lastEnd: Instant? = null

        override suspend fun hasRecordsForType(
            cls: KClass<out Record>,
            now: Instant,
            pageSize: Int
        ): Boolean = records.any { cls.isInstance(it) }

        override suspend fun readRecordsInRange(
            cls: KClass<out Record>,
            start: Instant,
            end: Instant,
            pageSize: Int,
            onPage: (List<Record>) -> Unit
        ) {
            onPage(records.filter { cls.isInstance(it) })
        }

        override suspend fun anyRecordInRange(
            cls: KClass<out Record>,
            start: Instant,
            end: Instant,
            pageSize: Int,
            predicate: (Record) -> Boolean
        ): Boolean {
            lastStart = start
            lastEnd = end
            return records.any { record ->
                cls.isInstance(record) &&
                    recordTimestamp(record)?.let { !it.isBefore(start) && !it.isAfter(end) } == true &&
                    predicate(record)
            }
        }
    }

    private fun model(gateway: HealthConnectGateway): HealthDataModel {
        return HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false,
            healthConnectGateway = gateway
        )
    }

    private fun nutritionAt(time: Instant, build: NutritionRecordBuilder.() -> Unit): NutritionRecord {
        val builder = NutritionRecordBuilder().apply(build)
        return NutritionRecord(
            startTime = time,
            startZoneOffset = ZoneOffset.UTC,
            endTime = time.plusSeconds(60),
            endZoneOffset = ZoneOffset.UTC,
            caffeine = builder.caffeine,
            energy = builder.energy,
            metadata = Metadata.manualEntry()
        )
    }

    private class NutritionRecordBuilder {
        var caffeine: Mass? = null
        var energy: androidx.health.connect.client.units.Energy? = null
    }

    @Test
    fun `recent caffeine value is detected`() = runBlocking {
        val gateway = FakeGateway(
            listOf(nutritionAt(Instant.now().minus(1, ChronoUnit.DAYS)) { caffeine = Mass.grams(0.1) })
        )
        assertTrue(
            model(gateway).hasRecentMetricData(NutritionRecord::class, "nutrition_caffeine")
        )
    }

    @Test
    fun `record without the targeted nutrient reports no data`() = runBlocking {
        val gateway = FakeGateway(
            listOf(
                nutritionAt(Instant.now().minus(1, ChronoUnit.DAYS)) {
                    energy = androidx.health.connect.client.units.Energy.kilocalories(200.0)
                }
            )
        )
        // Energy is present, but caffeine is not.
        assertFalse(
            model(gateway).hasRecentMetricData(NutritionRecord::class, "nutrition_caffeine")
        )
        assertTrue(
            model(gateway).hasRecentMetricData(NutritionRecord::class, null)
        )
    }

    @Test
    fun `data older than the lookback window is ignored`() = runBlocking {
        val gateway = FakeGateway(
            listOf(nutritionAt(Instant.now().minus(200, ChronoUnit.DAYS)) { caffeine = Mass.grams(0.1) })
        )
        assertFalse(
            model(gateway).hasRecentMetricData(NutritionRecord::class, "nutrition_caffeine")
        )
        // The probe window spans the default 90-day lookback.
        val start = requireNotNull(gateway.lastStart)
        val end = requireNotNull(gateway.lastEnd)
        assertEquals(Duration.ofDays(90), Duration.between(start, end))
    }
}

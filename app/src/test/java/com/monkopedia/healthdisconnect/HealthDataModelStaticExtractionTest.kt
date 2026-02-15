package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.ViewType
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthDataModelStaticExtractionTest {

    @Test
    fun `static mappings include weight and unit preference conversions`() {
        val model = model()
        val instant = Instant.parse("2026-02-15T08:00:00Z")
        val record: WeightRecord = mockk(relaxed = true)
        every { record.time } returns instant
        every { record.weight } returns Mass.kilograms(70.0)
        every { record.metadata } returns Metadata.manualEntry()
        assertPoint(record, UnitPreference.METRIC, 70.0, "kilograms", model)
        assertPoint(record, UnitPreference.IMPERIAL, 154.323, "pounds", model)
        assertPoint(record, UnitPreference.AUTO, 70.0, "kilograms", model)
    }

    @Test
    fun `static mappings include distance and unit preference conversions`() {
        val model = model()
        val instant = Instant.parse("2026-02-15T08:00:00Z")
        val record: DistanceRecord = mockk(relaxed = true)
        every { record.startTime } returns instant
        every { record.endTime } returns instant.plusSeconds(3600)
        every { record.distance } returns Length.kilometers(10.0)
        every { record.metadata } returns Metadata.manualEntry()
        assertPoint(record, UnitPreference.METRIC, 10.0, "kilometers", model)
        assertPoint(record, UnitPreference.IMPERIAL, 6.21371, "miles", model)
        assertPoint(record, UnitPreference.AUTO, 10.0, "kilometers", model)
    }

    @Test
    fun `static mappings include blood glucose and unit preference conversions`() {
        val model = model()
        val instant = Instant.parse("2026-02-15T08:00:00Z")
        val record: BloodGlucoseRecord = mockk(relaxed = true)
        every { record.time } returns instant
        every { record.level } returns BloodGlucose.millimolesPerLiter(5.5)
        every { record.metadata } returns Metadata.manualEntry()
        assertPoint(record, UnitPreference.METRIC, 5.5, "mmol/L", model)
        assertPoint(record, UnitPreference.IMPERIAL, 99.0, "mg/dL", model)
        assertPoint(record, UnitPreference.AUTO, 5.5, "mmol/L", model)
    }

    @Test
    fun `static mappings include total calories and unit preference conversions`() {
        val model = model()
        val instant = Instant.parse("2026-02-15T08:00:00Z")
        val record: TotalCaloriesBurnedRecord = mockk(relaxed = true)
        every { record.startTime } returns instant
        every { record.endTime } returns instant.plusSeconds(3600)
        every { record.energy } returns Energy.kilojoules(1000.0)
        every { record.metadata } returns Metadata.manualEntry()
        assertPoint(record, UnitPreference.METRIC, 1000.0, "kilojoules", model)
        assertPoint(record, UnitPreference.IMPERIAL, 239.006, "kilocalories", model)
        assertPoint(record, UnitPreference.AUTO, 239.006, "kilocalories", model)
    }

    @Test
    fun `static mappings include steps and count units`() {
        val model = model()
        val instant = Instant.parse("2026-02-15T08:00:00Z")
        val record: StepsRecord = mockk(relaxed = true)
        every { record.startTime } returns instant
        every { record.endTime } returns instant.plusSeconds(3600)
        every { record.count } returns 1234L
        every { record.metadata } returns Metadata.manualEntry()
        assertPoint(record, UnitPreference.METRIC, 1234.0, "count", model)
        assertPoint(record, UnitPreference.IMPERIAL, 1234.0, "count", model)
        assertPoint(record, UnitPreference.AUTO, 1234.0, "count", model)
    }

    private fun model(): HealthDataModel {
        return HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false
        )
    }

    private fun assertPoint(
        record: Record,
        unitPreference: UnitPreference,
        expectedValue: Double,
        expectedUnit: String,
        model: HealthDataModel
    ) {
        val fqn = record::class.qualifiedName ?: error("Record type missing qualifiedName")
        val selection = RecordSelection(
            fqn = fqn,
            metricSettings = MetricChartSettings(
                unitPreference = unitPreference
            )
        )
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(selection),
            chartSettings = ChartSettings(timeWindow = TimeWindow.ALL)
        )
        val seriesList = model.aggregateMetricSeriesList(view, listOf(record))
        val series = requireNotNull(seriesList.singleOrNull())
        assertEquals(1, series.points.size)
        assertEquals(1, seriesList.size)
        assertEquals(expectedUnit, series.unit)
        val pointValue = series.points.single().value
        assertEquals(
            expectedValue,
            pointValue,
            0.001
        )
    }
}

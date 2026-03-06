package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Pressure
import androidx.test.core.app.ApplicationProvider
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.ViewType
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthDataModelReflectiveExtractionTest {

    @Test
    fun `reflective extraction falls back to duration for session-like records`() {
        val model = model()
        val start = Instant.parse("2026-02-15T08:00:00Z")
        val end = start.plusSeconds(75 * 60)
        val record = SleepSessionRecord(
            start,
            ZoneOffset.UTC,
            end,
            ZoneOffset.UTC,
            Metadata.manualEntry(),
            "Nightly",
            "",
            emptyList<SleepSessionRecord.Stage>()
        )
        val measurement = extractMeasurement(model, record, UnitPreference.METRIC)
        assertEquals(75.0, measurement.value, 0.001)
        assertEquals("minutes", measurement.unitLabel)
    }

    @Test
    fun `aggregation supports multiple extracted metrics from sleep session`() {
        val model = model()
        val start = Instant.parse("2026-02-15T08:00:00Z")
        val end = start.plusSeconds(75 * 60)
        val sleepRecord = SleepSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(),
            title = "Nightly",
            notes = "",
            stages = listOf(
                SleepSessionRecord.Stage(
                    startTime = start,
                    endTime = end,
                    stage = SleepSessionRecord.STAGE_TYPE_DEEP
                )
            )
        )
        val sleepFqn = requireNotNull(SleepSessionRecord::class.qualifiedName)
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(
                    fqn = sleepFqn,
                    metricSettings = MetricChartSettings(
                        timeWindow = TimeWindow.ALL,
                        unitPreference = UnitPreference.METRIC
                    ),
                    metricKey = null
                ),
                RecordSelection(
                    fqn = sleepFqn,
                    metricSettings = MetricChartSettings(
                        timeWindow = TimeWindow.ALL,
                        unitPreference = UnitPreference.METRIC
                    ),
                    metricKey = DefaultHealthRecordMeasurementExtractor.SLEEP_STAGE_METRIC_KEY
                )
            ),
            chartSettings = ChartSettings(timeWindow = TimeWindow.ALL)
        )

        val seriesList = model.aggregateMetricSeriesList(view, listOf(sleepRecord))
        assertEquals(2, seriesList.size)

        assertEquals("Sleep Duration", seriesList[0].label)
        assertEquals(75.0, seriesList[0].points.single().value, 0.001)
        assertEquals("minutes", seriesList[0].unit)

        assertEquals("Sleep Stage", seriesList[1].label)
        assertEquals(5.0, seriesList[1].points.single().value, 0.001)
        assertEquals("stage", seriesList[1].unit)
    }

    @Test
    fun `aggregation supports blood pressure metric variants`() {
        val model = model()
        val time = Instant.parse("2026-02-15T08:00:00Z")
        val bloodPressureRecord = BloodPressureRecord(
            time = time,
            zoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(),
            systolic = Pressure.millimetersOfMercury(121.0),
            diastolic = Pressure.millimetersOfMercury(79.0)
        )
        val bloodPressureFqn = requireNotNull(BloodPressureRecord::class.qualifiedName)
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(
                    fqn = bloodPressureFqn,
                    metricSettings = MetricChartSettings(
                        timeWindow = TimeWindow.ALL,
                        unitPreference = UnitPreference.METRIC
                    ),
                    metricKey = null
                ),
                RecordSelection(
                    fqn = bloodPressureFqn,
                    metricSettings = MetricChartSettings(
                        timeWindow = TimeWindow.ALL,
                        unitPreference = UnitPreference.METRIC
                    ),
                    metricKey = DefaultHealthRecordMeasurementExtractor.BLOOD_PRESSURE_DIASTOLIC_METRIC_KEY
                )
            ),
            chartSettings = ChartSettings(timeWindow = TimeWindow.ALL)
        )

        val seriesList = model.aggregateMetricSeriesList(view, listOf(bloodPressureRecord))
        assertEquals(2, seriesList.size)

        assertEquals("Blood Pressure Systolic", seriesList[0].label)
        assertEquals(121.0, seriesList[0].points.single().value, 0.001)
        assertEquals("mmHg", seriesList[0].unit)

        assertEquals("Blood Pressure Diastolic", seriesList[1].label)
        assertEquals(79.0, seriesList[1].points.single().value, 0.001)
        assertEquals("mmHg", seriesList[1].unit)
    }

    @Test
    fun `aggregation supports nutrition metric variants`() {
        val model = model()
        val start = Instant.parse("2026-02-15T08:00:00Z")
        val end = start.plusSeconds(20 * 60)
        val nutritionRecord = NutritionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(),
            energy = Energy.kilocalories(430.0),
            protein = Mass.grams(28.0)
        )
        val nutritionFqn = requireNotNull(NutritionRecord::class.qualifiedName)
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(
                    fqn = nutritionFqn,
                    metricSettings = MetricChartSettings(
                        timeWindow = TimeWindow.ALL,
                        unitPreference = UnitPreference.IMPERIAL
                    ),
                    metricKey = null
                ),
                RecordSelection(
                    fqn = nutritionFqn,
                    metricSettings = MetricChartSettings(
                        timeWindow = TimeWindow.ALL,
                        unitPreference = UnitPreference.METRIC
                    ),
                    metricKey = DefaultHealthRecordMeasurementExtractor.NUTRITION_PROTEIN_METRIC_KEY
                )
            ),
            chartSettings = ChartSettings(timeWindow = TimeWindow.ALL)
        )

        val seriesList = model.aggregateMetricSeriesList(view, listOf(nutritionRecord))
        assertEquals(2, seriesList.size)

        assertEquals("Nutrition Energy", seriesList[0].label)
        assertEquals(430.0, seriesList[0].points.single().value, 0.001)
        assertEquals("kilocalories", seriesList[0].unit)

        assertEquals("Nutrition Protein", seriesList[1].label)
        assertEquals(28.0, seriesList[1].points.single().value, 0.001)
        assertEquals("grams", seriesList[1].unit)
    }

    private fun model(): HealthDataModel {
        return HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false
        )
    }

    private fun extractMeasurement(
        model: HealthDataModel,
        record: Record,
        unitPreference: UnitPreference
    ): ExtractedMeasurement {
        val method = HealthDataModel::class.java.getDeclaredMethod(
            "extractMeasurement",
            Record::class.java,
            UnitPreference::class.java
        )
        method.isAccessible = true
        val value = requireNotNull(method.invoke(model, record, unitPreference)) {
            "Expected a measurement from reflective extraction"
        }
        val valueField = value::class.java.getDeclaredField("value")
        val unitField = value::class.java.getDeclaredField("unitLabel")
        valueField.isAccessible = true
        unitField.isAccessible = true
        return ExtractedMeasurement(
            value = valueField.getDouble(value),
            unitLabel = unitField.get(value) as String?
        )
    }

    private data class ExtractedMeasurement(val value: Double, val unitLabel: String?)

}

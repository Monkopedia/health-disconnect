package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
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
    fun `reflective extraction prefers high-signal fields`() {
        val model = model()
        val instant = Instant.parse("2026-02-15T08:00:00Z")
        val record = ReflectiveSignalRecord(time = instant)
        val measurement = extractMeasurement(model, record, UnitPreference.METRIC)
        assertEquals(12.0, measurement.value, 0.001)
    }

    @Test
    fun `reflective extraction selects unit preference metric then imperial`() {
        val model = model()
        val instant = Instant.parse("2026-02-15T08:00:00Z")
        val record = ReflectiveUnitRecord(
            time = instant,
            metricValue = 70.0,
            imperialValue = 154.323
        )
        val metricMeasurement = extractMeasurement(model, record, UnitPreference.METRIC)
        assertEquals(70.0, metricMeasurement.value, 0.001)
        assertEquals("kilograms", metricMeasurement.unitLabel)

        val imperialMeasurement = extractMeasurement(model, record, UnitPreference.IMPERIAL)
        assertEquals(154.323, imperialMeasurement.value, 0.001)
        assertEquals("pounds", imperialMeasurement.unitLabel)
    }

    @Test
    fun `reflective extraction parses value from fallback string`() {
        val model = model()
        val instant = Instant.parse("2026-02-15T08:00:00Z")
        val record = ReflectiveStringRecord(
            time = instant,
            textValue = "72 bpm"
        )
        val measurement = extractMeasurement(model, record, UnitPreference.METRIC)
        assertEquals(72.0, measurement.value, 0.001)
        assertEquals("bpm", measurement.unitLabel)
    }

    @Test
    fun `reflective extraction falls back to samples when numeric values are provided as collections`() {
        val model = model()
        val instant = Instant.parse("2026-02-15T08:00:00Z")
        val record = ReflectiveSampleRecord(
            time = instant,
            samples = listOf(60.0, 80.0, 100.0)
        )
        val measurement = extractMeasurement(model, record, UnitPreference.METRIC)
        assertEquals(80.0, measurement.value, 0.001)
        assertEquals(null, measurement.unitLabel)
    }

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

    private class ReflectiveSignalRecord(
        private val time: Instant
    ) : Record {
        override val metadata: Metadata = Metadata.manualEntry()
        fun getTime(): Instant = time
        fun getDistanceMeasure(): Double = 12.0
        fun getMealType(): Double = 9999.0
    }

    private class ReflectiveUnitRecord(
        private val time: Instant,
        private val metricValue: Double,
        private val imperialValue: Double
    ) : Record {
        override val metadata: Metadata = Metadata.manualEntry()
        fun getTime(): Instant = time
        fun getMassCandidate(): UnitPreferenceValue = UnitPreferenceValue(
            metricValue,
            imperialValue
        )
    }

    private class UnitPreferenceValue(
        private val metricValue: Double,
        private val imperialValue: Double
    ) {
        fun inKilograms(): Double = metricValue
        fun inPounds(): Double = imperialValue
    }

    private class ReflectiveStringRecord(
        private val time: Instant,
        private val textValue: String
    ) : Record {
        override val metadata: Metadata = Metadata.manualEntry()
        fun getTime(): Instant = time
        fun getHeartRateText(): String = textValue
    }

    private class ReflectiveSampleRecord(
        private val time: Instant,
        private val samples: List<Double>
    ) : Record {
        override val metadata: Metadata = Metadata.manualEntry()
        fun getTime(): Instant = time
        fun getMeasurements(): List<SamplePoint> = samples.map(::SamplePoint)
    }

    private class SamplePoint(private val value: Double) {
        fun getRate(): Double = value
    }
}

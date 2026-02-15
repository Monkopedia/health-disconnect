package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Mass
import com.monkopedia.healthdisconnect.model.UnitPreference
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthRecordMeasurementExtractorTest {

    private val extractor: HealthRecordMeasurementExtractor = DefaultHealthRecordMeasurementExtractor()

    @Test
    fun `extractor supports static mapping values`() {
        val record: WeightRecord = mockk(relaxed = true)
        val instant = Instant.parse("2026-02-18T08:00:00Z")

        every { record.time } returns instant
        every { record.weight } returns Mass.kilograms(70.0)
        every { record.metadata } returns Metadata.manualEntry()

        val metric = requireNotNull(extractor.extractMeasurement(record, UnitPreference.IMPERIAL))
        assertEquals(154.323, metric.value, 0.001)
        assertEquals("pounds", metric.unitLabel)
    }

    @Test
    fun `extractor extracts blood glucose with unit preference`() {
        val record: BloodGlucoseRecord = mockk(relaxed = true)
        val instant = Instant.parse("2026-02-18T08:00:00Z")

        every { record.time } returns instant
        every { record.level } returns BloodGlucose.millimolesPerLiter(5.5)
        every { record.metadata } returns Metadata.manualEntry()

        val metric = requireNotNull(extractor.extractMeasurement(record, UnitPreference.METRIC))
        assertEquals(5.5, metric.value, 0.001)
        assertEquals("mmol/L", metric.unitLabel)
    }

    @Test
    fun `extractor falls back to explicit start and end time`() {
        val start = Instant.parse("2026-02-18T09:00:00Z")
        val end = start.plusSeconds(45 * 60)
        val record = DurationFallbackRecord(start, end)

        assertEquals(start, extractor.recordTimestamp(record))
    }

    private class DurationFallbackRecord(
        private val start: Instant,
        private val end: Instant
    ) : Record {
        override val metadata: Metadata = Metadata.manualEntry()
        fun getStartTime(): Instant = start
        fun getEndTime(): Instant = end
    }
}

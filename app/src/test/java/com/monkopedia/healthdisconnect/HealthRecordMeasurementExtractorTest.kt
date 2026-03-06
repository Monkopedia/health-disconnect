package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Pressure
import com.monkopedia.healthdisconnect.model.UnitPreference
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
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
    fun `extractor supports sleep duration and sleep stage metrics`() {
        val start = Instant.parse("2026-02-18T22:00:00Z")
        val end = start.plusSeconds(90 * 60)
        val sleepRecord = SleepSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(),
            title = "Nightly",
            notes = null,
            stages = listOf(
                SleepSessionRecord.Stage(
                    startTime = start,
                    endTime = end,
                    stage = SleepSessionRecord.STAGE_TYPE_DEEP
                )
            )
        )

        val duration = requireNotNull(
            extractor.extractMeasurement(sleepRecord, UnitPreference.METRIC)
        )
        assertEquals(90.0, duration.value, 0.001)
        assertEquals("minutes", duration.unitLabel)

        val stage = requireNotNull(
            extractor.extractMeasurement(
                sleepRecord,
                UnitPreference.METRIC,
                DefaultHealthRecordMeasurementExtractor.SLEEP_STAGE_METRIC_KEY
            )
        )
        assertEquals(5.0, stage.value, 0.001)
        assertEquals("stage", stage.unitLabel)
    }

    @Test
    fun `extractor supports blood pressure systolic and diastolic metrics`() {
        val instant = Instant.parse("2026-02-18T08:00:00Z")
        val record = BloodPressureRecord(
            time = instant,
            zoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(),
            systolic = Pressure.millimetersOfMercury(122.0),
            diastolic = Pressure.millimetersOfMercury(81.0)
        )

        val systolic = requireNotNull(extractor.extractMeasurement(record, UnitPreference.METRIC))
        assertEquals(122.0, systolic.value, 0.001)
        assertEquals("mmHg", systolic.unitLabel)

        val diastolic = requireNotNull(
            extractor.extractMeasurement(
                record,
                UnitPreference.METRIC,
                DefaultHealthRecordMeasurementExtractor.BLOOD_PRESSURE_DIASTOLIC_METRIC_KEY
            )
        )
        assertEquals(81.0, diastolic.value, 0.001)
        assertEquals("mmHg", diastolic.unitLabel)
    }

    @Test
    fun `extractor supports nutrition metric variants`() {
        val start = Instant.parse("2026-02-18T08:00:00Z")
        val end = start.plusSeconds(30 * 60)
        val record = NutritionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(),
            energy = Energy.kilocalories(500.0),
            energyFromFat = Energy.kilocalories(120.0),
            protein = Mass.grams(30.0),
            totalCarbohydrate = Mass.grams(55.0),
            totalFat = Mass.grams(15.0),
            sugar = Mass.grams(18.0),
            dietaryFiber = Mass.grams(7.0)
        )

        val energy = requireNotNull(extractor.extractMeasurement(record, UnitPreference.IMPERIAL))
        assertEquals(500.0, energy.value, 0.001)
        assertEquals("kilocalories", energy.unitLabel)

        val protein = requireNotNull(
            extractor.extractMeasurement(
                record,
                UnitPreference.METRIC,
                DefaultHealthRecordMeasurementExtractor.NUTRITION_PROTEIN_METRIC_KEY
            )
        )
        assertEquals(30.0, protein.value, 0.001)
        assertEquals("grams", protein.unitLabel)

        val sugar = requireNotNull(
            extractor.extractMeasurement(
                record,
                UnitPreference.IMPERIAL,
                DefaultHealthRecordMeasurementExtractor.NUTRITION_SUGAR_METRIC_KEY
            )
        )
        assertEquals(0.6349, sugar.value, 0.001)
        assertEquals("ounces", sugar.unitLabel)
    }

}

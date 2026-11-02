package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordPresentationFormattingTest {

    private val originalTimeZone = TimeZone.getDefault()

    // Details render instants in the device zone; pin it so the expected wall-clock text is fixed.
    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun formatAxisValue_usesThreeSignificantFigures() {
        assertEquals("12.3", formatAxisValue(12.3456))
        assertEquals("0.0123", formatAxisValue(0.0123456))
        assertEquals("1230", formatAxisValue(1234.56))
    }

    @Test
    fun formatValueWithUnit_abbreviatesCommonUnits() {
        assertEquals("154 lbs", formatValueWithUnit(154.323, "pounds"))
        assertEquals("70 kg", formatValueWithUnit(70.0, "kilograms"))
        assertEquals("5.5 mmol/L", formatValueWithUnit(5.5, "millimoles per liter"))
        assertEquals("23.5%", formatValueWithUnit(23.4567, "%"))
    }

    @Test
    fun formatValueWithUnit_formatsMinutesAsDuration() {
        assertEquals("59 mins", formatValueWithUnit(59.4, "minutes"))
        assertEquals("1 hr 30 mins", formatValueWithUnit(90.0, "minutes"))
        assertEquals("2 hrs 5 mins", formatValueWithUnit(125.0, "minutes"))
    }

    @Test
    fun recordDetailsText_formatsMeasurementLikeStrings() {
        val record = BodyFatRecord(
            percentage = Percentage(23.456789),
            time = Instant.parse("2026-02-23T10:00:00Z"),
            zoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry()
        )
        val details = recordDetailsText(record)
        assertTrue(details, details.contains("Percentage: 23.5%"))
    }

    @Test
    fun recordDetailsText_rendersCaffeineOnlyNutritionRecordInMilligrams() {
        val record = NutritionRecord(
            startTime = Instant.parse("2026-02-23T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-02-23T10:05:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            caffeine = Mass.grams(0.154),
            metadata = Metadata.manualEntry()
        )
        val details = recordDetailsText(record)
        assertTrue(details, details.contains("Caffeine: 154 mg"))
    }

    @Test
    fun recordDetailsText_rendersMicronutrientsInNaturalUnits() {
        val record = NutritionRecord(
            startTime = Instant.parse("2026-02-23T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-02-23T10:05:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            sodium = Mass.grams(1.5),
            vitaminD = Mass.micrograms(20.0),
            protein = Mass.grams(12.0),
            metadata = Metadata.manualEntry()
        )
        val details = recordDetailsText(record)
        assertTrue(details, details.contains("Sodium: 1500 mg"))
        assertTrue(details, details.contains("VitaminD: 20 mcg"))
        assertTrue(details, details.contains("Protein: 12 g"))
    }

    @Test
    fun recordDetailsText_rendersInstantAsDateTimeNotRoundedYear() {
        val record = WeightRecord(
            weight = Mass.kilograms(72.5),
            time = Instant.parse("2026-02-23T10:15:00Z"),
            zoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry()
        )
        val details = recordDetailsText(record)
        assertTrue(details, details.lines().contains("Time: Feb 23, 2026 10:15 AM"))
        assertFalse(details, details.contains("2030"))
    }

    @Test
    fun recordDetailsText_rendersStartAndEndTimesAsDateTimes() {
        val record = StepsRecord(
            startTime = Instant.parse("2026-02-23T10:00:00Z"),
            startZoneOffset = ZoneOffset.UTC,
            endTime = Instant.parse("2026-02-23T14:45:00Z"),
            endZoneOffset = ZoneOffset.UTC,
            count = 500,
            metadata = Metadata.manualEntry()
        )
        val lines = recordDetailsText(record).lines()
        assertTrue(lines.toString(), lines.contains("StartTime: Feb 23, 2026 10:00 AM"))
        assertTrue(lines.toString(), lines.contains("EndTime: Feb 23, 2026 2:45 PM"))
    }
}

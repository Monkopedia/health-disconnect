package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordPresentationFormattingTest {

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
}

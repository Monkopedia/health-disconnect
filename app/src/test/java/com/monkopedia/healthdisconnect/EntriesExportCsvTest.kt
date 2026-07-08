package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntriesExportCsvTest {
    @Test
    fun aggregatedCsvIncludesSeriesRowsAndSettings() {
        val view = DataView(
            id = 42,
            type = ViewType.CHART,
            records = listOf(RecordSelection(WeightRecord::class)),
            chartSettings = ChartSettings(timeWindow = TimeWindow.DAYS_30)
        )
        val series = listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "kilograms",
                points = listOf(
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 71.5),
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 2), 72.0)
                )
            )
        )

        val csv = buildAggregatedEntriesCsv(view, series)

        assertTrue(csv.contains("view_id,view_type,series_index"))
        assertTrue(csv.contains("42,CHART,0"))
        assertTrue(csv.contains("Weight,kilograms,2026-02-01,71.5"))
        assertTrue(csv.contains("DAYS_30"))
    }

    @Test
    fun aggregatedCsvHasBlankMinMaxColumnsForNonBandSeries() {
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(WeightRecord::class))
        )
        val series = listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "kilograms",
                points = listOf(HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 71.5))
            )
        )

        val csv = buildAggregatedEntriesCsv(view, series)
        val header = csv.lineSequence().first()

        assertTrue(header.contains("value,min_value,max_value,peak_value_in_window"))
        // value present, min/max blank -> two empty fields between value and peak.
        assertTrue(csv.contains(",2026-02-01,71.5,,,71.5,"))
    }

    @Test
    fun aggregatedCsvPopulatesMinMaxColumnsForBandSeries() {
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(WeightRecord::class))
        )
        val series = listOf(
            HealthDataModel.MetricSeries(
                label = "Weight",
                unit = "kilograms",
                points = listOf(HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 72.0)),
                bandMin = listOf(HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 70.0)),
                bandMax = listOf(HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 74.0))
            )
        )

        val csv = buildAggregatedEntriesCsv(view, series)

        assertTrue(csv.contains(",2026-02-01,72.0,70.0,74.0,"))
    }

    @Test
    fun rawCsvIncludesTypeTimestampAndFieldValues() {
        val record = mockk<WeightRecord>(relaxed = true)
        every { record.weight } returns Mass.kilograms(72.0)
        every { record.time } returns Instant.parse("2026-02-10T12:00:00Z")

        val csv = buildRawEntriesCsv(listOf(record))

        assertTrue(csv.contains("record_type,record_fqn,timestamp"))
        assertTrue(csv.contains("WeightRecord"))
        assertTrue(csv.contains("2026-02-10T12:00:00Z"))
        assertTrue(csv.contains("72"))
    }

    @Test
    fun rawCsvOmitsProvenanceColumnsWhenSourceIsStatic() {
        val records = listOf(
            weightRecord(72.0, "2026-02-10T12:00:00Z", origin = "com.example.app"),
            weightRecord(73.0, "2026-02-11T12:00:00Z", origin = "com.example.app")
        )

        val csv = buildRawEntriesCsv(records)
        val header = csv.lineSequence().first()

        assertFalse(header.contains("data_origin"))
        assertFalse(header.contains("device"))
    }

    @Test
    fun rawCsvIncludesDataOriginColumnWhenSourcesDiffer() {
        val records = listOf(
            weightRecord(72.0, "2026-02-10T12:00:00Z", origin = "com.example.appA"),
            weightRecord(73.0, "2026-02-11T12:00:00Z", origin = "com.example.appB")
        )

        val csv = buildRawEntriesCsv(records)
        val header = csv.lineSequence().first()

        assertTrue(header.contains("data_origin"))
        assertFalse(header.contains("device"))
        assertTrue(csv.contains("com.example.appA"))
        assertTrue(csv.contains("com.example.appB"))
    }

    @Test
    fun rawCsvIncludesDeviceColumnWhenDevicesDiffer() {
        val records = listOf(
            weightRecord(
                72.0,
                "2026-02-10T12:00:00Z",
                origin = "com.example.app",
                device = Device(
                    manufacturer = "Garmin",
                    model = "HRM-Pro",
                    type = Device.TYPE_CHEST_STRAP
                )
            ),
            weightRecord(
                73.0,
                "2026-02-11T12:00:00Z",
                origin = "com.example.app",
                device = Device(
                    manufacturer = "Polar",
                    model = "H10",
                    type = Device.TYPE_CHEST_STRAP
                )
            )
        )

        val csv = buildRawEntriesCsv(records)
        val header = csv.lineSequence().first()

        assertTrue(header.contains("device"))
        // Same writing app for both rows, so data_origin stays collapsed.
        assertFalse(header.contains("data_origin"))
        assertTrue(csv.contains("Garmin HRM-Pro chest_strap"))
        assertTrue(csv.contains("Polar H10 chest_strap"))
    }

    private fun weightRecord(
        kilograms: Double,
        time: String,
        origin: String = "",
        device: Device? = null
    ): WeightRecord {
        val record = mockk<WeightRecord>(relaxed = true)
        every { record.weight } returns Mass.kilograms(kilograms)
        every { record.time } returns Instant.parse(time)
        every { record.metadata } returns mockk<Metadata> {
            every { dataOrigin } returns DataOrigin(origin)
            every { this@mockk.device } returns device
        }
        return record
    }
}

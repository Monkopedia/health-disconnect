package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.WeightRecord
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
}

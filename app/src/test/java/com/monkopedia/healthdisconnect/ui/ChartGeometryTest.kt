package com.monkopedia.healthdisconnect.ui

import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.YAxisMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartGeometryTest {

    private val today = LocalDate.of(2026, 2, 22)

    private fun point(date: LocalDate, value: Double) = HealthDataModel.MetricPoint(date, value)

    private fun series(
        label: String,
        unit: String,
        points: List<HealthDataModel.MetricPoint>,
        yAxisMode: YAxisMode = YAxisMode.AUTO
    ) = HealthDataModel.MetricSeries(label = label, unit = unit, points = points, yAxisMode = yAxisMode)

    private fun geometry(
        seriesList: List<HealthDataModel.MetricSeries>,
        xAxis: ChartGeometry.XAxisMode = ChartGeometry.XAxisMode.DISCRETE_INDEX,
        settings: ChartSettings = ChartSettings()
    ) = ChartGeometry.create(seriesList, settings, xAxis, today)

    @Test
    fun create_returnsNull_forEmptySeries() {
        assertNull(geometry(emptyList()))
    }

    @Test
    fun create_returnsNull_whenNoPoints() {
        assertNull(geometry(listOf(series("Weight", "lb", emptyList()))))
    }

    @Test
    fun singleSeries_usesGlobalRange_normalizedAcrossFullRange() {
        val g = geometry(
            listOf(
                series(
                    "Weight", "lb",
                    listOf(
                        point(today.minusDays(2), 100.0),
                        point(today.minusDays(1), 150.0),
                        point(today, 200.0)
                    )
                )
            )
        )!!
        // AUTO range = [100, 200]; midpoint value maps to 0.5.
        assertEquals(0f, g.normalized(100.0, 0), 1e-6f)
        assertEquals(0.5f, g.normalized(150.0, 0), 1e-6f)
        assertEquals(1f, g.normalized(200.0, 0), 1e-6f)
    }

    @Test
    fun normalized_clampsOutOfRangeValues() {
        val g = geometry(
            listOf(series("Weight", "lb", listOf(point(today.minusDays(1), 10.0), point(today, 20.0))))
        )!!
        assertEquals(0f, g.normalized(-5.0, 0), 1e-6f)
        assertEquals(1f, g.normalized(999.0, 0), 1e-6f)
    }

    @Test
    fun startAtZero_yAxisMode_anchorsRangeMinToZero() {
        val g = geometry(
            listOf(
                series(
                    "Steps", "count",
                    listOf(point(today.minusDays(1), 4000.0), point(today, 8000.0)),
                    yAxisMode = YAxisMode.START_AT_ZERO
                )
            )
        )!!
        // min forced to 0 -> 4000 sits at half of [0, 8000].
        assertEquals(0.5f, g.normalized(4000.0, 0), 1e-6f)
        assertEquals(0f, g.normalized(0.0, 0), 1e-6f)
    }

    @Test
    fun multiSeries_usesSeparateNormalization_perSeriesRanges() {
        val steps = series(
            "Steps", "count",
            listOf(point(today.minusDays(1), 1000.0), point(today, 5000.0))
        )
        val weight = series(
            "Weight", "lb",
            listOf(point(today.minusDays(1), 200.0), point(today, 260.0))
        )
        val g = geometry(listOf(steps, weight))!!
        // Each series normalizes within its own range, not a shared one.
        assertEquals(0f, g.normalized(1000.0, 0), 1e-6f)
        assertEquals(1f, g.normalized(5000.0, 0), 1e-6f)
        assertEquals(0f, g.normalized(200.0, 1), 1e-6f)
        assertEquals(1f, g.normalized(260.0, 1), 1e-6f)
        // A value valid for series 1 would be off-scale (clamped) under series 0's range.
        assertEquals(0f, g.normalized(200.0, 0), 1e-6f)
    }

    @Test
    fun singleDistinctValue_centersVertically() {
        val g = geometry(
            listOf(series("Weight", "lb", listOf(point(today.minusDays(1), 70.0), point(today, 70.0))))
        )!!
        // Symmetric padded band around the lone value -> it sits at the vertical midpoint.
        assertEquals(0.5f, g.normalized(70.0, 0), 1e-6f)
    }

    @Test
    fun discreteIndex_xFraction_spacesDatesEvenlyByIndex() {
        val dates = (0..3).map { today.minusDays((3 - it).toLong()) }
        val g = geometry(
            listOf(series("Steps", "count", dates.map { point(it, 1.0) })),
            xAxis = ChartGeometry.XAxisMode.DISCRETE_INDEX
        )!!
        assertEquals(0f, g.xFraction(dates[0]), 1e-6f)
        assertEquals(1f / 3f, g.xFraction(dates[1]), 1e-6f)
        assertEquals(2f / 3f, g.xFraction(dates[2]), 1e-6f)
        assertEquals(1f, g.xFraction(dates[3]), 1e-6f)
    }

    @Test
    fun continuousDate_xFraction_isProportionalToCalendarPosition() {
        val start = today.minusDays(10)
        val g = geometry(
            listOf(
                series(
                    "Weight", "lb",
                    listOf(point(start, 1.0), point(today, 2.0))
                )
            ),
            xAxis = ChartGeometry.XAxisMode.CONTINUOUS_DATE,
            settings = ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.ALL)
        )!!
        // TimeWindow.ALL with no future data => axis spans [start, today]; midpoint date is 0.5.
        assertEquals(g.axisStart, start)
        assertEquals(g.axisEnd, today)
        assertEquals(10L, g.axisSpanDays)
        assertEquals(0f, g.xFraction(start), 1e-6f)
        assertEquals(1f, g.xFraction(today), 1e-6f)
        assertEquals(0.5f, g.xFraction(start.plusDays(5)), 1e-6f)
    }

    @Test
    fun continuousDate_axisExtendsToWindowStartAndToday() {
        val g = geometry(
            listOf(
                series(
                    "Weight", "lb",
                    listOf(point(today.minusDays(3), 1.0), point(today.minusDays(1), 2.0))
                )
            ),
            xAxis = ChartGeometry.XAxisMode.CONTINUOUS_DATE,
            settings = ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.DAYS_7)
        )!!
        // Window pulls the start back to today-7, and the end forward to today.
        assertEquals(today.minusDays(7), g.axisStart)
        assertEquals(today, g.axisEnd)
        assertEquals(7L, g.axisSpanDays)
    }

    @Test
    fun continuousDate_singleLinePoint_isCenteredHorizontally() {
        val g = geometry(
            listOf(series("Weight", "lb", listOf(point(today, 70.0)))),
            xAxis = ChartGeometry.XAxisMode.CONTINUOUS_DATE,
            settings = ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.ALL)
        )!!
        assertEquals(0.5f, g.xFraction(today), 1e-6f)
    }

    @Test
    fun axisSpan_isAtLeastOneDay_forDegenerateSingleDay() {
        val g = geometry(
            listOf(series("Weight", "lb", listOf(point(today, 70.0)))),
            xAxis = ChartGeometry.XAxisMode.CONTINUOUS_DATE,
            settings = ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.ALL)
        )!!
        assertTrue(g.axisSpanDays >= 1L)
        assertTrue(g.axisStart.isBefore(g.axisEnd))
        assertEquals(2L, ChronoUnit.DAYS.between(g.axisStart, g.axisEnd))
    }

    @Test
    fun pointFraction_yFractionFromTop_isInverseOfNormalized() {
        val g = geometry(
            listOf(series("Weight", "lb", listOf(point(today.minusDays(1), 0.0), point(today, 100.0))))
        )!!
        val pf = g.pointFraction(point(today, 100.0), 0)
        assertEquals(1f, pf.yNormalized, 1e-6f)
        assertEquals(0f, pf.yFractionFromTop, 1e-6f)
    }

    @Test
    fun axisLabelKind_followsSeriesCountBranch() {
        val one = geometry(listOf(series("A", "u", listOf(point(today, 1.0)))))!!
        val two = geometry(
            listOf(
                series("A", "u", listOf(point(today, 1.0))),
                series("B", "u", listOf(point(today, 2.0)))
            )
        )!!
        val three = geometry(
            listOf(
                series("A", "u", listOf(point(today, 1.0))),
                series("B", "u", listOf(point(today, 2.0))),
                series("C", "u", listOf(point(today, 3.0)))
            )
        )!!
        assertEquals(ChartGeometry.AxisLabelKind.SINGLE, one.axisLabelKind())
        assertEquals(ChartGeometry.AxisLabelKind.DUAL, two.axisLabelKind())
        assertEquals(ChartGeometry.AxisLabelKind.NORMALIZED, three.axisLabelKind())
    }

    @Test
    fun sortedDates_areDistinctAndAscending() {
        val g = geometry(
            listOf(
                series("A", "u", listOf(point(today, 1.0), point(today.minusDays(2), 2.0))),
                series("B", "u", listOf(point(today.minusDays(1), 3.0), point(today, 4.0)))
            )
        )!!
        assertEquals(listOf(today.minusDays(2), today.minusDays(1), today), g.sortedDates)
    }

    @Test
    fun create_succeeds_forTypicalSingleSeries() {
        assertNotNull(
            geometry(listOf(series("Weight", "lb", listOf(point(today, 70.0), point(today.minusDays(1), 71.0)))))
        )
    }
}

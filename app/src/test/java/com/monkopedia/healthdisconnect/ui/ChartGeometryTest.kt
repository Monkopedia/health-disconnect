package com.monkopedia.healthdisconnect.ui

import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.YAxisMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartGeometryTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 2, 22)
    private val nowInstant: Instant = today.atStartOfDay(zone).toInstant()

    /** The start-of-day instant a day-granular [MetricPoint] carries, for axis/xFraction asserts. */
    private fun LocalDate.startOfDay(): Instant = atStartOfDay(zone).toInstant()

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
    ) = ChartGeometry.create(seriesList, settings, xAxis, nowInstant, zone)

    @Test(expected = IllegalArgumentException::class)
    fun create_throws_forEmptySeries() {
        geometry(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun create_throws_whenNoPoints() {
        geometry(listOf(series("Weight", "lb", emptyList())))
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
        )
        // AUTO range = [100, 200]; midpoint value maps to 0.5.
        assertEquals(0f, g.normalized(100.0, 0), 1e-6f)
        assertEquals(0.5f, g.normalized(150.0, 0), 1e-6f)
        assertEquals(1f, g.normalized(200.0, 0), 1e-6f)
    }

    @Test
    fun normalized_clampsOutOfRangeValues() {
        val g = geometry(
            listOf(series("Weight", "lb", listOf(point(today.minusDays(1), 10.0), point(today, 20.0))))
        )
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
        )
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
        val g = geometry(listOf(steps, weight))
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
        )
        // Symmetric padded band around the lone value -> it sits at the vertical midpoint.
        assertEquals(0.5f, g.normalized(70.0, 0), 1e-6f)
    }

    @Test
    fun discreteIndex_xFraction_spacesDatesEvenlyByIndex() {
        val dates = (0..3).map { today.minusDays((3 - it).toLong()) }
        val g = geometry(
            listOf(series("Steps", "count", dates.map { point(it, 1.0) })),
            xAxis = ChartGeometry.XAxisMode.DISCRETE_INDEX
        )
        assertEquals(0f, g.xFraction(dates[0].startOfDay()), 1e-6f)
        assertEquals(1f / 3f, g.xFraction(dates[1].startOfDay()), 1e-6f)
        assertEquals(2f / 3f, g.xFraction(dates[2].startOfDay()), 1e-6f)
        assertEquals(1f, g.xFraction(dates[3].startOfDay()), 1e-6f)
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
        )
        // TimeWindow.ALL with no future data => axis spans [start, today]; midpoint date is 0.5.
        assertEquals(start.startOfDay(), g.axisStart)
        assertEquals(today.startOfDay(), g.axisEnd)
        assertEquals(10L, ChronoUnit.DAYS.between(g.axisStart, g.axisEnd))
        assertEquals(0f, g.xFraction(start.startOfDay()), 1e-6f)
        assertEquals(1f, g.xFraction(today.startOfDay()), 1e-6f)
        assertEquals(0.5f, g.xFraction(start.plusDays(5).startOfDay()), 1e-6f)
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
        )
        // Window pulls the start back to today-7, and the end forward to today.
        assertEquals(today.minusDays(7).startOfDay(), g.axisStart)
        assertEquals(today.startOfDay(), g.axisEnd)
        assertEquals(7L, ChronoUnit.DAYS.between(g.axisStart, g.axisEnd))
    }

    @Test
    fun continuousDate_singleLinePoint_isCenteredHorizontally() {
        val g = geometry(
            listOf(series("Weight", "lb", listOf(point(today, 70.0)))),
            xAxis = ChartGeometry.XAxisMode.CONTINUOUS_DATE,
            settings = ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.ALL)
        )
        assertEquals(0.5f, g.xFraction(today.startOfDay()), 1e-6f)
    }

    @Test
    fun axisSpan_isAtLeastOneDay_forDegenerateSingleDay() {
        val g = geometry(
            listOf(series("Weight", "lb", listOf(point(today, 70.0)))),
            xAxis = ChartGeometry.XAxisMode.CONTINUOUS_DATE,
            settings = ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.ALL)
        )
        assertTrue(g.axisSpanSeconds >= 1L)
        assertTrue(g.axisStart.isBefore(g.axisEnd))
        assertEquals(2L, ChronoUnit.DAYS.between(g.axisStart, g.axisEnd))
    }

    @Test
    fun pointFraction_yFractionFromTop_isInverseOfNormalized() {
        val g = geometry(
            listOf(series("Weight", "lb", listOf(point(today.minusDays(1), 0.0), point(today, 100.0))))
        )
        val pf = g.pointFraction(point(today, 100.0), 0)
        assertEquals(1f, pf.yNormalized, 1e-6f)
        assertEquals(0f, pf.yFractionFromTop, 1e-6f)
    }

    @Test
    fun axisLabelKind_followsSeriesCountBranch() {
        val one = geometry(listOf(series("A", "u", listOf(point(today, 1.0)))))
        val two = geometry(
            listOf(
                series("A", "u", listOf(point(today, 1.0))),
                series("B", "u", listOf(point(today, 2.0)))
            )
        )
        val three = geometry(
            listOf(
                series("A", "u", listOf(point(today, 1.0))),
                series("B", "u", listOf(point(today, 2.0))),
                series("C", "u", listOf(point(today, 3.0)))
            )
        )
        assertEquals(ChartGeometry.AxisLabelKind.SINGLE, one.axisLabelKind())
        assertEquals(ChartGeometry.AxisLabelKind.DUAL, two.axisLabelKind())
        assertEquals(ChartGeometry.AxisLabelKind.NORMALIZED, three.axisLabelKind())
    }

    @Test
    fun bandFor_isNull_whenSeriesHasNoBand() {
        val g = geometry(listOf(series("Weight", "lb", listOf(point(today, 70.0)))))
        assertEquals(null, g.bandFor(0))
    }

    @Test
    fun bandFor_sharesOneRangeSpanningWholeEnvelope() {
        val d0 = today.minusDays(1)
        val avg = listOf(point(d0, 150.0), point(today, 250.0))
        val bandMin = listOf(point(d0, 100.0), point(today, 200.0))
        val bandMax = listOf(point(d0, 200.0), point(today, 300.0))
        val bandSeries = HealthDataModel.MetricSeries(
            label = "Weight", unit = "lb", points = avg, bandMin = bandMin, bandMax = bandMax
        )
        val g = geometry(listOf(bandSeries))

        // Range spans [min(bandMin)=100, max(bandMax)=300], shared by line, min and max.
        assertEquals(0f, g.normalized(100.0, 0), 1e-6f)
        assertEquals(1f, g.normalized(300.0, 0), 1e-6f)
        // The avg (150) sits within that shared range, not normalized to its own [150,250].
        assertEquals(0.25f, g.normalized(150.0, 0), 1e-6f)

        val band = g.bandFor(0)!!
        assertEquals(2, band.edges.size)
        // yFractionFromTop: 0 = top (max value). First bucket: min=100 -> bottom, max=200 -> mid.
        assertEquals(1f, band.edges[0].minYFractionFromTop, 1e-6f)
        assertEquals(0.5f, band.edges[0].maxYFractionFromTop, 1e-6f)
        // x follows the same discrete-index layout as the line.
        assertEquals(g.xFraction(d0.startOfDay()), band.edges[0].x, 1e-6f)
        assertEquals(g.xFraction(today.startOfDay()), band.edges[1].x, 1e-6f)
    }

    @Test
    fun intraday_continuousAxis_positionsByTimeOfDayWithinTheHourWindow() {
        // Per-minute buckets over the last hour: the axis spans exactly the HOURS_1 window and each
        // minute lands at its own fraction (not collapsed onto one calendar-day slot).
        val t0 = nowInstant.minus(60, ChronoUnit.MINUTES)
        val points = (0..60 step 15).map {
            HealthDataModel.MetricPoint(t0.plus(it.toLong(), ChronoUnit.MINUTES), it.toDouble())
        }
        val g = ChartGeometry.create(
            listOf(HealthDataModel.MetricSeries(label = "HR", unit = "bpm", points = points)),
            ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.HOURS_1),
            ChartGeometry.XAxisMode.CONTINUOUS_DATE,
            nowInstant,
            zone
        )
        assertTrue(g.isIntraday)
        assertEquals(t0, g.axisStart)
        assertEquals(nowInstant, g.axisEnd)
        assertEquals(3600L, g.axisSpanSeconds)
        // The bucket 30 minutes into a 60-minute axis sits at the horizontal midpoint.
        assertEquals(0.5f, g.xFraction(t0.plus(30, ChronoUnit.MINUTES)), 1e-6f)
    }

    @Test
    fun intraday_endpointLabels_showTimeOfDay() {
        val start = today.atStartOfDay(zone).withHour(6).withMinute(15).toInstant()
        val end = today.atStartOfDay(zone).withHour(12).withMinute(0).toInstant()
        val points = listOf(
            HealthDataModel.MetricPoint(start, 60.0),
            HealthDataModel.MetricPoint(end, 80.0)
        )
        val g = ChartGeometry.create(
            listOf(HealthDataModel.MetricSeries(label = "HR", unit = "bpm", points = points)),
            ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.HOURS_6),
            ChartGeometry.XAxisMode.DISCRETE_INDEX,
            end,
            zone
        )
        // Sub-day window => time-of-day labels for the actual data range.
        assertEquals("6:15 AM" to "12:00 PM", g.dataRangeLabels(zone))
    }

    @Test
    fun intraday_endpointLabels_qualifyWithDate_whenSpanCrossesDayBoundary() {
        // A HOURS_24 axis spans exactly 24h, so its two endpoints share a wall-clock time one day
        // apart. Bare `h:mm a` would render them identically (`2:26 PM` … `2:26 PM`); the labels
        // must carry the date so the span is legible and reads chronologically start -> end.
        val end = today.atStartOfDay(zone).withHour(14).withMinute(26).toInstant()
        val start = end.minus(24, ChronoUnit.HOURS)
        val points = listOf(
            HealthDataModel.MetricPoint(start, 60.0),
            HealthDataModel.MetricPoint(end, 80.0)
        )
        val g = ChartGeometry.create(
            listOf(HealthDataModel.MetricSeries(label = "HR", unit = "bpm", points = points)),
            ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.HOURS_24),
            ChartGeometry.XAxisMode.CONTINUOUS_DATE,
            end,
            zone
        )
        val (startLabel, endLabel) = g.axisWindowLabels(zone)
        // The two ends differ and each carries a date qualifier (not the colliding bare time).
        assertTrue("labels must differ, got '$startLabel' == '$endLabel'", startLabel != endLabel)
        assertEquals("Feb 21, 2:26 PM", startLabel)
        assertEquals("Feb 22, 2:26 PM", endLabel)
    }

    @Test
    fun intraday_endpointLabels_stayTimeOnly_whenSpanWithinOneDay() {
        // HOURS_6 entirely within one local day keeps the compact time-of-day labels (no date).
        val start = today.atStartOfDay(zone).withHour(6).withMinute(15).toInstant()
        val end = today.atStartOfDay(zone).withHour(12).withMinute(0).toInstant()
        val points = listOf(
            HealthDataModel.MetricPoint(start, 60.0),
            HealthDataModel.MetricPoint(end, 80.0)
        )
        val g = ChartGeometry.create(
            listOf(HealthDataModel.MetricSeries(label = "HR", unit = "bpm", points = points)),
            ChartSettings(chartType = ChartType.LINE, timeWindow = TimeWindow.HOURS_6),
            ChartGeometry.XAxisMode.DISCRETE_INDEX,
            end,
            zone
        )
        assertEquals("6:15 AM" to "12:00 PM", g.dataRangeLabels(zone))
    }

    @Test
    fun dayGranular_endpointLabels_showCalendarDate() {
        val g = geometry(
            listOf(
                series(
                    "Steps", "count",
                    listOf(point(today.minusDays(3), 1.0), point(today, 2.0))
                )
            ),
            xAxis = ChartGeometry.XAxisMode.DISCRETE_INDEX,
            settings = ChartSettings(timeWindow = TimeWindow.DAYS_7)
        )
        assertEquals("Feb 19" to "Feb 22", g.dataRangeLabels(zone))
    }

    @Test
    fun sortedDates_areDistinctAndAscending() {
        val g = geometry(
            listOf(
                series("A", "u", listOf(point(today, 1.0), point(today.minusDays(2), 2.0))),
                series("B", "u", listOf(point(today.minusDays(1), 3.0), point(today, 4.0)))
            )
        )
        assertEquals(listOf(today.minusDays(2), today.minusDays(1), today), g.sortedDates)
    }

}

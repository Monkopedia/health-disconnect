package com.monkopedia.healthdisconnect.ui

import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.YAxisMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * Surface-independent chart geometry shared by every renderer (on-screen Compose chart,
 * share-as-PNG bitmap, home-screen widget bitmap). It owns the value ranges, normalization
 * (including the multi-series `useSeparateNormalization` branch), the axis-span computation,
 * the point → (x-fraction, y-fraction) mapping, and the axis-label range selection.
 *
 * This class contains **no** Android UI or Compose dependencies so it can be unit-tested
 * directly. Renderers translate the normalized (0..1) fractions this produces into their own
 * pixel coordinates and issue their own `drawLine`/`drawPath`/`drawText` calls; no chart
 * business logic should remain in an individual renderer.
 */
internal class ChartGeometry private constructor(
    val seriesList: List<HealthDataModel.MetricSeries>,
    val settings: ChartSettings,
    /** Distinct, ascending dates across every series. */
    val sortedDates: List<LocalDate>,
    private val dateIndex: Map<LocalDate, Int>,
    private val globalRange: ValueRange,
    private val perSeriesRanges: List<ValueRange>,
    private val useSeparateNormalization: Boolean,
    /**
     * Continuous date axis: the left edge (fraction 0) maps to this date, the right edge
     * (fraction 1) to [axisEnd]. Only meaningful when [xAxis] is [XAxisMode.CONTINUOUS_DATE].
     */
    val axisStart: LocalDate,
    val axisEnd: LocalDate,
    val axisSpanDays: Long,
    val xAxis: XAxisMode,
    private val singlePoint: Boolean
) {
    /** How the horizontal position of a point's date is computed. */
    enum class XAxisMode {
        /**
         * Continuous calendar axis: fraction = daysFromAxisStart / axisSpanDays. Used by the
         * on-screen line chart, which spans the view's whole time window so a single day still
         * sits within a real range.
         */
        CONTINUOUS_DATE,

        /**
         * Discrete slot axis: fraction = dateIndex / (dateCount - 1). Used by the bitmap
         * renderers (share + widget), which lay dates out at evenly-spaced indices.
         */
        DISCRETE_INDEX
    }

    /** Which axis-label set to draw, mirroring the historical 1 / 2 / 3+ series branch. */
    enum class AxisLabelKind { SINGLE, DUAL, NORMALIZED }

    val seriesCount: Int get() = seriesList.size

    /**
     * The min–max band around series [seriesIndex]'s avg line, as chart fractions on the same
     * Y-range as the line, or null when the series carries no band. Every renderer draws the band
     * from this so the fill/dashed-line geometry is defined once, not re-derived per surface.
     */
    fun bandFor(seriesIndex: Int): BandGeometry? {
        val series = seriesList[seriesIndex]
        val bandMin = series.bandMin ?: return null
        val bandMax = series.bandMax ?: return null
        val edges = series.points.indices.map { i ->
            val point = series.points[i]
            BandEdge(
                x = xFraction(point.date),
                minYFractionFromTop = 1f - normalized(bandMin[i].value, seriesIndex),
                maxYFractionFromTop = 1f - normalized(bandMax[i].value, seriesIndex)
            )
        }
        return BandGeometry(edges)
    }

    /** The value range used to normalize [seriesIndex]. */
    fun rangeFor(seriesIndex: Int): ValueRange =
        if (useSeparateNormalization) perSeriesRanges[seriesIndex] else globalRange

    /** Normalizes a raw value to 0..1 within its series' range. */
    fun normalized(value: Double, seriesIndex: Int): Float {
        val range = rangeFor(seriesIndex)
        return (((value - range.min) / (range.max - range.min)).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Horizontal position of [date] as a 0..1 fraction of the chart's plot width, honoring the
     * configured [xAxis]. A lone point is centered.
     */
    fun xFraction(date: LocalDate): Float {
        if (singlePoint) return 0.5f
        return when (xAxis) {
            XAxisMode.CONTINUOUS_DATE ->
                (ChronoUnit.DAYS.between(axisStart, date).toFloat() / axisSpanDays.toFloat())
                    .coerceIn(0f, 1f)
            XAxisMode.DISCRETE_INDEX -> {
                val index = dateIndex[date] ?: 0
                if (sortedDates.size > 1) index.toFloat() / (sortedDates.size - 1).toFloat() else 0f
            }
        }
    }

    /**
     * Point → (xFraction, 1 - yNormalized) where both are 0..1. Y is returned "top-down"
     * (0 = top of plot, 1 = bottom) so a renderer maps it directly as `top + yFraction * height`.
     */
    fun pointFraction(point: HealthDataModel.MetricPoint, seriesIndex: Int): PointFraction {
        val x = xFraction(point.date)
        val yNorm = normalized(point.value, seriesIndex)
        return PointFraction(x = x, yFractionFromTop = 1f - yNorm, yNormalized = yNorm)
    }

    /** Which axis-label block a renderer should draw, and the range/label associated with it. */
    fun axisLabelKind(): AxisLabelKind = when (seriesList.size) {
        1 -> AxisLabelKind.SINGLE
        2 -> AxisLabelKind.DUAL
        else -> AxisLabelKind.NORMALIZED
    }

    data class PointFraction(
        val x: Float,
        /** 0 at the top of the plot area, 1 at the bottom. */
        val yFractionFromTop: Float,
        /** 0 at the bottom of the plot area, 1 at the top (raw normalized value). */
        val yNormalized: Float
    )

    /** One min–max envelope column, as chart fractions on the series' shared Y-range. */
    data class BandEdge(
        val x: Float,
        /** Y of the bucket minimum, 0 = top of plot, 1 = bottom. */
        val minYFractionFromTop: Float,
        /** Y of the bucket maximum, 0 = top of plot, 1 = bottom. */
        val maxYFractionFromTop: Float
    )

    /** The full min–max envelope for a series, ordered left-to-right by date. */
    data class BandGeometry(val edges: List<BandEdge>)

    companion object {
        /**
         * Builds geometry for the given series. Every renderer already guards for an empty
         * chart before it gets here (nothing to plot), so this fails loudly rather than
         * returning null: a null slipping through would only mask a missing upstream guard.
         *
         * @param today reference "now" used only for the continuous-axis window computation.
         */
        fun create(
            seriesList: List<HealthDataModel.MetricSeries>,
            settings: ChartSettings,
            xAxis: XAxisMode,
            today: LocalDate = LocalDate.now()
        ): ChartGeometry {
            require(seriesList.isNotEmpty()) { "ChartGeometry.create requires at least one series" }
            val allPoints = seriesList.flatMap { it.points }
            require(allPoints.isNotEmpty()) { "ChartGeometry.create requires at least one point" }
            val sortedDates = allPoints.map { it.date }.distinct().sorted()

            val dateIndex = sortedDates.withIndex().associate { it.value to it.index }
            val useSeparateNormalization = seriesList.size > 1
            // A min/max/avg series must share ONE Y-range spanning its whole envelope so the min,
            // avg, and max all sit on the same scale (not per-line normalization).
            val globalRange = seriesRangeFromPoints(
                seriesList.flatMap { it.rangePoints() },
                seriesList.firstOrNull()?.yAxisMode ?: YAxisMode.AUTO
            )
            val perSeriesRanges = seriesList.map { series ->
                seriesRangeFromPoints(series.rangePoints(), series.yAxisMode)
            }

            val dataMin = sortedDates.first()
            val dataMax = sortedDates.last()
            val windowDays = when (settings.timeWindow) {
                TimeWindow.DAYS_7 -> 7L
                TimeWindow.DAYS_30 -> 30L
                TimeWindow.DAYS_90 -> 90L
                TimeWindow.YEAR_1 -> 365L
                TimeWindow.ALL -> null
            }
            var axisStart = if (windowDays != null) minOf(today.minusDays(windowDays), dataMin) else dataMin
            var axisEnd = maxOf(today, dataMax)
            if (!axisStart.isBefore(axisEnd)) {
                axisStart = dataMin.minusDays(1)
                axisEnd = dataMax.plusDays(1)
            }
            val axisSpanDays = ChronoUnit.DAYS.between(axisStart, axisEnd).coerceAtLeast(1L)

            // A lone point is centered horizontally only on the continuous line axis (matches the
            // on-screen renderer). Discrete-index surfaces keep their existing single-slot layout.
            val singlePoint = xAxis == XAxisMode.CONTINUOUS_DATE &&
                settings.chartType == ChartType.LINE &&
                allPoints.size == 1

            return ChartGeometry(
                seriesList = seriesList,
                settings = settings,
                sortedDates = sortedDates,
                dateIndex = dateIndex,
                globalRange = globalRange,
                perSeriesRanges = perSeriesRanges,
                useSeparateNormalization = useSeparateNormalization,
                axisStart = axisStart,
                axisEnd = axisEnd,
                axisSpanDays = axisSpanDays,
                xAxis = xAxis,
                singlePoint = singlePoint
            )
        }
    }
}

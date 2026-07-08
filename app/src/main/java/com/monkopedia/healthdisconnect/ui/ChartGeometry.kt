package com.monkopedia.healthdisconnect.ui

import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.YAxisMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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
    /** Distinct, ascending bucket instants across every series (the discrete-axis slots). */
    val sortedInstants: List<Instant>,
    private val instantIndex: Map<Instant, Int>,
    private val globalRange: ValueRange,
    private val perSeriesRanges: List<ValueRange>,
    private val useSeparateNormalization: Boolean,
    /**
     * Continuous time axis: the left edge (fraction 0) maps to this instant, the right edge
     * (fraction 1) to [axisEnd]. Only meaningful when [xAxis] is [XAxisMode.CONTINUOUS_DATE].
     * Positioning is by instant (not calendar day) so an intraday hour/minute bucket lands at its
     * own X coordinate; day-granular data still lands identically since its buckets sit on day
     * boundaries.
     */
    val axisStart: Instant,
    val axisEnd: Instant,
    val axisSpanSeconds: Long,
    /** True when the view's window is sub-day, driving the time-of-day axis labels. */
    val isIntraday: Boolean,
    val xAxis: XAxisMode,
    private val singlePoint: Boolean
) {
    /** How the horizontal position of a bucket instant is computed. */
    enum class XAxisMode {
        /**
         * Continuous time axis: fraction = secondsFromAxisStart / axisSpanSeconds. Used by the
         * on-screen line chart, which spans the view's whole time window so a single bucket still
         * sits within a real range and intraday hour/minute buckets land at their own X positions.
         */
        CONTINUOUS_DATE,

        /**
         * Discrete slot axis: fraction = instantIndex / (instantCount - 1). Used by the bitmap
         * renderers (share + widget), which lay buckets out at evenly-spaced indices.
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
                x = xFraction(point.instant),
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
     * Horizontal position of a bucket [instant] as a 0..1 fraction of the chart's plot width,
     * honoring the configured [xAxis]. A lone point is centered.
     */
    fun xFraction(instant: Instant): Float {
        if (singlePoint) return 0.5f
        return when (xAxis) {
            XAxisMode.CONTINUOUS_DATE ->
                (ChronoUnit.SECONDS.between(axisStart, instant).toFloat() / axisSpanSeconds.toFloat())
                    .coerceIn(0f, 1f)
            XAxisMode.DISCRETE_INDEX -> {
                val index = instantIndex[instant] ?: 0
                if (sortedInstants.size > 1) index.toFloat() / (sortedInstants.size - 1).toFloat() else 0f
            }
        }
    }

    /**
     * Point → (xFraction, 1 - yNormalized) where both are 0..1. Y is returned "top-down"
     * (0 = top of plot, 1 = bottom) so a renderer maps it directly as `top + yFraction * height`.
     */
    fun pointFraction(point: HealthDataModel.MetricPoint, seriesIndex: Int): PointFraction {
        val x = xFraction(point.instant)
        val yNorm = normalized(point.value, seriesIndex)
        return PointFraction(x = x, yFractionFromTop = 1f - yNorm, yNormalized = yNorm)
    }

    /**
     * The formatted start/end X-axis labels for [from]..[to] shared by every renderer. Sub-day
     * windows show time-of-day (e.g. `6:15 AM`, `12:00 PM`); day-granular windows show the calendar
     * date, widening to include the year only when the span crosses years. Built here so all three
     * surfaces label the intraday axis identically instead of each re-deriving the formatter.
     *
     * The continuous line chart passes the axis window ([axisStart]..[axisEnd]); the discrete-index
     * bitmaps pass their actual data range ([sortedInstants] first/last), matching how each lays out
     * the axis.
     *
     * @param zoneId the zone the instants are rendered in (system zone by default).
     */
    fun endpointLabels(
        from: Instant,
        to: Instant,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<String, String> {
        val start = from.atZone(zoneId)
        val end = to.atZone(zoneId)
        val formatter = when {
            // Intraday spans that stay within one local day read cleanly with just the clock time
            // (`6:15 AM` … `12:00 PM`). When the two ends land on different local dates — e.g. a
            // HOURS_24 window whose endpoints share a wall-clock time a day apart — the bare time
            // collides (`2:26 PM` … `2:26 PM`), so qualify each end with its date to keep the span
            // legible and chronological (`Feb 21, 2:26 PM` … `Feb 22, 2:26 PM`).
            isIntraday && start.toLocalDate() == end.toLocalDate() -> DateTimeFormatter.ofPattern("h:mm a")
            isIntraday -> DateTimeFormatter.ofPattern("MMM d, h:mm a")
            start.year != end.year -> DateTimeFormatter.ofPattern("MMM d, yyyy")
            else -> DateTimeFormatter.ofPattern("MMM d")
        }
        return start.format(formatter) to end.format(formatter)
    }

    /** Endpoint labels for the continuous line axis (the window span). */
    fun axisWindowLabels(zoneId: ZoneId = ZoneId.systemDefault()): Pair<String, String> =
        endpointLabels(axisStart, axisEnd, zoneId)

    /** Endpoint labels for the discrete-index bitmaps (the actual first/last data instants). */
    fun dataRangeLabels(zoneId: ZoneId = ZoneId.systemDefault()): Pair<String, String> =
        endpointLabels(sortedInstants.first(), sortedInstants.last(), zoneId)

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
         * @param now reference "now" used only for the continuous-axis window computation.
         * @param zoneId zone used to snap day-granular axis endpoints to local day boundaries.
         */
        fun create(
            seriesList: List<HealthDataModel.MetricSeries>,
            settings: ChartSettings,
            xAxis: XAxisMode,
            now: Instant = Instant.now(),
            zoneId: ZoneId = ZoneId.systemDefault()
        ): ChartGeometry {
            require(seriesList.isNotEmpty()) { "ChartGeometry.create requires at least one series" }
            val allPoints = seriesList.flatMap { it.points }
            require(allPoints.isNotEmpty()) { "ChartGeometry.create requires at least one point" }
            val sortedInstants = allPoints.map { it.instant }.distinct().sorted()
            val sortedDates = allPoints.map { it.date }.distinct().sorted()

            val instantIndex = sortedInstants.withIndex().associate { it.value to it.index }
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

            val isIntraday = settings.timeWindow.isIntraday
            val dataMin = sortedInstants.first()
            val dataMax = sortedInstants.last()
            // The continuous axis spans the view's time window so a single bucket still sits within
            // a real range. Day-granular windows snap the "now" edge to the end of the local day so
            // the axis endpoints stay on day boundaries and existing day-granular charts are
            // unchanged; intraday windows keep the exact instant so the time-of-day axis is precise.
            val windowSeconds = windowSeconds(settings.timeWindow)
            val axisNow = if (isIntraday) now else now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()
            var axisStart = if (windowSeconds != null) {
                minOf(axisNow.minusSeconds(windowSeconds), dataMin)
            } else {
                dataMin
            }
            var axisEnd = maxOf(axisNow, dataMax)
            if (!axisStart.isBefore(axisEnd)) {
                val pad = if (isIntraday) 60L else ChronoUnit.DAYS.duration.seconds
                axisStart = dataMin.minusSeconds(pad)
                axisEnd = dataMax.plusSeconds(pad)
            }
            val axisSpanSeconds = ChronoUnit.SECONDS.between(axisStart, axisEnd).coerceAtLeast(1L)

            // A lone point is centered horizontally only on the continuous line axis (matches the
            // on-screen renderer). Discrete-index surfaces keep their existing single-slot layout.
            val singlePoint = xAxis == XAxisMode.CONTINUOUS_DATE &&
                settings.chartType == ChartType.LINE &&
                allPoints.size == 1

            return ChartGeometry(
                seriesList = seriesList,
                settings = settings,
                sortedDates = sortedDates,
                sortedInstants = sortedInstants,
                instantIndex = instantIndex,
                globalRange = globalRange,
                perSeriesRanges = perSeriesRanges,
                useSeparateNormalization = useSeparateNormalization,
                axisStart = axisStart,
                axisEnd = axisEnd,
                axisSpanSeconds = axisSpanSeconds,
                isIntraday = isIntraday,
                xAxis = xAxis,
                singlePoint = singlePoint
            )
        }

        private fun windowSeconds(timeWindow: TimeWindow): Long? = when (timeWindow) {
            TimeWindow.HOURS_1 -> 3_600L
            TimeWindow.HOURS_3 -> 3 * 3_600L
            TimeWindow.HOURS_6 -> 6 * 3_600L
            TimeWindow.HOURS_24 -> 24 * 3_600L
            TimeWindow.DAYS_7 -> 7 * 86_400L
            TimeWindow.DAYS_30 -> 30 * 86_400L
            TimeWindow.DAYS_90 -> 90 * 86_400L
            TimeWindow.YEAR_1 -> 365 * 86_400L
            TimeWindow.ALL -> null
        }
    }
}

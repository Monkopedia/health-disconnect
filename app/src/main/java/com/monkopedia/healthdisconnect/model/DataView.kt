package com.monkopedia.healthdisconnect.model

import androidx.health.connect.client.records.Record
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

@Serializable
data class DataView(
    val id: Int = 0,
    val type: ViewType,
    val records: List<RecordSelection> = emptyList(),
    val chartSettings: ChartSettings = ChartSettings()
) {

    constructor(id: Int, fqn: KClass<out Record>) : this(
        id,
        ViewType.CHART,
        listOf(RecordSelection(fqn))
    )
}

@Serializable
enum class ViewType {
    CHART
}

@Serializable
data class ChartSettings(
    val aggregation: AggregationMode = AggregationMode.AVERAGE,
    val timeWindow: TimeWindow = TimeWindow.ALL,
    val bucketSize: BucketSize = BucketSize.DAY,
    val chartType: ChartType = ChartType.LINE,
    val showDataPoints: Boolean = false,
    val yAxisMode: YAxisMode = YAxisMode.AUTO,
    val smoothing: SmoothingMode = SmoothingMode.OFF,
    val unitPreference: UnitPreference = UnitPreference.AUTO,
    val backgroundStyle: ChartBackgroundStyle = ChartBackgroundStyle.HORIZONTAL_LINES,
    val widgetUpdateWindow: WidgetUpdateWindow = WidgetUpdateWindow.HOURS_12,
    val rangeDisplay: RangeDisplay = RangeDisplay.BAND
)

@Serializable
data class MetricChartSettings(
    val aggregation: AggregationMode = AggregationMode.AVERAGE,
    val timeWindow: TimeWindow = TimeWindow.ALL,
    val bucketSize: BucketSize = BucketSize.DAY,
    val yAxisMode: YAxisMode = YAxisMode.AUTO,
    val smoothing: SmoothingMode = SmoothingMode.OFF,
    val unitPreference: UnitPreference = UnitPreference.AUTO,
    val showMaxLabel: Boolean = true,
    val showMinLabel: Boolean = false,
    val rangeDisplay: RangeDisplay = RangeDisplay.BAND
)

@Serializable
enum class AggregationMode {
    AVERAGE,
    SUM,
    MIN,
    MAX,
    MIN_MAX_AVG
}

/** How a [AggregationMode.MIN_MAX_AVG] series draws its min–max envelope around the avg line. */
@Serializable
enum class RangeDisplay {
    BAND,
    LINES
}

@Serializable
enum class TimeWindow {
    HOURS_1,
    HOURS_3,
    HOURS_6,
    HOURS_24,
    DAYS_7,
    DAYS_30,
    DAYS_90,
    YEAR_1,
    ALL;

    /** True for the sub-day intraday spans, which drive the time-of-day axis in [ChartGeometry]. */
    val isIntraday: Boolean
        get() = this == HOURS_1 || this == HOURS_3 || this == HOURS_6 || this == HOURS_24
}

@Serializable
enum class BucketSize {
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH;

    /**
     * The time windows that make sense for this bucket size, ordered coarsest-last. This both
     * declutters the window menu and caps the resolved point count (~1,500 max) so the chart stays
     * legible/fast — there is no separate cap. When the bucket changes and the current window is no
     * longer here, callers snap to the nearest valid one via [nearestWindow].
     */
    fun windows(): List<TimeWindow> = when (this) {
        MINUTE -> listOf(TimeWindow.HOURS_1, TimeWindow.HOURS_3, TimeWindow.HOURS_6, TimeWindow.HOURS_24)
        HOUR -> listOf(TimeWindow.HOURS_24, TimeWindow.DAYS_7, TimeWindow.DAYS_30)
        DAY -> listOf(TimeWindow.DAYS_7, TimeWindow.DAYS_30, TimeWindow.DAYS_90, TimeWindow.YEAR_1, TimeWindow.ALL)
        WEEK -> listOf(TimeWindow.DAYS_90, TimeWindow.YEAR_1, TimeWindow.ALL)
        MONTH -> listOf(TimeWindow.YEAR_1, TimeWindow.ALL)
    }

    /**
     * The window in this bucket's [windows] closest to [current] by span, used to snap the
     * selection when a bucket change invalidates the current window. Picks the entry whose ordinal
     * distance to [current]'s position in the full [TimeWindow] order is smallest (ties → shorter).
     */
    fun nearestWindow(current: TimeWindow): TimeWindow {
        val options = windows()
        return options.firstOrNull { it == current }
            ?: options.minByOrNull { kotlin.math.abs(it.ordinal - current.ordinal) }
            ?: TimeWindow.ALL
    }
}

@Serializable
enum class ChartType {
    LINE,
    BARS
}

@Serializable
enum class YAxisMode {
    AUTO,
    START_AT_ZERO
}

@Serializable
enum class SmoothingMode {
    OFF,
    MOVING_AVERAGE_3
}

@Serializable
enum class UnitPreference {
    AUTO,
    METRIC,
    IMPERIAL
}

@Serializable
enum class ChartBackgroundStyle {
    NONE,
    HORIZONTAL_LINES,
    GRID
}

@Serializable
enum class WidgetUpdateWindow(val intervalHours: Long) {
    HOURS_3(3),
    HOURS_6(6),
    HOURS_12(12),
    HOURS_24(24);

    fun intervalMillis(): Long {
        return intervalHours * 60L * 60L * 1000L
    }
}

@Serializable
data class DataViewList(val views: Map<Int, DataView>)

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
    val widgetUpdateWindow: WidgetUpdateWindow = WidgetUpdateWindow.HOURS_12
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
    val showMinLabel: Boolean = false
)

@Serializable
enum class AggregationMode {
    AVERAGE,
    SUM,
    MIN,
    MAX
}

@Serializable
enum class TimeWindow {
    DAYS_7,
    DAYS_30,
    DAYS_90,
    YEAR_1,
    ALL
}

@Serializable
enum class BucketSize {
    DAY,
    WEEK,
    MONTH
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

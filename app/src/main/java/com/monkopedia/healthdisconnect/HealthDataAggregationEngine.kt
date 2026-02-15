package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.Record
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.YAxisMode
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass

interface HealthDataAggregationEngine {
    fun windowStart(timeWindow: TimeWindow, now: Instant): Instant?
    fun toBucketDate(date: LocalDate, bucketSize: BucketSize): LocalDate
    fun aggregateValues(values: List<Double>, mode: AggregationMode): Double
    fun smooth3(points: List<HealthDataModel.MetricPoint>): List<HealthDataModel.MetricPoint>
    fun metricDefaultsFromView(view: DataView): MetricChartSettings
    fun aggregateMetricSeriesList(view: DataView, records: List<Record>): List<HealthDataModel.MetricSeries>
    fun aggregateMetricSeries(view: DataView, records: List<Record>): HealthDataModel.MetricSeries?
    fun collectAggregatedSeries(
        view: DataView,
        now: Instant,
        maxSeries: Int,
        pageReader: suspend (
            cls: KClass<out Record>,
            start: Instant,
            end: Instant,
            onPage: (List<Record>) -> Unit
        ) -> Unit
    ): Flow<List<HealthDataModel.MetricSeries>>
}

class DefaultHealthDataAggregationEngine(
    private val measurementExtractor: HealthRecordMeasurementExtractor
) : HealthDataAggregationEngine {

    override fun windowStart(timeWindow: TimeWindow, now: Instant): Instant? {
        return when (timeWindow) {
            TimeWindow.DAYS_7 -> now.minus(7, ChronoUnit.DAYS)
            TimeWindow.DAYS_30 -> now.minus(30, ChronoUnit.DAYS)
            TimeWindow.DAYS_90 -> now.minus(90, ChronoUnit.DAYS)
            TimeWindow.YEAR_1 -> now.minus(365, ChronoUnit.DAYS)
            TimeWindow.ALL -> null
        }
    }

    override fun metricDefaultsFromView(view: DataView): MetricChartSettings {
        return MetricChartSettings(
            aggregation = view.chartSettings.aggregation,
            timeWindow = view.chartSettings.timeWindow,
            bucketSize = view.chartSettings.bucketSize,
            yAxisMode = view.chartSettings.yAxisMode,
            smoothing = view.chartSettings.smoothing,
            unitPreference = view.chartSettings.unitPreference
        )
    }

    override fun aggregateMetricSeriesList(view: DataView, records: List<Record>): List<HealthDataModel.MetricSeries> {
        if (records.isEmpty()) return emptyList()
        val settings = view.chartSettings
        val zoneId = ZoneId.systemDefault()
        val viewWindowStart = windowStart(settings.timeWindow, Instant.now())

        val selectedByType = view.records.mapNotNull { selection ->
            val cls = PermissionsViewModel.CLASSES.firstOrNull { it.qualifiedName == selection.fqn }
                ?: return@mapNotNull null
            val label = PermissionsViewModel.RECORD_NAMES[cls] ?: cls.simpleName ?: selection.fqn
            cls to label
        }

        if (selectedByType.isEmpty()) return emptyList()

        return selectedByType
            .mapNotNull { (cls, label) ->
                val selection = view.records.firstOrNull { it.fqn == cls.qualifiedName }
                    ?: return@mapNotNull null
                val metricSettings = selection.metricSettings ?: metricDefaultsFromView(view)
                val measurements = records
                    .asSequence()
                    .filter { cls.isInstance(it) }
                    .mapNotNull { extractMeasurement(it, metricSettings.unitPreference) }
                    .filter { measurement ->
                        viewWindowStart == null || !measurement.timestamp.isBefore(viewWindowStart)
                    }
                    .toList()
                if (measurements.isEmpty()) return@mapNotNull null

                val grouped = measurements.groupBy {
                    toBucketDate(measurementTimestampDate(it.timestamp, zoneId), view.chartSettings.bucketSize)
                }
                val aggregated = grouped
                    .toSortedMap()
                    .map { (date, values) ->
                        val value = aggregateValues(values.map { it.value }, metricSettings.aggregation)
                        HealthDataModel.MetricPoint(date = date, value = value)
                    }
                val points = when (metricSettings.smoothing) {
                    com.monkopedia.healthdisconnect.model.SmoothingMode.OFF -> aggregated
                    com.monkopedia.healthdisconnect.model.SmoothingMode.MOVING_AVERAGE_3 -> smooth3(aggregated)
                }
                HealthDataModel.MetricSeries(
                    label = label,
                    unit = measurements.firstNotNullOfOrNull { it.unitLabel },
                    points = points,
                    peakValueInWindow = measurements.maxOf { it.value },
                    yAxisMode = metricSettings.yAxisMode
                )
            }
            .take(HealthDataModel.MAX_CHART_SERIES)
    }

    override fun aggregateMetricSeries(view: DataView, records: List<Record>): HealthDataModel.MetricSeries? {
        return aggregateMetricSeriesList(view, records).firstOrNull()
    }

    override fun collectAggregatedSeries(
        view: DataView,
        now: Instant,
        maxSeries: Int,
        pageReader: suspend (
            cls: KClass<out Record>,
            start: Instant,
            end: Instant,
            onPage: (List<Record>) -> Unit
        ) -> Unit
    ): Flow<List<HealthDataModel.MetricSeries>> = channelFlow {
        val zoneId = ZoneId.systemDefault()
        val viewWindowStart = windowStart(view.chartSettings.timeWindow, now)
        val selectedByType = view.records.mapNotNull { selection ->
            val cls = PermissionsViewModel.CLASSES.firstOrNull { it.qualifiedName == selection.fqn }
                ?: return@mapNotNull null
            val label = PermissionsViewModel.RECORD_NAMES[cls] ?: cls.simpleName ?: selection.fqn
            val metricSettings = selection.metricSettings ?: metricDefaultsFromView(view)
            StreamingMetricState(
                recordClass = cls,
                label = label,
                metricSettings = metricSettings
            )
        }.take(maxSeries)

        if (selectedByType.isEmpty()) {
            trySend(emptyList())
            return@channelFlow
        }

        val queryStart = viewWindowStart ?: Instant.EPOCH
        selectedByType.forEach { metricState ->
            runCatching {
                pageReader(metricState.recordClass, queryStart, now) { pageRecords ->
                    pageRecords.forEach { record ->
                        val measurement = extractMeasurement(
                            record,
                            metricState.metricSettings.unitPreference
                        ) ?: return@forEach
                        if (viewWindowStart != null && measurement.timestamp.isBefore(viewWindowStart)) {
                            return@forEach
                        }
                        if (metricState.unit == null && !measurement.unitLabel.isNullOrBlank()) {
                            metricState.unit = measurement.unitLabel
                        }
                        metricState.peakValue = when (val current = metricState.peakValue) {
                            null -> measurement.value
                            else -> kotlin.math.max(current, measurement.value)
                        }
                        val bucketDate = toBucketDate(
                            measurementTimestampDate(measurement.timestamp, zoneId),
                            metricState.metricSettings.bucketSize
                        )
                        val bucket = metricState.buckets.getOrPut(bucketDate) { BucketAccumulator() }
                        bucket.add(measurement.value)
                    }
                    trySend(buildStreamingSeries(selectedByType))
                }
            }
        }
        trySend(buildStreamingSeries(selectedByType))
    }

    override fun toBucketDate(date: LocalDate, bucketSize: BucketSize): LocalDate {
        return when (bucketSize) {
            BucketSize.DAY -> date
            BucketSize.WEEK -> date.with(DayOfWeek.MONDAY)
            BucketSize.MONTH -> date.withDayOfMonth(1)
        }
    }

    override fun aggregateValues(values: List<Double>, mode: AggregationMode): Double {
        if (values.isEmpty()) return 0.0
        return when (mode) {
            AggregationMode.AVERAGE -> values.average()
            AggregationMode.SUM -> values.sum()
            AggregationMode.MIN -> values.minOrNull() ?: 0.0
            AggregationMode.MAX -> values.maxOrNull() ?: 0.0
        }
    }

    override fun smooth3(points: List<HealthDataModel.MetricPoint>): List<HealthDataModel.MetricPoint> {
        if (points.size < 3) return points
        return points.mapIndexed { index, point ->
            val from = (index - 1).coerceAtLeast(0)
            val to = (index + 1).coerceAtMost(points.lastIndex)
            val average = points.subList(from, to + 1).map { it.value }.average()
            point.copy(value = average)
        }
    }

    private fun extractMeasurement(
        record: Record,
        unitPreference: UnitPreference
    ): MetricMeasurement? {
        return measurementExtractor.extractMeasurement(record, unitPreference)
    }

    private fun measurementTimestampDate(timestamp: Instant, zoneId: ZoneId): LocalDate {
        return timestamp.atZone(zoneId).toLocalDate()
    }

    private fun buildStreamingSeries(states: List<StreamingMetricState>): List<HealthDataModel.MetricSeries> {
        return states.mapNotNull { state ->
            if (state.buckets.isEmpty()) return@mapNotNull null
            val aggregated = state.buckets
                .toSortedMap()
                .map { (date, bucket) ->
                    HealthDataModel.MetricPoint(
                        date = date,
                        value = bucket.aggregated(state.metricSettings.aggregation)
                    )
                }
            val points = when (state.metricSettings.smoothing) {
                com.monkopedia.healthdisconnect.model.SmoothingMode.OFF -> aggregated
                com.monkopedia.healthdisconnect.model.SmoothingMode.MOVING_AVERAGE_3 -> smooth3(aggregated)
            }
            HealthDataModel.MetricSeries(
                label = state.label,
                unit = state.unit,
                points = points,
                peakValueInWindow = state.peakValue ?: 0.0,
                yAxisMode = state.metricSettings.yAxisMode
            )
        }
    }

    private data class BucketAccumulator(
        var sum: Double = 0.0,
        var count: Int = 0,
        var min: Double = Double.POSITIVE_INFINITY,
        var max: Double = Double.NEGATIVE_INFINITY
    ) {
        fun add(value: Double) {
            sum += value
            count += 1
            min = kotlin.math.min(min, value)
            max = kotlin.math.max(max, value)
        }

        fun aggregated(mode: AggregationMode): Double {
            if (count == 0) return 0.0
            return when (mode) {
                AggregationMode.AVERAGE -> sum / count
                AggregationMode.SUM -> sum
                AggregationMode.MIN -> min
                AggregationMode.MAX -> max
            }
        }
    }

    private data class StreamingMetricState(
        val recordClass: KClass<out Record>,
        val label: String,
        val metricSettings: MetricChartSettings,
        var unit: String? = null,
        var peakValue: Double? = null,
        val buckets: MutableMap<LocalDate, BucketAccumulator> = mutableMapOf()
    )
}

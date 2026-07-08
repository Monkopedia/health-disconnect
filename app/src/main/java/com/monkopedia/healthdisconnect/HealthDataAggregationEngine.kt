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
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass
import java.util.TreeMap
import android.util.Log

interface HealthDataAggregationEngine {
    fun windowStart(timeWindow: TimeWindow, now: Instant): Instant?
    fun toBucketInstant(timestamp: Instant, bucketSize: BucketSize, zoneId: ZoneId): Instant
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
            TimeWindow.HOURS_1 -> now.minus(1, ChronoUnit.HOURS)
            TimeWindow.HOURS_3 -> now.minus(3, ChronoUnit.HOURS)
            TimeWindow.HOURS_6 -> now.minus(6, ChronoUnit.HOURS)
            TimeWindow.HOURS_24 -> now.minus(24, ChronoUnit.HOURS)
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
            unitPreference = view.chartSettings.unitPreference,
            showMaxLabel = true,
            showMinLabel = false,
            rangeDisplay = view.chartSettings.rangeDisplay
        )
    }

    override fun aggregateMetricSeriesList(view: DataView, records: List<Record>): List<HealthDataModel.MetricSeries> {
        if (records.isEmpty()) return emptyList()
        val settings = view.chartSettings
        val zoneId = ZoneId.systemDefault()
        val viewWindowStart = windowStart(settings.timeWindow, Instant.now())

        val selectedMetrics = view.records.mapNotNull { selection ->
            val cls = PermissionsViewModel.classForFqn(selection.fqn)
                ?: return@mapNotNull null
            val baseLabel = PermissionsViewModel.recordLabel(cls)
            val label = measurementExtractor.metricLabel(cls, selection.metricKey) ?: baseLabel
            AggregationMetricSelection(
                recordClass = cls,
                label = label,
                metricKey = selection.metricKey,
                metricSettings = selection.metricSettings ?: metricDefaultsFromView(view)
            )
        }

        if (selectedMetrics.isEmpty()) return emptyList()

        return selectedMetrics
            .mapNotNull { selection ->
                val measurements = records
                    .asSequence()
                    .filter { selection.recordClass.isInstance(it) }
                    .mapNotNull {
                        extractMeasurement(
                            record = it,
                            unitPreference = selection.metricSettings.unitPreference,
                            metricKey = selection.metricKey
                        )
                    }
                    .filter { measurement ->
                        viewWindowStart == null || !measurement.timestamp.isBefore(viewWindowStart)
                    }
                    .toList()
                if (measurements.isEmpty()) return@mapNotNull null

                val mode = selection.metricSettings.aggregation
                val grouped = measurements
                    .groupBy {
                        toBucketInstant(it.timestamp, selection.metricSettings.bucketSize, zoneId)
                    }
                    .toSortedMap()
                val aggregated = grouped.map { (instant, values) ->
                    HealthDataModel.MetricPoint(
                        instant = instant,
                        value = aggregateValues(values = values.map { it.value }, mode = mode)
                    )
                }
                val band = bandFor(mode) {
                    grouped.map { (instant, values) ->
                        instant to (values.minOf { it.value } to values.maxOf { it.value })
                    }
                }
                buildMetricSeries(
                    label = selection.label,
                    unit = measurements.firstNotNullOfOrNull { it.unitLabel },
                    aggregated = aggregated,
                    band = band,
                    peakValue = measurements.maxOf { it.value },
                    minValue = measurements.minOf { it.value },
                    settings = selection.metricSettings
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
            val cls = PermissionsViewModel.classForFqn(selection.fqn)
                ?: return@mapNotNull null
            val baseLabel = PermissionsViewModel.recordLabel(cls)
            val label = measurementExtractor.metricLabel(cls, selection.metricKey) ?: baseLabel
            StreamingMetricState(
                recordClass = cls,
                label = label,
                metricSettings = selection.metricSettings ?: metricDefaultsFromView(view),
                metricKey = selection.metricKey
            )
        }.take(maxSeries)

        if (selectedByType.isEmpty()) {
            trySend(emptyList())
            return@channelFlow
        }

        val queryStart = viewWindowStart ?: Instant.EPOCH
        selectedByType.groupBy { it.recordClass }.forEach { (recordClass, statesForClass) ->
            try {
                pageReader(recordClass, queryStart, now) { pageRecords ->
                    pageRecords.forEach { record ->
                        statesForClass.forEach { metricState ->
                            val measurement = extractMeasurement(
                                record = record,
                                unitPreference = metricState.metricSettings.unitPreference,
                                metricKey = metricState.metricKey
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
                            metricState.minValue = when (val current = metricState.minValue) {
                                null -> measurement.value
                                else -> kotlin.math.min(current, measurement.value)
                            }
                            val bucketInstant = toBucketInstant(
                                measurement.timestamp,
                                metricState.metricSettings.bucketSize,
                                zoneId
                            )
                            val bucket = metricState.buckets.getOrPut(bucketInstant) { BucketAccumulator() }
                            bucket.add(measurement.value)
                        }
                    }
                    trySend(buildStreamingSeries(selectedByType))
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                Log.w(
                    LOG_TAG,
                    "Failed to read aggregation data for ${recordClass.qualifiedName}",
                    exception
                )
            }
        }
        trySend(buildStreamingSeries(selectedByType))
    }

    companion object {
        private const val LOG_TAG = "HealthDataAggregation"
    }

    /**
     * The start [Instant] of the bucket that [timestamp] falls into, truncating to the bucket's
     * granularity in [zoneId]. Day/week/month truncation happens on the calendar (so a bucket is a
     * real local day/week/month, not a fixed 24h/168h slice) which keeps it correct across DST: the
     * result is re-resolved to an instant from the local date-time, so a bucket boundary is the
     * local midnight even when that day is 23 or 25 hours long. Hour/minute truncate the local
     * time-of-day the same way.
     */
    override fun toBucketInstant(timestamp: Instant, bucketSize: BucketSize, zoneId: ZoneId): Instant {
        val local = timestamp.atZone(zoneId)
        val truncated = when (bucketSize) {
            BucketSize.MINUTE -> local.truncatedTo(ChronoUnit.MINUTES)
            BucketSize.HOUR -> local.truncatedTo(ChronoUnit.HOURS)
            BucketSize.DAY -> local.toLocalDate().atStartOfDay(zoneId)
            BucketSize.WEEK -> local.toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay(zoneId)
            BucketSize.MONTH -> local.toLocalDate().withDayOfMonth(1).atStartOfDay(zoneId)
        }
        return truncated.toInstant()
    }

    override fun aggregateValues(values: List<Double>, mode: AggregationMode): Double {
        if (values.isEmpty()) return 0.0
        return when (mode) {
            // MIN_MAX_AVG plots the average as its line; the min/max form the band around it.
            AggregationMode.AVERAGE, AggregationMode.MIN_MAX_AVG -> values.average()
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
        unitPreference: UnitPreference,
        metricKey: String?
    ): MetricMeasurement? {
        return measurementExtractor.extractMeasurement(record, unitPreference, metricKey)
    }

    private fun buildStreamingSeries(states: List<StreamingMetricState>): List<HealthDataModel.MetricSeries> {
        return states.mapNotNull { state ->
            if (state.buckets.isEmpty()) return@mapNotNull null
            val mode = state.metricSettings.aggregation
            val instants = state.buckets.keys.toList()
            val aggregated = instants.map { instant ->
                HealthDataModel.MetricPoint(instant = instant, value = state.buckets.getValue(instant).aggregated(mode))
            }
            val band = bandFor(mode) {
                instants.map { instant ->
                    val bucket = state.buckets.getValue(instant)
                    instant to (bucket.min to bucket.max)
                }
            }
            buildMetricSeries(
                label = state.label,
                unit = state.unit,
                aggregated = aggregated,
                band = band,
                peakValue = state.peakValue ?: 0.0,
                minValue = state.minValue ?: 0.0,
                settings = state.metricSettings
            )
        }
    }

    /**
     * For [AggregationMode.MIN_MAX_AVG] materializes the per-bucket (min, max) envelope from
     * [entries]; null for every other mode. Kept in one place so the streaming and the batch
     * aggregation paths produce identical band shapes.
     */
    private inline fun bandFor(
        mode: AggregationMode,
        entries: () -> List<Pair<Instant, Pair<Double, Double>>>
    ): Pair<List<HealthDataModel.MetricPoint>, List<HealthDataModel.MetricPoint>>? {
        if (mode != AggregationMode.MIN_MAX_AVG) return null
        val bucketBounds = entries()
        val min = bucketBounds.map { (instant, bounds) -> HealthDataModel.MetricPoint(instant, bounds.first) }
        val max = bucketBounds.map { (instant, bounds) -> HealthDataModel.MetricPoint(instant, bounds.second) }
        return min to max
    }

    /**
     * Assembles a [HealthDataModel.MetricSeries], applying the configured smoothing uniformly to
     * the avg line and (when present) both band edges so the envelope stays aligned with the line.
     */
    private fun buildMetricSeries(
        label: String,
        unit: String?,
        aggregated: List<HealthDataModel.MetricPoint>,
        band: Pair<List<HealthDataModel.MetricPoint>, List<HealthDataModel.MetricPoint>>?,
        peakValue: Double,
        minValue: Double,
        settings: MetricChartSettings
    ): HealthDataModel.MetricSeries {
        fun applySmoothing(points: List<HealthDataModel.MetricPoint>): List<HealthDataModel.MetricPoint> =
            when (settings.smoothing) {
                com.monkopedia.healthdisconnect.model.SmoothingMode.OFF -> points
                com.monkopedia.healthdisconnect.model.SmoothingMode.MOVING_AVERAGE_3 -> smooth3(points)
            }
        return HealthDataModel.MetricSeries(
            label = label,
            unit = unit,
            points = applySmoothing(aggregated),
            peakValueInWindow = peakValue,
            minValueInWindow = minValue,
            showMaxLabel = settings.showMaxLabel,
            showMinLabel = settings.showMinLabel,
            yAxisMode = settings.yAxisMode,
            bandMin = band?.first?.let(::applySmoothing),
            bandMax = band?.second?.let(::applySmoothing)
        )
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
                // MIN_MAX_AVG plots the average as its line; the min/max form the band around it.
                AggregationMode.AVERAGE, AggregationMode.MIN_MAX_AVG -> sum / count
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
        val metricKey: String?,
        var unit: String? = null,
        var peakValue: Double? = null,
        var minValue: Double? = null,
        val buckets: TreeMap<Instant, BucketAccumulator> = TreeMap()
    )

    private data class AggregationMetricSelection(
        val recordClass: KClass<out Record>,
        val label: String,
        val metricKey: String?,
        val metricSettings: MetricChartSettings
    )
}

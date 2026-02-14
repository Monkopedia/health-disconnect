package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.SmoothingMode
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.RecordSelection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class HealthDataModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val MAX_CHART_SERIES = 3
    }

    val healthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }
    val metricsWithData = MutableStateFlow<List<KClass<out Record>>?>(null)

    init {
        viewModelScope.launch {
            refreshMetricsWithData()
        }
    }

    suspend fun refreshMetricsWithData() {
        val counts = coroutineScope {
            PermissionsViewModel.CLASSES.map {
                async {
                    val records = healthConnectClient.readRecords(
                        ReadRecordsRequest(
                            it,
                            TimeRangeFilter.before(Instant.now()),
                            pageSize = 1
                        )
                    ).records
                    it to records.isNotEmpty()
                }
            }.awaitAll()
        }
        metricsWithData.value = counts.filter { it.second }.map { it.first }
    }

    fun collectData(view: DataView, refreshTick: Int = 0): Flow<List<Record>> = flow {
        @Suppress("UNUSED_VARIABLE")
        val ignoredRefreshTick = refreshTick
        val typeMap: Map<String, KClass<out Record>> =
            PermissionsViewModel.CLASSES.associateBy { it.qualifiedName ?: "" }
        val selections: List<KClass<out Record>> = view.records.mapNotNull { sel: RecordSelection ->
            typeMap[sel.fqn]
        }
        val now = Instant.now()
        val all = mutableListOf<Record>()
        for (cls in selections) {
            try {
                all.addAll(readAllRecords(cls, now))
            } catch (_: Throwable) {
                // Ignore errors per type to keep UI responsive
            }
        }
        emit(all)
    }

    private suspend fun readAllRecords(
        cls: KClass<out Record>,
        now: Instant,
        pageSize: Int = 500
    ): List<Record> {
        val all = mutableListOf<Record>()
        var pageToken: String? = null
        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = cls,
                    timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, now),
                    pageSize = pageSize,
                    pageToken = pageToken
                )
            )
            @Suppress("UNCHECKED_CAST")
            all.addAll(response.records as List<Record>)
            pageToken = response.pageToken
        } while (pageToken != null)
        return all
    }

    data class MetricPoint(val date: LocalDate, val value: Double)

    data class MetricSeries(
        val label: String,
        val unit: String?,
        val points: List<MetricPoint>
    )

    fun aggregateMetricSeriesList(view: DataView, records: List<Record>): List<MetricSeries> {
        if (records.isEmpty()) return emptyList()
        val settings = view.chartSettings
        val zoneId = ZoneId.systemDefault()
        val windowStart = windowStart(settings.timeWindow)

        val selectedByType = view.records.mapNotNull { selection ->
            val cls = PermissionsViewModel.CLASSES.firstOrNull { it.qualifiedName == selection.fqn }
                ?: return@mapNotNull null
            val label = PermissionsViewModel.RECORD_NAMES[cls] ?: cls.simpleName ?: selection.fqn
            cls to label
        }

        if (selectedByType.isEmpty()) return emptyList()

        return selectedByType
            .mapNotNull { (cls, label) ->
            val measurements = records
                .asSequence()
                .filter { cls.isInstance(it) }
                .mapNotNull { extractMeasurement(it, settings.unitPreference) }
                .filter { measurement -> windowStart == null || !measurement.timestamp.isBefore(windowStart) }
                .toList()
            if (measurements.isEmpty()) return@mapNotNull null

            val grouped = measurements.groupBy {
                toBucketDate(it.timestamp.atZone(zoneId).toLocalDate(), settings.bucketSize)
            }
            val aggregated = grouped
                .toSortedMap()
                .map { (date, values) ->
                    val value = aggregateValues(values.map { it.value }, settings.aggregation)
                    MetricPoint(date = date, value = value)
                }
            val points = when (settings.smoothing) {
                SmoothingMode.OFF -> aggregated
                SmoothingMode.MOVING_AVERAGE_3 -> smooth3(aggregated)
            }
            MetricSeries(
                label = label,
                unit = measurements.firstNotNullOfOrNull { it.unitLabel },
                points = points
            )
        }.take(MAX_CHART_SERIES)
    }

    fun aggregateMetricSeries(view: DataView, records: List<Record>): MetricSeries? {
        return aggregateMetricSeriesList(view, records).firstOrNull()
    }

    private data class MetricMeasurement(
        val timestamp: Instant,
        val value: Double,
        val unitLabel: String?
    )

    private data class CandidateMeasurement(
        val value: Double,
        val unitLabel: String?,
        val score: Int
    )

    private fun extractMeasurement(record: Record, unitPreference: UnitPreference): MetricMeasurement? {
        val timestamp = recordTimestamp(record) ?: return null

        // Prefer strongly-typed extraction for common metrics so values like weight are always graphable.
        when (record) {
            is WeightRecord -> {
                val value = when (unitPreference) {
                    UnitPreference.IMPERIAL -> record.weight.inPounds
                    else -> record.weight.inKilograms
                }
                val unit = if (unitPreference == UnitPreference.IMPERIAL) "pounds" else "kilograms"
                return MetricMeasurement(
                    timestamp = timestamp,
                    value = value,
                    unitLabel = unit
                )
            }
            is DistanceRecord -> {
                val inKilometers = when (unitPreference) {
                    UnitPreference.IMPERIAL -> record.distance.inMiles
                    else -> record.distance.inKilometers
                }
                val unit = if (unitPreference == UnitPreference.IMPERIAL) "miles" else "kilometers"
                return MetricMeasurement(timestamp = timestamp, value = inKilometers, unitLabel = unit)
            }
            is StepsRecord -> {
                return MetricMeasurement(
                    timestamp = timestamp,
                    value = record.count.toDouble(),
                    unitLabel = "count"
                )
            }
            is TotalCaloriesBurnedRecord -> {
                val value = when (unitPreference) {
                    UnitPreference.METRIC -> record.energy.inKilojoules
                    else -> record.energy.inKilocalories
                }
                val unit = if (unitPreference == UnitPreference.METRIC) "kilojoules" else "kilocalories"
                return MetricMeasurement(
                    timestamp = timestamp,
                    value = value,
                    unitLabel = unit
                )
            }
        }

        val methods = record.javaClass.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
            .filterNot { it.name in setOf("getMetadata", "getZoneOffset", "getStartZoneOffset", "getEndZoneOffset") }

        val candidates = mutableListOf<CandidateMeasurement>()
        methods.forEach { method ->
            val raw = runCatching { method.invoke(record) }.getOrNull() ?: return@forEach
            val methodName = method.name.removePrefix("get")

            (raw as? Number)?.toDouble()?.let { value ->
                candidates += CandidateMeasurement(
                    value = value,
                    unitLabel = null,
                    score = fieldScore(methodName)
                )
            }
            val unitGetters = raw.javaClass.methods.filter { candidate ->
                candidate.parameterCount == 0 &&
                    candidate.name.startsWith("getIn") &&
                    Number::class.java.isAssignableFrom(candidate.returnType)
            }
            unitGetters.forEach { unitGetter ->
                val unitRaw = unitGetter.name.removePrefix("getIn")
                val value = runCatching { (unitGetter.invoke(raw) as? Number)?.toDouble() }.getOrNull()
                    ?: return@forEach
                candidates += CandidateMeasurement(
                    value = value,
                    unitLabel = normalizeUnitLabel(unitRaw),
                    score = unitScore(unitRaw, unitPreference) + fieldScore(methodName)
                )
            }
        }

        val best = candidates.maxByOrNull { it.score } ?: return null
        return MetricMeasurement(timestamp = timestamp, value = best.value, unitLabel = best.unitLabel)
    }

    private fun windowStart(timeWindow: TimeWindow): Instant? {
        val now = Instant.now()
        return when (timeWindow) {
            TimeWindow.DAYS_7 -> now.minus(7, ChronoUnit.DAYS)
            TimeWindow.DAYS_30 -> now.minus(30, ChronoUnit.DAYS)
            TimeWindow.DAYS_90 -> now.minus(90, ChronoUnit.DAYS)
            TimeWindow.YEAR_1 -> now.minus(365, ChronoUnit.DAYS)
            TimeWindow.ALL -> null
        }
    }

    private fun toBucketDate(date: LocalDate, bucketSize: BucketSize): LocalDate {
        return when (bucketSize) {
            BucketSize.DAY -> date
            BucketSize.WEEK -> date.with(DayOfWeek.MONDAY)
            BucketSize.MONTH -> date.withDayOfMonth(1)
        }
    }

    private fun aggregateValues(values: List<Double>, mode: AggregationMode): Double {
        if (values.isEmpty()) return 0.0
        return when (mode) {
            AggregationMode.AVERAGE -> values.average()
            AggregationMode.SUM -> values.sum()
            AggregationMode.MIN -> values.minOrNull() ?: 0.0
            AggregationMode.MAX -> values.maxOrNull() ?: 0.0
        }
    }

    private fun smooth3(points: List<MetricPoint>): List<MetricPoint> {
        if (points.size < 3) return points
        return points.mapIndexed { index, point ->
            val from = (index - 1).coerceAtLeast(0)
            val to = (index + 1).coerceAtMost(points.lastIndex)
            val average = points.subList(from, to + 1).map { it.value }.average()
            point.copy(value = average)
        }
    }

    private fun unitScore(rawUnit: String, unitPreference: UnitPreference): Int {
        val unit = rawUnit.lowercase()
        val imperial = listOf(
            "mile", "foot", "feet", "inch", "yard",
            "pound", "ounce", "fahrenheit", "fluidounce", "gallon", "cup", "calorie"
        )
        val metric = listOf(
            "meter", "metre", "kilometer", "centimeter", "millimeter",
            "gram", "kilogram", "celsius", "joule", "liter", "litre", "milliliter"
        )
        val base = when {
            imperial.any(unit::contains) -> 2
            metric.any(unit::contains) -> 2
            else -> 1
        }
        return when (unitPreference) {
            UnitPreference.AUTO -> base
            UnitPreference.METRIC -> if (metric.any(unit::contains)) 10 else base
            UnitPreference.IMPERIAL -> if (imperial.any(unit::contains)) 10 else base
        }
    }

    private fun fieldScore(rawField: String): Int {
        val field = rawField.lowercase()
        val highSignal = listOf(
            "value", "count", "energy", "distance", "mass",
            "temperature", "rate", "speed", "power", "vo2",
            "systolic", "diastolic", "glucose", "saturation", "cadence"
        )
        return when {
            highSignal.any(field::contains) -> 4
            field in setOf("time", "starttime", "endtime", "metadata") -> -10
            else -> 0
        }
    }

    private fun normalizeUnitLabel(rawUnit: String): String {
        val spaced = rawUnit.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        return spaced.lowercase()
    }

    private fun recordTimestamp(record: Record): Instant? {
        val getterNames = listOf("getTime", "getStartTime", "getEndTime")
        for (getterName in getterNames) {
            try {
                val value = record.javaClass.getMethod(getterName).invoke(record)
                if (value is Instant) return value
            } catch (_: Throwable) {
                // Some record types don't expose this getter. Fall back below.
            }
        }
        return try {
            record.metadata.lastModifiedTime
        } catch (_: Throwable) {
            null
        }
    }
}

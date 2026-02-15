package com.monkopedia.healthdisconnect

import android.app.Application
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodGlucoseRecord
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
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.SmoothingMode
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.YAxisMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HealthDataModel @JvmOverloads constructor(
    app: Application,
    private val autoRefreshMetrics: Boolean = true,
    private val recordLoaderOverride: (suspend (DataView, ((List<Record>) -> Unit)?) -> List<Record>)? = null,
    private val pageReaderOverride: (suspend (
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        onPage: (List<Record>) -> Unit
    ) -> Unit)? = null
) : AndroidViewModel(app) {
    private data class CachedRecordState(
        val data: MutableStateFlow<List<Record>?> = MutableStateFlow(null),
        var loading: Boolean = false,
        var lastRefreshTick: Int = Int.MIN_VALUE,
        var lastAccessTick: Long = 0L
    )

    companion object {
        private const val EXTRACTION_LOG_TAG = "HealthDisconnectExtract"
        const val MAX_CHART_SERIES = 3
        private const val RECORD_CACHE_SCHEMA_VERSION = 4
        private const val MAX_CACHED_VIEWS = 8
    }

    private val recordCacheAccessCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val metricsLock = Any()
    private val recordCacheLock = Any()
    private val metricsWithData: MutableStateFlow<List<KClass<out Record>>?> = MutableStateFlow(null)
    private val recordCache = mutableMapOf<String, CachedRecordState>()
    private var metricsLoading = false
    private var metricsLastRefreshTick = Int.MIN_VALUE

    val healthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    init {
        if (autoRefreshMetrics) {
            scheduleMetricsRefresh()
        }
    }

    private val recordLoader: suspend (DataView, ((List<Record>) -> Unit)?) -> List<Record> = { view, onPartial ->
        recordLoaderOverride?.invoke(view, onPartial)
            ?: loadRecordsForView(view, onPartial)
    }
    private val pageReader: suspend (
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        onPage: (List<Record>) -> Unit
    ) -> Unit = { cls, start, end, onPage ->
        pageReaderOverride?.invoke(cls, start, end, onPage)
            ?: run {
                readRecordsInRange(cls, start, end, onPage)
            }
    }

    fun collectMetricsWithData(refreshTick: Int = 0): Flow<List<KClass<out Record>>> {
        scheduleMetricsRefresh(refreshTick)
        return metricsWithData.filterNotNull()
    }

    suspend fun refreshMetricsWithData() {
        val values = loadMetricsWithData()
        synchronized(metricsLock) {
            metricsWithData.value = values
            metricsLastRefreshTick = maxOf(metricsLastRefreshTick, 0)
            metricsLoading = false
        }
    }

    private fun scheduleMetricsRefresh(refreshTick: Int = 0) {
        val shouldRefresh = synchronized(metricsLock) {
            val needsRefresh = metricsWithData.value == null || refreshTick > metricsLastRefreshTick
            if (metricsLoading || !needsRefresh) {
                false
            } else {
                metricsLoading = true
                true
            }
        }
        if (!shouldRefresh) return
        viewModelScope.launch(Dispatchers.IO) {
            val values = loadMetricsWithData()
            synchronized(metricsLock) {
                metricsWithData.value = values
                metricsLastRefreshTick = maxOf(metricsLastRefreshTick, refreshTick)
                metricsLoading = false
            }
        }
    }

    private suspend fun loadMetricsWithData(): List<KClass<out Record>> {
        val counts = withContext(Dispatchers.IO) {
            coroutineScope {
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
        }
        return counts.filter { it.second }.map { it.first }
    }

    fun collectData(view: DataView, refreshTick: Int = 0): Flow<List<Record>> {
        val key = cacheKey(view)
        val cached = synchronized(recordCacheLock) {
            recordCache.getOrPut(key) { CachedRecordState() }.also {
                it.lastAccessTick = recordCacheAccessCounter.incrementAndGet()
            }.also {
                evictRecordCacheLocked()
            }
        }
        scheduleCacheLoad(view, cached, refreshTick)
        return cached.data.filterNotNull()
    }

    private fun scheduleCacheLoad(view: DataView, cache: CachedRecordState, refreshTick: Int) {
        val shouldRefresh = synchronized(recordCacheLock) {
            cache.lastAccessTick = recordCacheAccessCounter.incrementAndGet()
            val needsRefresh = cache.data.value == null || refreshTick > cache.lastRefreshTick
            if (cache.loading || !needsRefresh) {
                false
            } else {
                cache.loading = true
                true
            }
        }
        if (!shouldRefresh) return
        viewModelScope.launch(Dispatchers.IO) {
            val records = recordLoader(view) { partial ->
                synchronized(recordCacheLock) {
                    cache.data.value = partial
                }
            }
            synchronized(recordCacheLock) {
                cache.data.value = records
                cache.lastRefreshTick = maxOf(cache.lastRefreshTick, refreshTick)
                cache.loading = false
                cache.lastAccessTick = recordCacheAccessCounter.incrementAndGet()
                evictRecordCacheLocked()
            }
        }
    }

    private fun evictRecordCacheLocked() {
        if (recordCache.size <= MAX_CACHED_VIEWS) return
        val removals = recordCache.entries
            .filter { !it.value.loading }
            .sortedBy { it.value.lastAccessTick }
            .take(recordCache.size - MAX_CACHED_VIEWS)
        removals.forEach { (key, _) -> recordCache.remove(key) }
    }

    private suspend fun loadRecordsForView(
        view: DataView,
        onPartialUpdate: ((List<Record>) -> Unit)? = null
    ): List<Record> {
        val typeMap: Map<String, KClass<out Record>> =
            PermissionsViewModel.CLASSES.associateBy { it.qualifiedName ?: "" }
        val selections: List<KClass<out Record>> = view.records.mapNotNull { sel: RecordSelection ->
            typeMap[sel.fqn]
        }
        val now = Instant.now()
        val queryStart = windowStart(view.chartSettings.timeWindow) ?: Instant.EPOCH
        val all = mutableListOf<Record>()
        for (cls in selections) {
            try {
                readRecordsInRange(
                    cls = cls,
                    start = queryStart,
                    end = now,
                    onPage = { pageRecords ->
                    all.addAll(pageRecords)
                    onPartialUpdate?.invoke(
                        all.sortedByDescending { recordTimestamp(it) ?: Instant.EPOCH }
                    )
                })
            } catch (_: Throwable) {
                // Ignore errors per type to keep UI responsive
            }
        }
        return all.sortedByDescending { recordTimestamp(it) ?: Instant.EPOCH }
    }

    internal fun clearCachesForTest() {
        synchronized(metricsLock) {
            metricsLoading = false
            metricsLastRefreshTick = Int.MIN_VALUE
            metricsWithData.value = null
        }
        synchronized(recordCacheLock) {
            recordCache.clear()
        }
    }

    private fun cacheKey(view: DataView): String {
        val recordsKey = view.records.map { it.fqn }.sorted().joinToString("|")
        val windowsKey = view.chartSettings.timeWindow.name
        return "v$RECORD_CACHE_SCHEMA_VERSION|$recordsKey|windows=$windowsKey"
    }

    private suspend fun readRecordsInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        onPage: (List<Record>) -> Unit,
        pageSize: Int = 500
    ) {
        var pageToken: String? = null
        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = cls,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = pageSize,
                    pageToken = pageToken
                )
            )
            @Suppress("UNCHECKED_CAST")
            onPage(response.records as List<Record>)
            pageToken = response.pageToken
        } while (pageToken != null)
    }

    fun collectRecordCount(view: DataView, refreshTick: Int = 0): Flow<Int> = channelFlow {
        val typeMap: Map<String, KClass<out Record>> =
            PermissionsViewModel.CLASSES.associateBy { it.qualifiedName ?: "" }
        val selections: List<KClass<out Record>> = view.records.mapNotNull { sel ->
            typeMap[sel.fqn]
        }
        if (selections.isEmpty()) {
            trySend(0)
            return@channelFlow
        }
        val now = Instant.now()
        val queryStart = windowStart(view.chartSettings.timeWindow) ?: Instant.EPOCH
        var count = 0
        selections.forEach { cls ->
            runCatching {
                pageReader(cls, queryStart, now) { pageRecords ->
                    count += pageRecords.size
                    trySend(count)
                }
            }
        }
        if (count == 0) trySend(0)
    }.flowOn(Dispatchers.IO)

    fun collectAggregatedSeries(view: DataView, refreshTick: Int = 0): Flow<List<MetricSeries>> =
        collectAggregatedSeries(view, refreshTick) { cls, start, end, onPage ->
            readRecordsInRange(
                cls = cls,
                start = start,
                end = end,
                onPage = { page -> onPage(page) }
            )
        }

    internal fun collectAggregatedSeries(
        view: DataView,
        refreshTick: Int = 0,
        pageReader: suspend (
            cls: KClass<out Record>,
            start: Instant,
            end: Instant,
            onPage: (List<Record>) -> Unit
        ) -> Unit
    ): Flow<List<MetricSeries>> = channelFlow {
        @Suppress("UNUSED_VARIABLE")
        val ignoredRefreshTick = refreshTick
        val zoneId = ZoneId.systemDefault()
        val viewWindowStart = windowStart(view.chartSettings.timeWindow)
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
        }.take(MAX_CHART_SERIES)

        if (selectedByType.isEmpty()) {
            trySend(emptyList())
            return@channelFlow
        }

        val now = Instant.now()
        val queryStart = viewWindowStart ?: Instant.EPOCH
        selectedByType.forEach { metricState ->
            runCatching {
                pageReader(
                    metricState.recordClass,
                    queryStart,
                    now
                ) { pageRecords ->
                        // Process records page-by-page and discard page data immediately.
                        pageRecords.forEach { record ->
                            val measurement = extractMeasurement(record, metricState.metricSettings.unitPreference)
                                ?: return@forEach
                            if (viewWindowStart != null && measurement.timestamp.isBefore(viewWindowStart)) {
                                return@forEach
                            }
                            if (metricState.unit == null && !measurement.unitLabel.isNullOrBlank()) {
                                metricState.unit = measurement.unitLabel
                            }
                            metricState.peakValue = when (val current = metricState.peakValue) {
                                null -> measurement.value
                                else -> maxOf(current, measurement.value)
                            }
                            val bucketDate = toBucketDate(
                                measurement.timestamp.atZone(zoneId).toLocalDate(),
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
    }.flowOn(Dispatchers.IO)

    data class MetricPoint(val date: LocalDate, val value: Double)

    data class MetricSeries(
        val label: String,
        val unit: String?,
        val points: List<MetricPoint>,
        val peakValueInWindow: Double = points.maxOfOrNull { it.value } ?: 0.0,
        val yAxisMode: YAxisMode = YAxisMode.AUTO
    )

    fun aggregateMetricSeriesList(view: DataView, records: List<Record>): List<MetricSeries> {
        if (records.isEmpty()) return emptyList()
        val settings = view.chartSettings
        val zoneId = ZoneId.systemDefault()
        val viewWindowStart = windowStart(settings.timeWindow)

        val selectedByType = view.records.mapNotNull { selection ->
            val cls = PermissionsViewModel.CLASSES.firstOrNull { it.qualifiedName == selection.fqn }
                ?: return@mapNotNull null
            val label = PermissionsViewModel.RECORD_NAMES[cls] ?: cls.simpleName ?: selection.fqn
            cls to label
        }

        if (selectedByType.isEmpty()) return emptyList()

        return selectedByType
            .mapNotNull { (cls, label) ->
            val selection = view.records.firstOrNull { it.fqn == cls.qualifiedName } ?: return@mapNotNull null
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
                toBucketDate(it.timestamp.atZone(zoneId).toLocalDate(), settings.bucketSize)
            }
            val aggregated = grouped
                .toSortedMap()
                .map { (date, values) ->
                    val value = aggregateValues(values.map { it.value }, metricSettings.aggregation)
                    MetricPoint(date = date, value = value)
                }
            val points = when (metricSettings.smoothing) {
                SmoothingMode.OFF -> aggregated
                SmoothingMode.MOVING_AVERAGE_3 -> smooth3(aggregated)
            }
            MetricSeries(
                label = label,
                unit = measurements.firstNotNullOfOrNull { it.unitLabel },
                points = points,
                peakValueInWindow = measurements.maxOf { it.value },
                yAxisMode = metricSettings.yAxisMode
            )
        }.take(MAX_CHART_SERIES)
    }

    fun aggregateMetricSeries(view: DataView, records: List<Record>): MetricSeries? {
        return aggregateMetricSeriesList(view, records).firstOrNull()
    }

    private data class MetricMeasurement(
        val timestamp: Instant,
        val value: Double,
        val unitLabel: String?,
        val sourceField: String?
    )

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

    private fun buildStreamingSeries(states: List<StreamingMetricState>): List<MetricSeries> {
        return states.mapNotNull { state ->
            if (state.buckets.isEmpty()) return@mapNotNull null
            val aggregated = state.buckets
                .toSortedMap()
                .map { (date, bucket) ->
                    MetricPoint(date = date, value = bucket.aggregated(state.metricSettings.aggregation))
                }
            val points = when (state.metricSettings.smoothing) {
                SmoothingMode.OFF -> aggregated
                SmoothingMode.MOVING_AVERAGE_3 -> smooth3(aggregated)
            }
            MetricSeries(
                label = state.label,
                unit = state.unit,
                points = points,
                peakValueInWindow = state.peakValue ?: 0.0,
                yAxisMode = state.metricSettings.yAxisMode
            )
        }
    }

    private data class CandidateMeasurement(
        val value: Double,
        val unitLabel: String?,
        val score: Int,
        val sourceField: String
    )

    private data class ParsedMeasurement(
        val value: Double,
        val unitLabel: String?
    )

    private data class ReflectiveTypePreference(
        val preferredFields: List<String>,
        val fallbackToDurationMinutes: Boolean = false
    )

    private val reflectiveTypePreferencesByFqn = mapOf(
        "androidx.health.connect.client.records.ActiveCaloriesBurnedRecord" to ReflectiveTypePreference(listOf("Energy", "Calories")),
        "androidx.health.connect.client.records.BasalBodyTemperatureRecord" to ReflectiveTypePreference(listOf("Temperature")),
        "androidx.health.connect.client.records.BasalMetabolicRateRecord" to ReflectiveTypePreference(listOf("BasalMetabolicRate", "Power")),
        "androidx.health.connect.client.records.BloodGlucoseRecord" to ReflectiveTypePreference(listOf("Level", "Glucose")),
        "androidx.health.connect.client.records.BloodPressureRecord" to ReflectiveTypePreference(listOf("Systolic", "Diastolic", "Pressure")),
        "androidx.health.connect.client.records.BodyFatRecord" to ReflectiveTypePreference(listOf("Percentage", "Percent")),
        "androidx.health.connect.client.records.BodyTemperatureRecord" to ReflectiveTypePreference(listOf("Temperature")),
        "androidx.health.connect.client.records.BodyWaterMassRecord" to ReflectiveTypePreference(listOf("Mass", "Weight")),
        "androidx.health.connect.client.records.BoneMassRecord" to ReflectiveTypePreference(listOf("Mass", "Weight")),
        "androidx.health.connect.client.records.CervicalMucusRecord" to ReflectiveTypePreference(listOf("Appearance", "Sensation", "Type")),
        "androidx.health.connect.client.records.CyclingPedalingCadenceRecord" to ReflectiveTypePreference(listOf("RevolutionsPerMinute", "Cadence", "Rate", "Rpm", "Samples")),
        "androidx.health.connect.client.records.DistanceRecord" to ReflectiveTypePreference(listOf("Distance")),
        "androidx.health.connect.client.records.ElevationGainedRecord" to ReflectiveTypePreference(listOf("Elevation", "Height", "Gain")),
        "androidx.health.connect.client.records.ExerciseSessionRecord" to ReflectiveTypePreference(listOf("Duration", "ActiveTime"), fallbackToDurationMinutes = true),
        "androidx.health.connect.client.records.FloorsClimbedRecord" to ReflectiveTypePreference(listOf("Floors", "Count")),
        "androidx.health.connect.client.records.HeartRateRecord" to ReflectiveTypePreference(listOf("BeatsPerMinute", "Bpm", "Rate", "Samples")),
        "androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord" to ReflectiveTypePreference(listOf("Rmssd", "Millis", "Variability")),
        "androidx.health.connect.client.records.HeightRecord" to ReflectiveTypePreference(listOf("Height")),
        "androidx.health.connect.client.records.HydrationRecord" to ReflectiveTypePreference(listOf("Volume", "Hydration")),
        "androidx.health.connect.client.records.IntermenstrualBleedingRecord" to ReflectiveTypePreference(listOf("Type", "Flow", "Amount")),
        "androidx.health.connect.client.records.LeanBodyMassRecord" to ReflectiveTypePreference(listOf("Mass", "Weight")),
        "androidx.health.connect.client.records.MenstruationFlowRecord" to ReflectiveTypePreference(listOf("Flow", "Type", "Severity")),
        "androidx.health.connect.client.records.MenstruationPeriodRecord" to ReflectiveTypePreference(listOf("Duration", "Length"), fallbackToDurationMinutes = true),
        "androidx.health.connect.client.records.NutritionRecord" to ReflectiveTypePreference(listOf("Energy", "Calories", "Protein", "Carbohydrate", "Fat", "Sugar")),
        "androidx.health.connect.client.records.OvulationTestRecord" to ReflectiveTypePreference(listOf("Result", "Type")),
        "androidx.health.connect.client.records.OxygenSaturationRecord" to ReflectiveTypePreference(listOf("Percentage", "Saturation", "Percent")),
        "androidx.health.connect.client.records.PowerRecord" to ReflectiveTypePreference(listOf("Power", "Watts", "Samples")),
        "androidx.health.connect.client.records.RespiratoryRateRecord" to ReflectiveTypePreference(listOf("Rate", "Respiratory", "Breaths")),
        "androidx.health.connect.client.records.RestingHeartRateRecord" to ReflectiveTypePreference(listOf("BeatsPerMinute", "Bpm", "Rate")),
        "androidx.health.connect.client.records.SexualActivityRecord" to ReflectiveTypePreference(listOf("ProtectionUsed", "Type", "Result")),
        "androidx.health.connect.client.records.SleepSessionRecord" to ReflectiveTypePreference(listOf("Duration", "Sleep"), fallbackToDurationMinutes = true),
        "androidx.health.connect.client.records.SpeedRecord" to ReflectiveTypePreference(listOf("Speed", "MetersPerSecond", "Samples")),
        "androidx.health.connect.client.records.StepsCadenceRecord" to ReflectiveTypePreference(listOf("Rate", "Cadence", "StepsPerMinute", "Samples")),
        "androidx.health.connect.client.records.StepsRecord" to ReflectiveTypePreference(listOf("Count", "Steps")),
        "androidx.health.connect.client.records.TotalCaloriesBurnedRecord" to ReflectiveTypePreference(listOf("Energy", "Calories")),
        "androidx.health.connect.client.records.Vo2MaxRecord" to ReflectiveTypePreference(listOf("Vo2", "MillilitersPerMinuteKilogram", "MlPerMinPerKg")),
        "androidx.health.connect.client.records.WeightRecord" to ReflectiveTypePreference(listOf("Weight", "Mass")),
        "androidx.health.connect.client.records.WheelchairPushesRecord" to ReflectiveTypePreference(listOf("Count", "Pushes"))
    )

    private fun <T : Record, U> mappingLookup(
        fieldName: String,
        recordClass: KClass<T>,
        valueField: (T) -> U,
        imperialValue: (U) -> Double,
        imperialUnit: String,
        metricValue: (U) -> Double,
        metricUnit: String,
        autoPreference: UnitPreference = UnitPreference.METRIC
    ): Pair<KClass<out Record>, (Record, UnitPreference, Instant) -> MetricMeasurement> {
        val extractor: (Record, UnitPreference, Instant) -> MetricMeasurement = { record, preference, timestamp ->
            val typedRecord = record as T
            val value = valueField(typedRecord)
            val useImperial = when (preference) {
                UnitPreference.IMPERIAL -> true
                UnitPreference.METRIC -> false
                UnitPreference.AUTO -> autoPreference == UnitPreference.IMPERIAL
            }
            MetricMeasurement(
                timestamp = timestamp,
                value = if (useImperial) imperialValue(value) else metricValue(value),
                unitLabel = if (useImperial) imperialUnit else metricUnit,
                sourceField = fieldName
            )
        }
        return recordClass to extractor
    }

    private fun <T : Record> scalarMappingLookup(
        fieldName: String,
        recordClass: KClass<T>,
        valueField: (T) -> Double,
        unitLabel: String? = null
    ): Pair<KClass<out Record>, (Record, UnitPreference, Instant) -> MetricMeasurement> {
        val extractor: (Record, UnitPreference, Instant) -> MetricMeasurement = { record, _, timestamp ->
            MetricMeasurement(
                timestamp = timestamp,
                value = valueField(record as T),
                unitLabel = unitLabel,
                sourceField = fieldName
            )
        }
        return recordClass to extractor
    }

    private val staticExtractors: Map<KClass<out Record>, (Record, UnitPreference, Instant) -> MetricMeasurement> =
        mapOf(
            mappingLookup(
                fieldName = "Weight",
                recordClass = WeightRecord::class,
                valueField = WeightRecord::weight,
                imperialValue = { it.inPounds },
                imperialUnit = "pounds",
                metricValue = { it.inKilograms },
                metricUnit = "kilograms"
            ),
            mappingLookup(
                fieldName = "Distance",
                recordClass = DistanceRecord::class,
                valueField = DistanceRecord::distance,
                imperialValue = { it.inMiles },
                imperialUnit = "miles",
                metricValue = { it.inKilometers },
                metricUnit = "kilometers"
            ),
            mappingLookup(
                fieldName = "Level",
                recordClass = BloodGlucoseRecord::class,
                valueField = BloodGlucoseRecord::level,
                imperialValue = { it.inMilligramsPerDeciliter },
                imperialUnit = "mg/dL",
                metricValue = { it.inMillimolesPerLiter },
                metricUnit = "mmol/L"
            ),
            mappingLookup(
                fieldName = "Energy",
                recordClass = TotalCaloriesBurnedRecord::class,
                valueField = TotalCaloriesBurnedRecord::energy,
                imperialValue = { it.inKilocalories },
                imperialUnit = "kilocalories",
                metricValue = { it.inKilojoules },
                metricUnit = "kilojoules",
                autoPreference = UnitPreference.IMPERIAL
            ),
            scalarMappingLookup(
                fieldName = "Count",
                recordClass = StepsRecord::class,
                valueField = { it.count.toDouble() },
                unitLabel = "count"
            )
        )

    private fun extractMeasurement(record: Record, unitPreference: UnitPreference): MetricMeasurement? {
        val timestamp = recordTimestamp(record) ?: return null
        staticExtractors[record::class]?.let { extractor ->
            return extractor(record, unitPreference, timestamp)
        }
        val typePreference = reflectiveTypePreferencesByFqn[record::class.qualifiedName]

        val methods = record.javaClass.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
            .filterNot {
                it.name in setOf(
                    "getMetadata",
                    "getZoneOffset",
                    "getStartZoneOffset",
                    "getEndZoneOffset",
                    "getTime",
                    "getStartTime",
                    "getEndTime"
                )
            }

        val candidates = mutableListOf<CandidateMeasurement>()
        methods.forEach { method ->
            val raw = runCatching { method.invoke(record) }.getOrNull() ?: return@forEach
            val methodName = method.name.removePrefix("get")
            var addedUnitCandidate = false

            (raw as? Number)?.toDouble()?.let { value ->
                candidates += CandidateMeasurement(
                    value = value,
                    unitLabel = null,
                    score = fieldScore(methodName) + preferredFieldScore(methodName, typePreference),
                    sourceField = methodName
                )
            }
            val unitGetters = raw.javaClass.methods.filter { candidate ->
                candidate.parameterCount == 0 &&
                    (candidate.name.startsWith("getIn") || candidate.name.startsWith("in")) &&
                    isNumericType(candidate.returnType)
            }
            unitGetters.forEach { unitGetter ->
                val unitRaw = unitGetter.name
                    .removePrefix("getIn")
                    .removePrefix("in")
                val value = runCatching { (unitGetter.invoke(raw) as? Number)?.toDouble() }.getOrNull()
                    ?: return@forEach
                candidates += CandidateMeasurement(
                    value = value,
                    unitLabel = normalizeUnitLabel(unitRaw),
                    score = unitScore(unitRaw, unitPreference, value) + fieldScore(methodName) + preferredFieldScore(methodName, typePreference),
                    sourceField = methodName
                )
                addedUnitCandidate = true
            }
            if (raw is Collection<*> && raw.isNotEmpty()) {
                val samples = raw.filterNotNull().take(50)
                if (samples.isNotEmpty()) {
                    val sampleMethods = samples.first().javaClass.methods.filter { sampleMethod ->
                        sampleMethod.parameterCount == 0 &&
                            sampleMethod.name.startsWith("get") &&
                            isNumericType(sampleMethod.returnType)
                    }
                    sampleMethods.forEach { sampleMethod ->
                        val sampleName = sampleMethod.name.removePrefix("get")
                        val values = samples.mapNotNull { sample ->
                            runCatching { (sampleMethod.invoke(sample) as? Number)?.toDouble() }.getOrNull()
                        }
                        if (values.isNotEmpty()) {
                            val mean = values.average()
                            candidates += CandidateMeasurement(
                                value = mean,
                                unitLabel = null,
                                score = fieldScore(methodName) + fieldScore(sampleName) +
                                    preferredFieldScore(methodName, typePreference) + preferredFieldScore(sampleName, typePreference),
                                sourceField = "$methodName.$sampleName"
                            )
                        }
                    }
                }
            }
            if (!addedUnitCandidate) {
                parseMeasurementFromString(raw.toString())?.let { parsed ->
                    candidates += CandidateMeasurement(
                        value = parsed.value,
                        unitLabel = parsed.unitLabel,
                        score = unitScore(parsed.unitLabel ?: "", unitPreference, parsed.value) +
                            fieldScore(methodName) + preferredFieldScore(methodName, typePreference) + 3,
                        sourceField = methodName
                    )
                }
            }
        }

        val prioritized = candidates
            .let { list ->
                // Prefer values with explicit units when available.
                val withUnits = list.filter { !it.unitLabel.isNullOrBlank() }
                if (withUnits.isNotEmpty()) withUnits else list
            }
        val best = prioritized.maxByOrNull { it.score }
        if (best == null && typePreference?.fallbackToDurationMinutes == true) {
            val start = runCatching { record.javaClass.getMethod("getStartTime").invoke(record) as? Instant }.getOrNull()
            val end = runCatching { record.javaClass.getMethod("getEndTime").invoke(record) as? Instant }.getOrNull()
            if (start != null && end != null && !end.isBefore(start)) {
                val minutes = ChronoUnit.SECONDS.between(start, end).toDouble() / 60.0
                return MetricMeasurement(
                    timestamp = timestamp,
                    value = minutes,
                    unitLabel = "minutes",
                    sourceField = "Duration"
                )
            }
        }
        if (best == null) return null
        logMeasurementChoice(record, unitPreference, best)
        return MetricMeasurement(
            timestamp = timestamp,
            value = best.value,
            unitLabel = best.unitLabel,
            sourceField = best.sourceField
        )
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

    private fun metricDefaultsFromView(view: DataView): MetricChartSettings {
        return MetricChartSettings(
            aggregation = view.chartSettings.aggregation,
            timeWindow = view.chartSettings.timeWindow,
            bucketSize = view.chartSettings.bucketSize,
            yAxisMode = view.chartSettings.yAxisMode,
            smoothing = view.chartSettings.smoothing,
            unitPreference = view.chartSettings.unitPreference
        )
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

    private fun unitScore(rawUnit: String, unitPreference: UnitPreference, value: Double): Int {
        val unit = rawUnit.lowercase()
        val imperial = listOf(
            "mile", "foot", "feet", "inch", "yard",
            "pound", "ounce", "fahrenheit", "fluidounce", "gallon", "cup", "calorie",
            "deciliter"
        )
        val metric = listOf(
            "meter", "metre", "kilometer", "centimeter", "millimeter",
            "gram", "kilogram", "celsius", "joule", "liter", "litre", "milliliter",
            "mole", "millimole"
        )
        val base = when {
            imperial.any(unit::contains) -> 2
            metric.any(unit::contains) -> 2
            else -> 1
        }
        val preferenceBoost = when (unitPreference) {
            UnitPreference.AUTO -> 0
            UnitPreference.METRIC -> if (metric.any(unit::contains)) 8 else 0
            UnitPreference.IMPERIAL -> if (imperial.any(unit::contains)) 8 else 0
        }
        val concentrationBoost = when {
            unit.contains("millimolesperliter") -> when (unitPreference) {
                UnitPreference.IMPERIAL -> 2
                else -> 8
            }
            unit.contains("milligramsperdeciliter") -> when (unitPreference) {
                UnitPreference.METRIC -> 2
                else -> 8
            }
            // Prefer human-scaled subunits when both base and milli forms exist.
            unit.contains("molesperliter") -> -8
            unit.contains("gramsperliter") -> -3
            else -> 0
        }
        val magnitudePenalty = when {
            value == 0.0 -> 0
            kotlin.math.abs(value) < 0.01 -> -8
            kotlin.math.abs(value) < 0.1 -> -4
            kotlin.math.abs(value) > 1_000_000 -> -4
            else -> 0
        }
        return base + preferenceBoost + concentrationBoost + magnitudePenalty
    }

    private fun fieldScore(rawField: String): Int {
        val field = rawField.lowercase()
        val highSignal = listOf(
            "value", "count", "energy", "distance", "mass",
            "temperature", "rate", "speed", "power", "vo2",
            "systolic", "diastolic", "glucose", "saturation", "cadence", "level"
        )
        val lowSignal = listOf(
            "meal", "relation", "source", "specimen", "type", "status"
        )
        return when {
            highSignal.any(field::contains) -> 4
            lowSignal.any(field::contains) -> -8
            field in setOf("time", "starttime", "endtime", "metadata") -> -10
            else -> 0
        }
    }

    private fun preferredFieldScore(
        rawField: String,
        preference: ReflectiveTypePreference?
    ): Int {
        if (preference == null) return 0
        val field = rawField.lowercase()
        return if (preference.preferredFields.any { token -> field.contains(token.lowercase()) }) 18 else 0
    }

    private fun normalizeUnitLabel(rawUnit: String): String {
        val spaced = rawUnit.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        return spaced.lowercase()
    }

    private fun parseMeasurementFromString(text: String): ParsedMeasurement? {
        // Handles unit wrappers rendered like "72.3 kg" or "5.9 mmol/L".
        val match = Regex("""^\s*([-+]?\d+(?:\.\d+)?)\s*([^\d].+)?\s*$""").find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2)?.trim().orEmpty().ifBlank { null }
        return ParsedMeasurement(value = value, unitLabel = unit)
    }

    private fun isNumericType(type: Class<*>): Boolean {
        return Number::class.java.isAssignableFrom(type) ||
            type == java.lang.Double.TYPE ||
            type == java.lang.Float.TYPE ||
            type == java.lang.Integer.TYPE ||
            type == java.lang.Long.TYPE ||
            type == java.lang.Short.TYPE ||
            type == java.lang.Byte.TYPE
    }

    private fun logMeasurementChoice(
        record: Record,
        unitPreference: UnitPreference,
        best: CandidateMeasurement
    ) {
        val cls = record::class.qualifiedName ?: record::class.simpleName ?: "Record"
        if (!cls.contains("bloodglucose", ignoreCase = true)) return
        Log.d(
            EXTRACTION_LOG_TAG,
            "record=$cls field=${best.sourceField} unit=${best.unitLabel ?: "none"} value=${best.value} pref=$unitPreference score=${best.score}"
        )
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

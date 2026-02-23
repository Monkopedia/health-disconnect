package com.monkopedia.healthdisconnect

import android.app.Application
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.YAxisMode
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.KClass
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HealthDataPermissionDeniedException(
    val deniedRecordTypes: Set<String>
) : IllegalStateException(
    "Health Connect read permission denied for: ${deniedRecordTypes.joinToString(",")}"
)

class HealthDataModel @JvmOverloads constructor(
    app: Application,
    private val autoRefreshMetrics: Boolean = true,
    private val recordLoaderOverride: (suspend (DataView, ((List<Record>) -> Unit)?) -> List<Record>)? = null,
    private val pageReaderOverride: (suspend (
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        onPage: (List<Record>) -> Unit
    ) -> Unit)? = null,
    initialHealthConnectClient: HealthConnectClient? = null,
    private val healthConnectGateway: HealthConnectGateway? = null,
    private val measurementExtractor: HealthRecordMeasurementExtractor = DefaultHealthRecordMeasurementExtractor(),
    private val aggregationEngine: HealthDataAggregationEngine = DefaultHealthDataAggregationEngine(measurementExtractor),
    private val cachePolicy: HealthDataRecordCachePolicy = DefaultHealthDataRecordCachePolicy(),
    private val dispatchers: CoroutineDispatcherProvider = DefaultDispatcherProvider(),
    private val timeProvider: TimeProvider = SystemTimeProvider()
) : AndroidViewModel(app) {
    private val gateway by lazy {
        healthConnectGateway ?: DefaultHealthConnectGateway(
            initialHealthConnectClient ?: HealthConnectClient.getOrCreate(app)
        )
    }
    private val recordCache: MutableMap<String, HealthDataRecordCachePolicy.CachedRecordState> = cachePolicy.recordCache

    companion object {
        const val MAX_CHART_SERIES = 3
        const val LOG_TAG = "HealthDataModel"
    }

    data class RecordSelectionOption(
        val selection: RecordSelection,
        val label: String
    )

    private val metricsLock = Any()
    private val metricsWithData: MutableStateFlow<List<KClass<out Record>>?> = MutableStateFlow(null)
    private var metricsLoading = false
    private var metricsLastRefreshTick = Int.MIN_VALUE

    private val ioDispatcher = dispatchers.io

    init {
        if (autoRefreshMetrics) {
            scheduleMetricsRefresh()
        }
    }

    private val recordLoader: suspend (DataView, ((List<Record>) -> Unit)?) -> List<Record> = { view, onPartial ->
        recordLoaderOverride?.invoke(view, onPartial) ?: loadRecordsForView(view, onPartial)
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

    fun recordSelectionLabel(selection: RecordSelection): String {
        val cls = PermissionsViewModel.CLASSES.firstOrNull { it.qualifiedName == selection.fqn }
            ?: return selection.fqn
        return measurementExtractor.metricLabel(cls, selection.metricKey)
            ?: PermissionsViewModel.RECORD_NAMES[cls]
            ?: cls.simpleName
            ?: selection.fqn
    }

    fun recordSelectionOptions(
        recordClass: KClass<out Record>,
        metricSettings: MetricChartSettings
    ): List<RecordSelectionOption> {
        val fqn = recordClass.qualifiedName ?: return emptyList()
        val baseLabel = PermissionsViewModel.RECORD_NAMES[recordClass]
            ?: recordClass.simpleName
            ?: fqn
        val availableMetrics = measurementExtractor.availableMetrics(recordClass)
            .ifEmpty { listOf(ExtractableMetric()) }
        return availableMetrics.map { metric ->
            val selection = RecordSelection(
                fqn = fqn,
                metricSettings = metricSettings,
                metricKey = metric.key
            )
            RecordSelectionOption(
                selection = selection,
                label = metric.label ?: baseLabel
            )
        }.distinctBy { it.selection.selectionKey() }
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
        viewModelScope.launch(ioDispatcher) {
            try {
                val values = loadMetricsWithData()
                synchronized(metricsLock) {
                    metricsWithData.value = values
                    metricsLastRefreshTick = maxOf(metricsLastRefreshTick, refreshTick)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                Log.w(LOG_TAG, "Failed to refresh metrics with data", exception)
            } finally {
                synchronized(metricsLock) {
                    metricsLoading = false
                }
            }
        }
    }

    private suspend fun loadMetricsWithData(): List<KClass<out Record>> {
        val counts = withContext(ioDispatcher) {
            coroutineScope {
                PermissionsViewModel.CLASSES.map {
                    async {
                        val hasRecords = gateway.hasRecordsForType(
                            cls = it,
                            now = timeProvider.now()
                        )
                        it to hasRecords
                    }
                }.awaitAll()
            }
        }
        return counts.filter { it.second }.map { it.first }
    }

    fun collectData(view: DataView, refreshTick: Int = 0): Flow<List<Record>> {
        return cachePolicy.collectData(
            view = view,
            refreshTick = refreshTick,
            ioScope = viewModelScope,
            ioDispatcher = ioDispatcher,
            recordLoader = recordLoader
        )
    }

    suspend fun loadRawDataForExport(view: DataView): List<Record> {
        return withContext(ioDispatcher) {
            recordLoader(view, null)
        }
    }

    suspend fun loadRawDataForWidget(view: DataView): List<Record> {
        return withContext(ioDispatcher) {
            loadRecordsForView(view, onPartialUpdate = null, throwOnPermissionDenied = true)
        }
    }

    suspend fun loadAggregatedSeriesForExport(view: DataView): List<MetricSeries> {
        return withContext(ioDispatcher) {
            val records = recordLoader(view, null)
            aggregationEngine.aggregateMetricSeriesList(view, records)
        }
    }

    suspend fun loadAggregatedSeriesForWidget(view: DataView): List<MetricSeries> {
        return withContext(ioDispatcher) {
            val records = loadRecordsForView(
                view = view,
                onPartialUpdate = null,
                throwOnPermissionDenied = true
            )
            aggregationEngine.aggregateMetricSeriesList(view, records)
        }
    }

    private suspend fun loadRecordsForView(
        view: DataView,
        onPartialUpdate: ((List<Record>) -> Unit)? = null,
        throwOnPermissionDenied: Boolean = false
    ): List<Record> {
        val typeMap: Map<String, KClass<out Record>> =
            PermissionsViewModel.CLASSES.associateBy { it.qualifiedName ?: "" }
        val selections: List<KClass<out Record>> = view.records.mapNotNull { sel: RecordSelection ->
            typeMap[sel.fqn]
        }.distinctBy { it.qualifiedName.orEmpty() }
        val now = timeProvider.now()
        val queryStart = windowStart(view.chartSettings.timeWindow) ?: Instant.EPOCH
        val all = mutableListOf<Record>()
        val deniedRecordTypes = mutableSetOf<String>()
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
                    }
                )
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                if (exception is SecurityException) {
                    deniedRecordTypes.add(cls.qualifiedName ?: cls.simpleName.orEmpty())
                }
                Log.w(
                    LOG_TAG,
                    "Failed to load records for ${cls.qualifiedName}, continuing with partial dataset",
                    exception
                )
            }
        }
        if (throwOnPermissionDenied && all.isEmpty() && deniedRecordTypes.isNotEmpty()) {
            throw HealthDataPermissionDeniedException(deniedRecordTypes)
        }
        return all.sortedByDescending { recordTimestamp(it) ?: Instant.EPOCH }
    }

    internal fun clearCachesForTest() {
        synchronized(metricsLock) {
            metricsLoading = false
            metricsLastRefreshTick = Int.MIN_VALUE
            metricsWithData.value = null
        }
        cachePolicy.clear()
    }

    private fun cacheKey(view: DataView): String {
        return cachePolicy.cacheKey(view)
    }

    private suspend fun readRecordsInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        onPage: (List<Record>) -> Unit,
        pageSize: Int = 500
    ) {
        gateway.readRecordsInRange(
            cls = cls,
            start = start,
            end = end,
            pageSize = pageSize,
            onPage = onPage
        )
    }

    fun collectRecordCount(view: DataView, refreshTick: Int = 0): Flow<Int> = channelFlow {
        val typeMap: Map<String, KClass<out Record>> =
            PermissionsViewModel.CLASSES.associateBy { it.qualifiedName ?: "" }
        val selections: List<KClass<out Record>> = view.records.mapNotNull { sel ->
            typeMap[sel.fqn]
        }.distinctBy { it.qualifiedName.orEmpty() }
        if (selections.isEmpty()) {
            trySend(0)
            return@channelFlow
        }
        val now = timeProvider.now()
        val queryStart = windowStart(view.chartSettings.timeWindow) ?: Instant.EPOCH
        var count = 0
        selections.forEach { cls ->
            try {
                pageReader(cls, queryStart, now) { pageRecords ->
                    count += pageRecords.size
                    trySend(count)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                Log.w(
                    LOG_TAG,
                    "Failed to read record count for ${cls.qualifiedName}",
                    exception
                )
            }
        }
        if (count == 0) trySend(0)
    }.flowOn(ioDispatcher)

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
    ): Flow<List<MetricSeries>> {
        @Suppress("UNUSED_PARAMETER")
        val ignoredRefreshTick = refreshTick
        return aggregationEngine.collectAggregatedSeries(
            view = view,
            now = timeProvider.now(),
            maxSeries = MAX_CHART_SERIES,
            pageReader = pageReader
        ).flowOn(ioDispatcher)
    }

    data class MetricPoint(val date: LocalDate, val value: Double)

    data class MetricSeries(
        val label: String,
        val unit: String?,
        val points: List<MetricPoint>,
        val peakValueInWindow: Double = points.maxOfOrNull { it.value } ?: 0.0,
        val minValueInWindow: Double = points.minOfOrNull { it.value } ?: 0.0,
        val showMaxLabel: Boolean = true,
        val showMinLabel: Boolean = false,
        val yAxisMode: YAxisMode = YAxisMode.AUTO
    )

    fun aggregateMetricSeriesList(view: DataView, records: List<Record>): List<MetricSeries> {
        return aggregationEngine.aggregateMetricSeriesList(view, records)
    }

    fun aggregateMetricSeries(view: DataView, records: List<Record>): MetricSeries? {
        return aggregationEngine.aggregateMetricSeries(view, records)
    }

    private fun extractMeasurement(record: Record, unitPreference: UnitPreference): MetricMeasurement? {
        return measurementExtractor.extractMeasurement(record, unitPreference)
    }

    private fun windowStart(timeWindow: TimeWindow): Instant? {
        return aggregationEngine.windowStart(timeWindow, timeProvider.now())
    }

    fun toBucketDate(date: LocalDate, bucketSize: com.monkopedia.healthdisconnect.model.BucketSize): LocalDate {
        return aggregationEngine.toBucketDate(date, bucketSize)
    }

    fun aggregateValues(values: List<Double>, mode: com.monkopedia.healthdisconnect.model.AggregationMode): Double {
        return aggregationEngine.aggregateValues(values, mode)
    }

    fun smooth3(points: List<MetricPoint>): List<MetricPoint> {
        return aggregationEngine.smooth3(points)
    }

    private fun recordTimestamp(record: Record): Instant? {
        return measurementExtractor.recordTimestamp(record)
    }

}

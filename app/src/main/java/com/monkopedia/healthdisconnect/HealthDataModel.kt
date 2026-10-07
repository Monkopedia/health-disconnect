package com.monkopedia.healthdisconnect

import android.app.Application
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.YAxisMode
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
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
        const val RECENT_METRIC_LOOKBACK_DAYS = 90L

        /**
         * Most raw records a chart reads per record type, newest first. Every 500 records is one
         * Health Connect read, and Health Connect rate-limits an app's reads: years of
         * per-minute wearable steps were thousands of reads per chart (issue #112). Past this a
         * chart shows its most recent records only. Step SUM charts skip raw records entirely —
         * see [stepTotals].
         */
        const val MAX_CHART_RECORDS = 100_000

        /**
         * Most raw records the entries count and entries list read across a view, newest first.
         * A count that reaches this is shown as "at least" this many.
         */
        const val MAX_LISTED_RECORDS = 10_000

        /** Periods per Health Connect aggregate request, so a request never spans unbounded groups. */
        const val MAX_PERIODS_PER_AGGREGATE = 365
    }

    data class RecordSelectionOption(
        val selection: RecordSelection,
        val label: String
    )

    private val metricsLock = Any()
    private val metricsWithData: MutableStateFlow<List<KClass<out Record>>?> = MutableStateFlow(null)
    private val chartReadFailures = MutableStateFlow<Set<Int>>(emptySet())
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
    private val listedRecordLoader: suspend (DataView, ((List<Record>) -> Unit)?) -> List<Record> =
        { view, onPartial ->
            recordLoaderOverride?.invoke(view, onPartial)
                ?: loadRecordsForView(view, onPartial, maxRecords = MAX_LISTED_RECORDS)
        }
    private val pageReader: suspend (
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        onPage: (List<Record>) -> Unit
    ) -> Unit = { cls, start, end, onPage ->
        pageReaderOverride?.invoke(cls, start, end, onPage)
            ?: run {
                readRecordsInRange(cls, start, end, onPage, maxRecords = MAX_LISTED_RECORDS)
            }
    }

    fun collectMetricsWithData(refreshTick: Int = 0): Flow<List<KClass<out Record>>> {
        scheduleMetricsRefresh(refreshTick)
        return metricsWithData.filterNotNull()
    }

    /**
     * Human-readable label for [selection], or null when its record type can no longer be resolved
     * (e.g. a view saved by a build that obfuscated the record class name — see the Health Connect
     * keep rule in proguard-rules.pro). Callers surface a localized "re-add this metric" prompt for
     * the null case rather than leaking the raw stored [RecordSelection.fqn] into the UI.
     */
    fun recordSelectionLabel(selection: RecordSelection): String? {
        val cls = PermissionsViewModel.classForFqn(selection.fqn)
            ?: return null
        return measurementExtractor.metricLabel(cls, selection.metricKey)
            ?: PermissionsViewModel.recordLabel(cls)
    }

    fun recordSelectionOptions(
        recordClass: KClass<out Record>,
        metricSettings: MetricChartSettings
    ): List<RecordSelectionOption> {
        val fqn = recordClass.qualifiedName ?: return emptyList()
        val baseLabel = PermissionsViewModel.recordLabel(recordClass)
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

    /**
     * Whether [recordClass]'s [metricKey] metric has any value within the last
     * [lookbackDays]. Probes most-recent-first and short-circuits, so a recently-logged
     * value returns quickly. Used to warn (without blocking) when a user adds a metric
     * they have no recent data for.
     */
    suspend fun hasRecentMetricData(
        recordClass: KClass<out Record>,
        metricKey: String?,
        lookbackDays: Long = RECENT_METRIC_LOOKBACK_DAYS
    ): Boolean = withContext(ioDispatcher) {
        val now = timeProvider.now()
        val start = now.minus(lookbackDays, ChronoUnit.DAYS)
        try {
            gateway.anyRecordInRange(recordClass, start, now) { record ->
                measurementExtractor.extractMeasurement(record, UnitPreference.METRIC, metricKey) != null
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) {
                throw exception
            }
            // Both callers launch this with no handler of their own, so a failed read (an exhausted
            // Health Connect read quota, say) was a crash. Unknown is reported as "has data": the
            // warning is advisory, and a false one is worse than a missing one.
            Log.w(LOG_TAG, "Failed to probe recent data (${exception.errorLabel()})")
            true
        }
    }

    /**
     * Reloads which metrics have data and publishes the result. **Never throws for a failed read**
     * — a failure is logged and the previously published list is kept.
     *
     * Both UI callers invoke this straight from a coroutine whose failure would take the process
     * down: the `LaunchedEffect` that runs on the first composition of [PermissionsGatedRoot], and
     * pull-to-refresh in `DataViewView`. Neither had error handling of its own, so an escaping
     * exception was a crash — issue #89, a launch crash for anyone whose Health Connect store makes
     * one of the 38 reads fail. The guard lives here rather than at the call sites so a third
     * caller cannot reintroduce it; [scheduleMetricsRefresh] applies the same policy.
     */
    suspend fun refreshMetricsWithData() {
        try {
            val values = loadMetricsWithData()
            synchronized(metricsLock) {
                metricsWithData.value = values
                metricsLastRefreshTick = maxOf(metricsLastRefreshTick, 0)
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) {
                throw exception
            }
            // Log the kind of failure, never the throwable. See errorLabel in StorageJson.kt.
            Log.w(LOG_TAG, "Failed to refresh metrics with data (${exception.errorLabel()})")
        } finally {
            synchronized(metricsLock) {
                metricsLoading = false
            }
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
                // Log the kind of failure, never the throwable. See errorLabel in StorageJson.kt.
                Log.w(LOG_TAG, "Failed to refresh metrics with data (${exception.errorLabel()})")
            } finally {
                synchronized(metricsLock) {
                    metricsLoading = false
                }
            }
        }
    }

    /**
     * Probes all 38 record types concurrently for "does this metric have any data?".
     *
     * Each probe is isolated: a type whose read fails degrades to "no data for this metric" instead
     * of failing the surrounding [coroutineScope] and, with it, the other 37 probes. Health Connect
     * makes single-type failures ordinary — a read permission that was never granted throws
     * SecurityException, and a record the library refuses to reconstruct (one stored with
     * `startTime == endTime`, say) throws IllegalArgumentException — and one such record used to
     * make the app permanently unlaunchable. See issue #89.
     */
    private suspend fun loadMetricsWithData(): List<KClass<out Record>> {
        val counts = withContext(ioDispatcher) {
            coroutineScope {
                PermissionsViewModel.CLASSES.map {
                    async {
                        val hasRecords = try {
                            gateway.hasRecordsForType(
                                cls = it,
                                now = timeProvider.now()
                            )
                        } catch (exception: Exception) {
                            if (exception is CancellationException) {
                                throw exception
                            }
                            // Log the kind of failure, never the throwable: Health Connect messages
                            // quote the offending record. See errorLabel in StorageJson.kt. The
                            // record class name is safe — it is a type, not user data, and
                            // proguard-rules.pro keeps these names on release builds.
                            Log.w(
                                LOG_TAG,
                                "No data reported for ${it.simpleName} " +
                                    "(${exception.errorLabel()}); other metrics are unaffected"
                            )
                            false
                        }
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
            recordLoader = listedRecordLoader
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

    /** The view's series exactly as its chart draws them — see [chartSeries]. */
    suspend fun loadAggregatedSeriesForExport(view: DataView): List<MetricSeries> {
        return chartSeries(view) { _, _ -> }.last()
    }

    /**
     * The view's series exactly as its chart draws them — see [chartSeries] — so a widget never
     * shows a different total from the app. Throws [HealthDataPermissionDeniedException] when
     * nothing could be drawn because a read permission is missing.
     */
    suspend fun loadAggregatedSeriesForWidget(view: DataView): List<MetricSeries> {
        val deniedRecordTypes = mutableSetOf<String>()
        val series = chartSeries(view) { cls, exception ->
            if (exception is SecurityException) {
                deniedRecordTypes.add(cls.qualifiedName ?: cls.simpleName.orEmpty())
            }
        }.last()
        if (series.isEmpty() && deniedRecordTypes.isNotEmpty()) {
            throw HealthDataPermissionDeniedException(deniedRecordTypes)
        }
        return series
    }

    private suspend fun loadRecordsForView(
        view: DataView,
        onPartialUpdate: ((List<Record>) -> Unit)? = null,
        throwOnPermissionDenied: Boolean = false,
        maxRecords: Int = Int.MAX_VALUE
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
            if (all.size >= maxRecords) break
            try {
                readRecordsInRange(
                    cls = cls,
                    start = queryStart,
                    end = now,
                    maxRecords = maxRecords - all.size,
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
                // Log the kind of failure, never the throwable. See errorLabel in StorageJson.kt.
                Log.w(
                    LOG_TAG,
                    "Failed to load records for ${cls.simpleName} (${exception.errorLabel()}), " +
                        "continuing with partial dataset"
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

    private suspend fun readRecordsInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        onPage: (List<Record>) -> Unit,
        maxRecords: Int = Int.MAX_VALUE
    ) {
        gateway.readRecordsInRange(
            cls = cls,
            start = start,
            end = end,
            maxRecords = maxRecords,
            onPage = onPage
        )
    }

    /**
     * Daily, weekly or monthly step totals for [start]..[end] from Health Connect's aggregation
     * API rather than its raw records: one read to find the oldest record, then one read per
     * [MAX_PERIODS_PER_AGGREGATE] buckets — a handful of reads for years of data, where raw
     * records took one read per 500 (issue #112). Health Connect deduplicates steps that several
     * apps recorded for the same time, so a phone-plus-watch user's totals here are lower than
     * the raw sum this replaced, and match what Health Connect itself reports.
     *
     * Null when this cannot serve the request (another record type, an intraday bucket, or a
     * gateway without aggregation), and the caller reads raw records instead.
     */
    private suspend fun stepTotals(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        bucketSize: BucketSize
    ): List<MetricMeasurement>? {
        if (cls != StepsRecord::class) return null
        val period = when (bucketSize) {
            BucketSize.DAY -> Period.ofDays(1)
            BucketSize.WEEK -> Period.ofWeeks(1)
            BucketSize.MONTH -> Period.ofMonths(1)
            BucketSize.MINUTE, BucketSize.HOUR -> return null
        }
        val zoneId = ZoneId.systemDefault()
        val oldest = gateway.oldestRecordTime(cls, start, end) ?: return emptyList()
        var from = aggregationEngine.toBucketInstant(oldest, bucketSize, zoneId)
            .atZone(zoneId).toLocalDateTime()
        val until = end.atZone(zoneId).toLocalDateTime()
        val totals = mutableListOf<MetricMeasurement>()
        while (from < until) {
            val to = minOf(from.plus(period.multipliedBy(MAX_PERIODS_PER_AGGREGATE)), until)
            val slices = gateway.stepTotalsByPeriod(from, to, period) ?: return null
            slices.mapTo(totals) { (sliceStart, steps) ->
                MetricMeasurement(
                    timestamp = sliceStart.atZone(zoneId).toInstant(),
                    value = steps.toDouble(),
                    unitLabel = "count",
                    sourceField = "Count"
                )
            }
            from = to
        }
        return totals
    }

    /** True when [record] yields a value for at least one of the view's selected metrics. */
    private fun recordHasSelectedMetric(view: DataView, record: Record): Boolean {
        return view.records.any { sel ->
            val cls = PermissionsViewModel.classForFqn(sel.fqn)
            cls?.java?.isAssignableFrom(record.javaClass) == true &&
                measurementExtractor.extractMeasurement(record, UnitPreference.METRIC, sel.metricKey) != null
        }
    }

    /**
     * Counts the view's entries as pages arrive. Reads at most [MAX_LISTED_RECORDS] raw records per
     * record type, newest first, so a count of [MAX_LISTED_RECORDS] or more means "at least".
     */
    fun collectRecordCount(view: DataView): Flow<Int> = channelFlow {
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
                    count += pageRecords.count { recordHasSelectedMetric(view, it) }
                    trySend(count)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                // Log the kind of failure, never the throwable. See errorLabel in StorageJson.kt.
                Log.w(LOG_TAG, "Failed to read record count for ${cls.simpleName} (${exception.errorLabel()})")
            }
        }
        if (count == 0) trySend(0)
    }.flowOn(ioDispatcher)

    /**
     * Whether the latest load of view [viewId]'s chart had a Health Connect read fail — an
     * exhausted read quota, say — so the chart may be empty or partial although data exists.
     */
    fun collectChartReadFailed(viewId: Int): Flow<Boolean> =
        chartReadFailures.map { viewId in it }.distinctUntilChanged()

    fun collectAggregatedSeries(view: DataView): Flow<List<MetricSeries>> =
        chartSeries(view) { _, _ -> chartReadFailures.update { it + view.id } }
            .onStart { chartReadFailures.update { it - view.id } }

    /**
     * The view's chart series, shared by the chart, the widget and the CSV export so they agree:
     * step SUM series from Health Connect's totals ([stepTotals]), everything else from at most
     * [MAX_CHART_RECORDS] raw records per type. A failed read degrades that series to what was
     * read before it failed, and is reported to [onReadFailure].
     */
    private fun chartSeries(
        view: DataView,
        onReadFailure: (KClass<out Record>, Exception) -> Unit
    ): Flow<List<MetricSeries>> {
        suspend fun <T> reportingFailure(cls: KClass<out Record>, read: suspend () -> T): T = try {
            read()
        } catch (exception: Exception) {
            if (exception !is CancellationException) onReadFailure(cls, exception)
            throw exception
        }
        return collectAggregatedSeries(
            view = view,
            bucketTotalsReader = { cls, start, end, bucketSize ->
                reportingFailure(cls) { stepTotals(cls, start, end, bucketSize) }
            },
            pageReader = { cls, start, end, onPage ->
                reportingFailure(cls) {
                    readRecordsInRange(cls, start, end, onPage, maxRecords = MAX_CHART_RECORDS)
                }
            }
        )
    }

    internal fun collectAggregatedSeries(
        view: DataView,
        bucketTotalsReader: BucketTotalsReader? = null,
        pageReader: suspend (
            cls: KClass<out Record>,
            start: Instant,
            end: Instant,
            onPage: (List<Record>) -> Unit
        ) -> Unit
    ): Flow<List<MetricSeries>> {
        return aggregationEngine.collectAggregatedSeries(
            view = view,
            now = timeProvider.now(),
            maxSeries = MAX_CHART_SERIES,
            pageReader = pageReader,
            bucketTotalsReader = bucketTotalsReader
        ).flowOn(ioDispatcher)
    }

    /**
     * A single aggregated point on a series. [instant] is the bucket's start time (truncated to the
     * bucket's granularity in the view's zone), which the chart axis positions by — so an intraday
     * hour/minute bucket lands at its own X coordinate instead of collapsing onto a day. [date] is
     * that instant's day in the system zone, kept for the day-granular callers (bar slots, older
     * tests) that still key by calendar day.
     */
    data class MetricPoint(val instant: Instant, val value: Double) {
        val date: LocalDate get() = instant.atZone(ZoneId.systemDefault()).toLocalDate()

        /** Day-granular convenience: a point at the start of [date] in the system zone. */
        constructor(date: LocalDate, value: Double) : this(
            date.atStartOfDay(ZoneId.systemDefault()).toInstant(),
            value
        )
    }

    data class MetricSeries(
        val label: String,
        val unit: String?,
        val points: List<MetricPoint>,
        val peakValueInWindow: Double = points.maxOfOrNull { it.value } ?: 0.0,
        val minValueInWindow: Double = points.minOfOrNull { it.value } ?: 0.0,
        val showMaxLabel: Boolean = true,
        val showMinLabel: Boolean = false,
        val yAxisMode: YAxisMode = YAxisMode.AUTO,
        /**
         * Per-bucket minimum/maximum envelope for an [AggregationMode.MIN_MAX_AVG] series,
         * aligned 1:1 with [points] (which carry the average). Null for every other mode, so a
         * min/max/avg result stays one logical series carrying its band rather than three series.
         */
        val bandMin: List<MetricPoint>? = null,
        val bandMax: List<MetricPoint>? = null
    ) {
        /** True when this series carries a min–max envelope to draw around its avg line. */
        val hasBand: Boolean get() = bandMin != null && bandMax != null
    }

    fun aggregateMetricSeriesList(view: DataView, records: List<Record>): List<MetricSeries> {
        return aggregationEngine.aggregateMetricSeriesList(view, records)
    }

    fun aggregateMetricSeries(view: DataView, records: List<Record>): MetricSeries? {
        return aggregationEngine.aggregateMetricSeries(view, records)
    }

    private fun windowStart(timeWindow: TimeWindow): Instant? {
        return aggregationEngine.windowStart(timeWindow, timeProvider.now())
    }

    private fun recordTimestamp(record: Record): Instant? {
        return measurementExtractor.recordTimestamp(record)
    }

}

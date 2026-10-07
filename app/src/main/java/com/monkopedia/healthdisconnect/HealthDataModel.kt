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
         * chart draws its most recent records only and says so ([ChartLoadIssues.truncatedSince]).
         * Day/week/month step charts read no raw records at all — see [dailyStepTotals].
         */
        const val MAX_CHART_RECORDS = 100_000

        /**
         * Most raw records the entries count and entries list read per record type, newest first.
         * A count that stops here is shown as "at least" ([RecordCount.atLeast]).
         */
        const val MAX_LISTED_RECORDS = 10_000

        /** Periods per Health Connect aggregate request, so a request never spans unbounded groups. */
        const val MAX_PERIODS_PER_AGGREGATE = 365
    }

    data class RecordSelectionOption(
        val selection: RecordSelection,
        val label: String
    )

    /** A view's entry count; [atLeast] when a record type had more than [MAX_LISTED_RECORDS]. */
    data class RecordCount(val count: Int, val atLeast: Boolean = false)

    /** Why a chart (or its export) may show less than the data Health Connect holds. */
    data class ChartLoadIssues(
        val readFailure: ReadFailure? = null,
        /** Start of the data drawn, when a series stopped at [MAX_CHART_RECORDS]. */
        val truncatedSince: Instant? = null
    ) {
        /** A rate limit outranks any other failure: it is the one the user can wait out. */
        fun withFailure(exception: Exception): ChartLoadIssues = when {
            readFailure == ReadFailure.RATE_LIMITED -> this
            exception.isHealthConnectRateLimit() -> copy(readFailure = ReadFailure.RATE_LIMITED)
            else -> copy(readFailure = ReadFailure.OTHER)
        }

        /** With several truncated series, the data is complete only from the latest start. */
        fun withTruncation(since: Instant): ChartLoadIssues =
            copy(truncatedSince = maxOf(truncatedSince ?: since, since))

        val isComplete: Boolean get() = readFailure == null && truncatedSince == null
    }

    /** An aggregated export: the chart's series, and what they could not include. */
    data class ExportedSeries(val series: List<MetricSeries>, val issues: ChartLoadIssues)

    enum class ReadFailure {
        /** Health Connect refused reads because this app exhausted its read quota. */
        RATE_LIMITED,
        OTHER
    }

    private val metricsLock = Any()
    private val metricsWithData: MutableStateFlow<List<KClass<out Record>>?> = MutableStateFlow(null)
    private val chartLoadIssues = MutableStateFlow<Map<Int, ChartLoadIssues>>(emptyMap())
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
                ?: loadRecordsForView(view, onPartial, maxRecordsPerType = MAX_LISTED_RECORDS)
        }

    /** Pages the records counted as entries; true when older records were left unread. */
    private val pageReader: suspend (
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        onPage: (List<Record>) -> Unit
    ) -> Boolean = { cls, start, end, onPage ->
        pageReaderOverride?.invoke(cls, start, end, onPage)?.let { false }
            ?: readNewestRecords(cls, start, end, MAX_LISTED_RECORDS, onPage)
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

    /**
     * The view's series exactly as its chart draws them — see [chartSeries] — with what they could
     * not include, so the export can tell the user it is incomplete rather than silently starting
     * later than the window they chose.
     */
    suspend fun loadAggregatedSeriesForExport(view: DataView): ExportedSeries {
        var issues = ChartLoadIssues()
        val series = chartSeries(
            view = view,
            onReadFailure = { _, exception -> issues = issues.withFailure(exception) },
            onTruncated = { since -> issues = issues.withTruncation(since) }
        ).last()
        return ExportedSeries(series, issues)
    }

    /**
     * The view's series exactly as its chart draws them — see [chartSeries] — so a widget never
     * shows a different total from the app. Throws [HealthDataPermissionDeniedException] when
     * nothing could be drawn because a read permission is missing.
     *
     * Unlike the chart and the export, the widget does not say when a series was cut short by
     * [MAX_CHART_RECORDS] or a read failed for any other reason: it is a small background-refreshed
     * surface, and the app's chart for the same view says so.
     */
    suspend fun loadAggregatedSeriesForWidget(view: DataView): List<MetricSeries> {
        val deniedRecordTypes = mutableSetOf<String>()
        val series = chartSeries(
            view = view,
            onReadFailure = { cls, exception ->
                if (exception is SecurityException) {
                    deniedRecordTypes.add(cls.qualifiedName ?: cls.simpleName.orEmpty())
                }
            },
            onTruncated = {}
        ).last()
        if (series.isEmpty() && deniedRecordTypes.isNotEmpty()) {
            throw HealthDataPermissionDeniedException(deniedRecordTypes)
        }
        return series
    }

    private suspend fun loadRecordsForView(
        view: DataView,
        onPartialUpdate: ((List<Record>) -> Unit)? = null,
        throwOnPermissionDenied: Boolean = false,
        maxRecordsPerType: Int = Int.MAX_VALUE
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
                    maxRecords = maxRecordsPerType,
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
     * Reads the newest [limit] records of [cls] into [onPage]. Returns true when older records
     * were left unread — one record past [limit] is requested to tell "exactly [limit]" apart.
     */
    private suspend fun readNewestRecords(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        limit: Int,
        onPage: (List<Record>) -> Unit
    ): Boolean {
        var delivered = 0
        var truncated = false
        readRecordsInRange(cls, start, end, maxRecords = limit + 1, onPage = { page ->
            val kept = page.take(limit - delivered)
            if (kept.size < page.size) truncated = true
            delivered += kept.size
            if (kept.isNotEmpty()) onPage(kept)
        })
        return truncated
    }

    /**
     * Daily step totals for [start]..[end] from Health Connect's aggregation API rather than its
     * raw records, for charts bucketed by day, week or month: one read to find the oldest record,
     * then one read per [MAX_PERIODS_PER_AGGREGATE] days — a handful of reads for years of data,
     * where raw records took one read per 500 (issue #112). Health Connect deduplicates steps
     * that several apps recorded for the same time, so a phone-plus-watch user's totals here are
     * lower than the raw sum this replaced, and match what Health Connect itself reports.
     *
     * Null when this cannot serve the request (another record type, an intraday bucket, or a
     * gateway without aggregation), and the caller reads raw records instead.
     */
    private suspend fun dailyStepTotals(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        bucketSize: BucketSize
    ): List<MetricMeasurement>? {
        if (cls != StepsRecord::class) return null
        if (bucketSize == BucketSize.MINUTE || bucketSize == BucketSize.HOUR) return null
        val day = Period.ofDays(1)
        val zoneId = ZoneId.systemDefault()
        val oldest = gateway.oldestRecordTime(cls, start, end) ?: return emptyList()
        var from = oldest.atZone(zoneId).toLocalDate().atStartOfDay()
        val until = end.atZone(zoneId).toLocalDateTime()
        val totals = mutableListOf<MetricMeasurement>()
        while (from < until) {
            val to = minOf(from.plus(day.multipliedBy(MAX_PERIODS_PER_AGGREGATE)), until)
            val slices = gateway.stepTotalsByPeriod(from, to, day) ?: return null
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
     * record type, newest first, and marks the final count [RecordCount.atLeast] when a type had
     * more — the same records the entries list shows.
     */
    fun collectRecordCount(view: DataView): Flow<RecordCount> = channelFlow {
        val typeMap: Map<String, KClass<out Record>> =
            PermissionsViewModel.CLASSES.associateBy { it.qualifiedName ?: "" }
        val selections: List<KClass<out Record>> = view.records.mapNotNull { sel ->
            typeMap[sel.fqn]
        }.distinctBy { it.qualifiedName.orEmpty() }
        val now = timeProvider.now()
        val queryStart = windowStart(view.chartSettings.timeWindow) ?: Instant.EPOCH
        var count = 0
        var atLeast = false
        selections.forEach { cls ->
            try {
                val truncated = pageReader(cls, queryStart, now) { pageRecords ->
                    count += pageRecords.count { recordHasSelectedMetric(view, it) }
                    trySend(RecordCount(count))
                }
                atLeast = atLeast || truncated
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                // Log the kind of failure, never the throwable. See errorLabel in StorageJson.kt.
                Log.w(LOG_TAG, "Failed to read record count for ${cls.simpleName} (${exception.errorLabel()})")
            }
        }
        send(RecordCount(count, atLeast))
    }.flowOn(ioDispatcher)

    /** What the latest load of view [viewId]'s chart could not show — see [ChartLoadIssues]. */
    fun collectChartLoadIssues(viewId: Int): Flow<ChartLoadIssues> =
        chartLoadIssues.map { it[viewId] ?: ChartLoadIssues() }.distinctUntilChanged()

    fun collectAggregatedSeries(view: DataView): Flow<List<MetricSeries>> {
        fun note(change: (ChartLoadIssues) -> ChartLoadIssues) = chartLoadIssues.update {
            it + (view.id to change(it[view.id] ?: ChartLoadIssues()))
        }
        return chartSeries(
            view = view,
            onReadFailure = { _, exception -> note { it.withFailure(exception) } },
            onTruncated = { since -> note { it.withTruncation(since) } }
        ).onStart { chartLoadIssues.update { it - view.id } }
    }

    /**
     * The view's chart series, shared by the chart, the widget and the CSV export so they agree:
     * day/week/month step series from Health Connect's daily totals ([dailyStepTotals]),
     * everything else from at most [MAX_CHART_RECORDS] raw records per type, reporting where that
     * cap cut a series short to [onTruncated]. A failed read degrades that series to what was
     * read before it failed, and is reported to [onReadFailure].
     */
    private fun chartSeries(
        view: DataView,
        onReadFailure: (KClass<out Record>, Exception) -> Unit,
        onTruncated: (Instant) -> Unit
    ): Flow<List<MetricSeries>> {
        suspend fun <T> reportingFailure(cls: KClass<out Record>, read: suspend () -> T): T = try {
            read()
        } catch (exception: Exception) {
            if (exception !is CancellationException) onReadFailure(cls, exception)
            throw exception
        }
        return collectAggregatedSeries(
            view = view,
            dailyTotalsReader = { cls, start, end, bucketSize ->
                reportingFailure(cls) { dailyStepTotals(cls, start, end, bucketSize) }
            },
            pageReader = { cls, start, end, onPage ->
                reportingFailure(cls) {
                    var oldest: Instant? = null
                    val truncated = readNewestRecords(cls, start, end, MAX_CHART_RECORDS) { page ->
                        page.mapNotNull(::recordTimestamp).minOrNull()?.let { pageOldest ->
                            oldest = minOf(oldest ?: pageOldest, pageOldest)
                        }
                        onPage(page)
                    }
                    oldest?.takeIf { truncated }?.let(onTruncated)
                }
            }
        )
    }

    internal fun collectAggregatedSeries(
        view: DataView,
        dailyTotalsReader: DailyTotalsReader? = null,
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
            dailyTotalsReader = dailyTotalsReader
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

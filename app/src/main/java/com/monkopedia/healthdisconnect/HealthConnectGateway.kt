package com.monkopedia.healthdisconnect

import android.health.connect.HealthConnectException
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import kotlin.reflect.KClass

interface HealthConnectGateway {
    suspend fun hasRecordsForType(
        cls: KClass<out Record>,
        now: Instant,
        pageSize: Int = 1
    ): Boolean

    /**
     * Pages the records of [cls] in [start]..[end] into [onPage], newest first, stopping once
     * [maxRecords] have been delivered. Every page is one Health Connect read, and Health Connect
     * rate-limits reads per app, so a caller that does not need everything should pass a bound.
     */
    suspend fun readRecordsInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        pageSize: Int = 500,
        maxRecords: Int = Int.MAX_VALUE,
        onPage: (List<Record>) -> Unit
    )

    /**
     * Returns true as soon as a record in [start]..[end] satisfies [predicate]. Reads
     * most-recent-first and short-circuits, so a recently-logged match returns quickly
     * without scanning the whole window. The default delegates to [readRecordsInRange]
     * (correct but unordered); [DefaultHealthConnectGateway] overrides it efficiently.
     */
    suspend fun anyRecordInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        pageSize: Int = 200,
        predicate: (Record) -> Boolean
    ): Boolean {
        var found = false
        readRecordsInRange(cls, start, end, pageSize) { page ->
            if (!found && page.any(predicate)) found = true
        }
        return found
    }

    /**
     * The timestamp of the oldest record of [cls] in [start]..[end], or null when there is none.
     * The default scans the range; [DefaultHealthConnectGateway] answers with a single read.
     */
    suspend fun oldestRecordTime(cls: KClass<out Record>, start: Instant, end: Instant): Instant? {
        var oldest: Instant? = null
        readRecordsInRange(cls, start, end) { page ->
            page.forEach { record ->
                val time = recordTimestamp(record) ?: return@forEach
                if (oldest?.isAfter(time) != false) oldest = time
            }
        }
        return oldest
    }

    /**
     * Total steps in each [period]-long slice of [start]..[end] (local time), as Health Connect
     * aggregates them: one read per call however many records the range holds, and deduplicated
     * across apps that record the same steps. Slices with no steps are omitted.
     *
     * Null means this gateway cannot aggregate, and callers fall back to [readRecordsInRange].
     */
    suspend fun stepTotalsByPeriod(
        start: LocalDateTime,
        end: LocalDateTime,
        period: Period
    ): List<Pair<LocalDateTime, Long>>? = null
}

class DefaultHealthConnectGateway(
    private val healthConnectClient: HealthConnectClient
) : HealthConnectGateway {
    override suspend fun hasRecordsForType(cls: KClass<out Record>, now: Instant, pageSize: Int): Boolean {
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = cls,
                timeRangeFilter = TimeRangeFilter.before(now),
                pageSize = pageSize
            )
        )
        return response.records.isNotEmpty()
    }

    override suspend fun readRecordsInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        pageSize: Int,
        maxRecords: Int,
        onPage: (List<Record>) -> Unit
    ) {
        var remaining = maxRecords
        var pageToken: String? = null
        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = cls,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = pageSize,
                    pageToken = pageToken,
                    ascendingOrder = false
                )
            )
            val page = response.records.take(remaining)
            onPage(page)
            remaining -= page.size
            pageToken = response.pageToken
        } while (pageToken != null && remaining > 0)
    }

    override suspend fun anyRecordInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        pageSize: Int,
        predicate: (Record) -> Boolean
    ): Boolean {
        var pageToken: String? = null
        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = cls,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = pageSize,
                    pageToken = pageToken,
                    ascendingOrder = false
                )
            )
            if (response.records.any(predicate)) {
                return true
            }
            pageToken = response.pageToken
        } while (pageToken != null)
        return false
    }

    override suspend fun oldestRecordTime(cls: KClass<out Record>, start: Instant, end: Instant): Instant? {
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = cls,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                pageSize = 1,
                ascendingOrder = true
            )
        )
        return response.records.firstOrNull()?.let(::recordTimestamp)
    }

    override suspend fun stepTotalsByPeriod(
        start: LocalDateTime,
        end: LocalDateTime,
        period: Period
    ): List<Pair<LocalDateTime, Long>> {
        return healthConnectClient.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end),
                timeRangeSlicer = period
            )
        ).mapNotNull { slice ->
            slice.result[StepsRecord.COUNT_TOTAL]?.let { slice.startTime to it }
        }
    }
}

/**
 * True when Health Connect refused a call because this app exhausted its rate limit. connect-client
 * 1.1.0 has no exception type for it: its platform `toKtException` maps the codes it knows
 * (invalid argument, IO, security, remote) and wraps anything else, ERROR_RATE_LIMIT_EXCEEDED
 * included, in an IllegalStateException whose cause is the platform exception.
 */
internal fun Exception.isHealthConnectRateLimit(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        (cause as? HealthConnectException)?.errorCode == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED

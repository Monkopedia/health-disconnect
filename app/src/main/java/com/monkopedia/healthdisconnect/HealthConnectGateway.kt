package com.monkopedia.healthdisconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

interface HealthConnectGateway {
    suspend fun hasRecordsForType(
        cls: KClass<out Record>,
        now: Instant,
        pageSize: Int = 1
    ): Boolean

    suspend fun readRecordsInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        pageSize: Int = 500,
        onPage: (List<Record>) -> Unit
    )
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
        onPage: (List<Record>) -> Unit
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
}


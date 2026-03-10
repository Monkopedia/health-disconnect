package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
import kotlin.reflect.KClass

class DemoHealthConnectGateway : HealthConnectGateway {

    private val supportedTypes: Set<KClass<out Record>> = setOf(
        WeightRecord::class,
        StepsRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        SleepSessionRecord::class,
        BodyFatRecord::class,
        DistanceRecord::class
    )

    override suspend fun hasRecordsForType(
        cls: KClass<out Record>,
        now: Instant,
        pageSize: Int
    ): Boolean {
        return cls in supportedTypes
    }

    override suspend fun readRecordsInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        pageSize: Int,
        onPage: (List<Record>) -> Unit
    ) {
        val records = DemoRecordFactory.generate(cls, start, end)
        if (records.isNotEmpty()) {
            onPage(records)
        }
    }
}

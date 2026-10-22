package com.monkopedia.healthdisconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import com.monkopedia.healthdisconnect.ui.MetricDebugRange
import com.monkopedia.healthdisconnect.ui.loadMetricDebugRanges
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #122: "Refresh Debug Ranges" read every record of every type to find each type's oldest
 * and newest, one Health Connect read per 500 records. It needs two single-record reads per type.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDebugRangesTest {

    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private val stepsTotal = 10_000
    private val oldestStep = now.minus(Duration.ofDays(stepsTotal.toLong()))
    private val readsByType = mutableMapOf<String, Int>()

    private val client: HealthConnectClient = mockk {
        coEvery { readRecords(any<ReadRecordsRequest<Record>>()) } answers { readPage(firstArg()) }
    }

    /** [stepsTotal] daily steps records ending yesterday; weight reads fail; everything else is empty. */
    private fun readPage(request: ReadRecordsRequest<Record>): ReadRecordsResponse<Record> {
        val type = request.recordType.simpleName!!
        readsByType[type] = (readsByType[type] ?: 0) + 1
        return when (request.recordType) {
            WeightRecord::class -> throw IOException("Health Connect unavailable")
            StepsRecord::class -> {
                val offset = request.pageToken?.toInt() ?: 0
                val size = minOf(request.pageSize, stepsTotal - offset)
                val records = (offset until offset + size).map { position ->
                    stepsRecord(if (request.ascendingOrder) position else stepsTotal - 1 - position)
                }
                ReadRecordsResponse(records, (offset + size).takeIf { it < stepsTotal }?.toString())
            }
            else -> ReadRecordsResponse(emptyList(), null)
        }
    }

    private fun stepsRecord(day: Int): StepsRecord {
        val start = oldestStep.plus(Duration.ofDays(day.toLong()))
        return StepsRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = start.plus(Duration.ofHours(1)),
            endZoneOffset = ZoneOffset.UTC,
            count = 100,
            metadata = Metadata.manualEntry()
        )
    }

    private fun load(vararg granted: kotlin.reflect.KClass<out Record>): Map<String, MetricDebugRange> =
        runBlocking {
            loadMetricDebugRanges(
                DefaultHealthConnectGateway(client),
                granted.map { PermissionsViewModel.READ_PERMISSIONS_BY_CLASS.getValue(it) }.toSet(),
                now
            ).associateBy { it.label }
        }

    @Test
    fun `each type's range takes two single-record reads however many records it holds`() {
        val rows = load(StepsRecord::class, HeartRateRecord::class)

        // The unbounded scan took one read per 500 records: 20 for these 10,000 steps.
        assertEquals(2, readsByType["StepsRecord"])
        // Steps cadence shares the steps read permission, so it is read too; nothing else is.
        assertEquals(
            mapOf("StepsRecord" to 2, "StepsCadenceRecord" to 2, "HeartRateRecord" to 2),
            readsByType
        )
        val steps = rows.getValue(PermissionsViewModel.recordLabel(StepsRecord::class))
        assertEquals(oldestStep, steps.oldest)
        assertEquals(oldestStep.plus(Duration.ofDays(stepsTotal - 1L)), steps.newest)
    }

    @Test
    fun `a type whose read fails is marked on its own row and the others still load`() {
        val rows = load(WeightRecord::class, StepsRecord::class, HeartRateRecord::class)

        val weight = rows.getValue(PermissionsViewModel.recordLabel(WeightRecord::class))
        assertTrue(weight.permissionGranted)
        assertTrue(weight.readFailed)
        val steps = rows.getValue(PermissionsViewModel.recordLabel(StepsRecord::class))
        assertFalse(steps.readFailed)
        assertEquals(oldestStep, steps.oldest)
        val heartRate = rows.getValue(PermissionsViewModel.recordLabel(HeartRateRecord::class))
        assertFalse(heartRate.readFailed)
        assertEquals(PermissionsViewModel.CLASSES.size, rows.size)
        assertTrue(rows.values.filter { !it.permissionGranted }.none { it.readFailed })
    }
}

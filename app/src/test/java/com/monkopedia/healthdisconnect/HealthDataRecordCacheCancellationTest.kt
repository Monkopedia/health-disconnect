package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving the Entries screen cancels its ViewModel's scope, and with it any record load that
 * screen started in the process-wide cache. That must not leave the cache entry latched as
 * loading (issue #105).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthDataRecordCacheCancellationTest {

    private val view = DataView(
        id = 1,
        type = ViewType.CHART,
        records = listOf(RecordSelection(StepsRecord::class))
    )
    private val records = listOf(mockk<Record>())
    private val policy = DefaultHealthDataRecordCachePolicy()

    /** A gateway whose reads suspend until the test releases them. */
    private class SuspendingGateway {
        val reads = mutableListOf<CompletableDeferred<List<Record>>>()

        suspend fun load(onPartialUpdate: ((List<Record>) -> Unit)?, partial: List<Record>?): List<Record> {
            partial?.let { onPartialUpdate?.invoke(it) }
            return CompletableDeferred<List<Record>>().also { reads += it }.await()
        }
    }

    @Test
    fun `a load cancelled by leaving the screen does not stop the next visit loading`() = runTest {
        val gateway = SuspendingGateway()
        val firstVisit = screen()
        firstVisit.collectFirst(gateway)
        runCurrent()
        assertEquals(1, gateway.reads.size)

        firstVisit.leave()
        runCurrent()

        val secondVisit = screen()
        val result = secondVisit.collectFirst(gateway)
        runCurrent()
        assertEquals("the second visit must start its own read", 2, gateway.reads.size)
        gateway.reads[1].complete(records)
        advanceUntilIdle()

        assertTrue("re-entering the view must produce data, not hang", result.isCompleted)
        assertSame(records, result.await())
        secondVisit.leave()
    }

    @Test
    fun `a collector that joined a load which is then cancelled starts its own`() = runTest {
        val gateway = SuspendingGateway()
        val leaving = screen()
        val staying = screen()
        leaving.collectFirst(gateway)
        runCurrent()
        val result = staying.collectFirst(gateway)
        runCurrent()
        assertEquals("the second collector joins the read in flight", 1, gateway.reads.size)

        leaving.leave()
        runCurrent()

        assertEquals("the remaining collector must start a read of its own", 2, gateway.reads.size)
        gateway.reads[1].complete(records)
        advanceUntilIdle()
        assertTrue("the remaining collector must get data, not hang", result.isCompleted)
        assertSame(records, result.await())
        staying.leave()
    }

    @Test
    fun `a partial page from a cancelled load is replaced on the next visit`() = runTest {
        val gateway = SuspendingGateway()
        val partial = listOf(mockk<Record>())
        val firstVisit = screen()
        firstVisit.collectFirst(gateway, partial)
        runCurrent()
        firstVisit.leave()
        runCurrent()

        val secondVisit = screen()
        val result = secondVisit.async {
            secondVisit.collect(gateway).first { it !== partial }
        }
        runCurrent()
        assertEquals("the partial page must not suppress the next read", 2, gateway.reads.size)
        gateway.reads[1].complete(records)
        advanceUntilIdle()

        assertSame(records, result.await())
        secondVisit.leave()
    }

    /** One visit to the screen: its viewModelScope, on the test's scheduler. */
    private inner class Screen(scheduler: TestCoroutineScheduler) {
        private val dispatcher = StandardTestDispatcher(scheduler)
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)

        fun collect(gateway: SuspendingGateway, partial: List<Record>? = null) = policy.collectData(
            view = view,
            refreshTick = 0,
            ioScope = scope,
            ioDispatcher = dispatcher,
            recordLoader = { _, onPartialUpdate -> gateway.load(onPartialUpdate, partial) }
        )

        fun <T> async(block: suspend () -> T) = scope.async { block() }

        fun collectFirst(gateway: SuspendingGateway, partial: List<Record>? = null) =
            async { collect(gateway, partial).first() }

        /** Leaving the screen clears its ViewModel, which cancels viewModelScope. */
        fun leave() = scope.cancel()
    }

    private fun TestScope.screen() = Screen(testScheduler)
}

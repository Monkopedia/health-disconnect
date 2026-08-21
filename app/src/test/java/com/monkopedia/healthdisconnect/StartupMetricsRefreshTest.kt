package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #89: one record type that fails must not take the whole startup down.
 *
 * The reported crash is a launch crash on v1.2.2: [HealthDataModel.loadMetricsWithData] fans all 38
 * record types out concurrently and awaits them together, so a single failing read failed the whole
 * scope — and the composable that kicks that refresh off on first composition let the exception
 * escape its `LaunchedEffect`, killing the process. Both candidate triggers (a read permission that
 * was never granted, and a stored record the Health Connect library refuses to reconstruct) reach
 * the app the same way: [HealthConnectGateway.hasRecordsForType] throws for one type.
 */
private class OneFailingTypeGateway(
    private val failingType: KClass<out Record> = SexualActivityRecord::class,
    private val failure: () -> Exception = { SecurityException("Caller doesn't have permission") },
    private val othersHaveData: Boolean = false
) : HealthConnectGateway {
    override suspend fun hasRecordsForType(
        cls: KClass<out Record>,
        now: Instant,
        pageSize: Int
    ): Boolean {
        if (cls == failingType) throw failure()
        return othersHaveData
    }

    override suspend fun readRecordsInRange(
        cls: KClass<out Record>,
        start: Instant,
        end: Instant,
        pageSize: Int,
        onPage: (List<Record>) -> Unit
    ) = Unit
}

@RunWith(AndroidJUnit4::class)
class StartupMetricsRefreshTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun model(gateway: HealthConnectGateway) = HealthDataModel(
        app = ApplicationProvider.getApplicationContext<Application>(),
        autoRefreshMetrics = false,
        healthConnectGateway = gateway
    )

    /** The suspend refresh the UI calls directly must not rethrow a failed record read. */
    @Test
    fun refreshMetricsWithData_doesNotPropagateAFailingRecordType() = runBlocking {
        // Fails before the fix with the SecurityException thrown out of awaitAll().
        model(OneFailingTypeGateway()).refreshMetricsWithData()
    }

    /**
     * The same for a record the Health Connect library refuses to construct
     * (`startTime must be before endTime` surfaces as IllegalArgumentException).
     */
    @Test
    fun refreshMetricsWithData_doesNotPropagateAnUnconstructibleRecord() = runBlocking {
        val gateway = OneFailingTypeGateway(
            failure = { IllegalArgumentException("startTime must be before endTime") }
        )
        model(gateway).refreshMetricsWithData()
    }

    /** The other 37 types still report their results when one of them fails. */
    @Test
    fun loadMetricsWithData_keepsTheOtherTypesWhenOneFails() = runBlocking {
        val model = model(OneFailingTypeGateway(othersHaveData = true))
        model.refreshMetricsWithData()

        val metrics = model.collectMetricsWithData().first()
        val expected = PermissionsViewModel.CLASSES - SexualActivityRecord::class
        assertEquals(expected.size, metrics.size)
        assertEquals(expected, metrics.toSet())
        assertTrue(
            "the failing type must degrade to \"no data\", not be reported as having data",
            SexualActivityRecord::class !in metrics
        )
    }

    /**
     * The crash path itself: first composition with permissions believed granted starts the refresh
     * inside a `LaunchedEffect`, where an escaping exception takes the process down.
     */
    @Test
    fun permissionsGatedRoot_survivesAFailingRecordType() {
        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.availabilityStatus } returns HealthConnectClient.SDK_AVAILABLE
        // The real-world initial state: the granted-permissions IPC has not answered yet, so
        // collectAsStateWithLifecycle is still serving `false` and the permitted UI is composed.
        every { permissionsViewModel.needsPermissions } returns flowOf(false)

        var escaped: Throwable? = null
        try {
            composeRule.setContent {
                HealthDisconnectTheme(dynamicColor = false) {
                    PermissionsGatedRoot(
                        permittedContent = { Text("Select basic metric") },
                        permissionsViewModel = permissionsViewModel,
                        healthDataModel = model(OneFailingTypeGateway())
                    )
                }
            }
            composeRule.onNodeWithText("Select basic metric").assertIsDisplayed()
            composeRule.mainClock.advanceTimeBy(2_000)
            composeRule.waitForIdle()
        } catch (throwable: Throwable) {
            escaped = throwable
        }
        assertEquals(
            "a failing record type must not escape the startup LaunchedEffect",
            null,
            escaped
        )
    }
}

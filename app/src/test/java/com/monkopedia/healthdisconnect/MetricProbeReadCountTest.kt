package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.ui.DataViewView
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A metric probe is 38 Health Connect reads against a rate-limited quota (issue #112), so the
 * re-probing added for issue #123 must not multiply: one per dialog opening, and one at a time.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MetricProbeReadCountTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Counts probes by their Steps read; no type has data, so the cached result stays empty. */
    private class CountingGateway(private val stepsGate: CompletableDeferred<Unit>? = null) : HealthConnectGateway {
        val probes = AtomicInteger()

        override suspend fun hasRecordsForType(cls: KClass<out Record>, now: Instant, pageSize: Int): Boolean {
            if (cls == StepsRecord::class) {
                probes.incrementAndGet()
                stepsGate?.await()
            }
            return false
        }

        override suspend fun readRecordsInRange(
            cls: KClass<out Record>,
            start: Instant,
            end: Instant,
            pageSize: Int,
            maxRecords: Int,
            onPage: (List<Record>) -> Unit
        ) = Unit
    }

    /** Everything on the main looper, so `waitForIdle` also waits for every probe to finish. */
    private object MainDispatchers : CoroutineDispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Main
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val default: CoroutineDispatcher = Dispatchers.Main
    }

    @Test
    fun retryingDuringAnInFlightProbeJoinsIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val gateway = CountingGateway(gate)
        val model = HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false,
            healthConnectGateway = gateway
        )

        // The entry probe, then a double-tapped "Try again", all while the first is still reading.
        model.collectMetricsWithData()
        val retries = List(2) { async(Dispatchers.Default) { model.refreshMetricsWithData() } }
        withTimeout(5_000) { while (gateway.probes.get() == 0) delay(5) }
        delay(200)
        gate.complete(Unit)
        withTimeout(5_000) { retries.awaitAll() }

        assertEquals(1, gateway.probes.get())
    }

    @Test
    fun recomposingAnOpenMetricDialogDoesNotReprobe() {
        val gateway = CountingGateway()
        val model = HealthDataModel(
            app = ApplicationProvider.getApplicationContext<Application>(),
            autoRefreshMetrics = false,
            healthConnectGateway = gateway,
            dispatchers = MainDispatchers
        )
        val grantedPermissions = MutableStateFlow(setOf(PermissionsViewModel.HISTORY_PERMISSION))
        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.grantedPermissions } returns grantedPermissions
        val viewModel = mockk<DataViewAdapterViewModel>(relaxed = true)
        every { viewModel.dataViews } returns MutableStateFlow(
            DataViewInfoList(dataViews = mapOf(1 to DataViewInfo(id = 1, name = "Sample View")), ordering = listOf(1))
        )
        every { viewModel.dataView(1) } returns MutableStateFlow(
            DataView(
                id = 1,
                type = ViewType.CHART,
                records = listOf(RecordSelection(StepsRecord::class)),
                chartSettings = ChartSettings()
            )
        )
        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DataViewView(
                        viewModel = viewModel,
                        page = 0,
                        healthDataModel = model,
                        permissionsViewModel = permissionsViewModel
                    )
                }
            }
        }
        composeRule.onNodeWithTag("data_view_configuration_header").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Add metric"))
        composeRule.onAllNodesWithText("Add metric")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Select a metric to add").assertExists()
        val probesWhenOpened = gateway.probes.get()

        // Each change recomposes DataViewView, and with it the open dialog.
        repeat(3) {
            grantedPermissions.value = if (it % 2 == 0) emptySet() else setOf(PermissionsViewModel.HISTORY_PERMISSION)
            composeRule.waitForIdle()
        }

        assertEquals(probesWhenOpened, gateway.probes.get())
    }
}

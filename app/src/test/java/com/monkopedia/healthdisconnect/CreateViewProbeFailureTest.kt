package com.monkopedia.healthdisconnect

import android.app.Application
import android.health.connect.HealthConnectException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.ui.CreateViewView
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #123: Create View said "No health data found" when the metric-availability probe failed —
 * the failing type was silently dropped — and an empty probe result was cached until the process
 * restarted, so data that arrived later (a permission grant, a sync) never showed up.
 */
@RunWith(AndroidJUnit4::class)
class CreateViewProbeFailureTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Steps either has data, has none, or its probe throws [stepsFailure]; every other type is empty. */
    private class StepsGateway : HealthConnectGateway {
        @Volatile var stepsHaveData = false

        @Volatile var stepsFailure: (() -> Exception)? = null

        override suspend fun hasRecordsForType(cls: KClass<out Record>, now: Instant, pageSize: Int): Boolean {
            if (cls != StepsRecord::class) return false
            stepsFailure?.let { throw it() }
            return stepsHaveData
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

    private val gateway = StepsGateway()
    private val model = HealthDataModel(
        app = ApplicationProvider.getApplicationContext<Application>(),
        autoRefreshMetrics = false,
        healthConnectGateway = gateway
    )

    private fun setCreateView(shown: () -> Boolean = { true }) {
        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                if (shown()) {
                    CreateViewView(viewModel = mockk(relaxed = true), healthDataModel = model)
                }
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aFailedProbeNamesTheTypeAndItsErrorKindInsteadOfNoData() {
        gateway.stepsFailure = { IllegalArgumentException("startTime must be before endTime") }
        setCreateView()

        waitForText(READ_FAILED, substring = true)
        composeRule.onNodeWithText("$STEPS (IllegalArgumentException)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("startTime", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(NO_DATA, substring = true).assertDoesNotExist()
    }

    @Test
    fun anExhaustedReadQuotaIsReportedAsSuch() {
        gateway.stepsFailure = {
            val rateLimit = mockk<HealthConnectException> {
                every { errorCode } returns HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED
            }
            IllegalStateException(rateLimit)
        }
        setCreateView()

        waitForText(RATE_LIMITED, substring = true)
        composeRule.onNodeWithText("could not be checked: $STEPS.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(READ_FAILED, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(NO_DATA, substring = true).assertDoesNotExist()
    }

    /** A read permission the user did not grant is ordinary; it is not reported as a failure. */
    @Test
    fun aMissingPermissionStillReadsAsNoData() {
        gateway.stepsFailure = { SecurityException("Caller doesn't have permission") }
        setCreateView()

        waitForText(NO_DATA, substring = true)
        composeRule.onNodeWithText(READ_FAILED, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(RETRY).assertDoesNotExist()
    }

    @Test
    fun retryReprobesAndShowsTheTypeOnceItCanBeRead() {
        gateway.stepsFailure = { IllegalArgumentException() }
        setCreateView()
        waitForText(RETRY)

        gateway.stepsFailure = null
        gateway.stepsHaveData = true
        composeRule.onNodeWithText(RETRY).performClick()

        waitForText(STEPS)
        composeRule.onNodeWithText(READ_FAILED, substring = true).assertDoesNotExist()
    }

    /** Re-entering Create View re-probes an empty cached result instead of serving it forever. */
    @Test
    fun reenteringCreateViewReprobesAnEmptyResult() {
        var shown by mutableStateOf(true)
        setCreateView { shown }
        waitForText(NO_DATA, substring = true)

        gateway.stepsHaveData = true
        shown = false
        composeRule.waitForIdle()
        shown = true

        waitForText(STEPS)
    }

    @Test
    fun anEmptyCachedResultIsReprobedOnTheNextCollect() = runBlocking {
        assertEquals(emptyList<KClass<out Record>>(), withTimeout(5_000) { model.collectMetricsWithData().first() })

        gateway.stepsHaveData = true

        // Re-collect until published: a collect that lands while the first probe is still winding
        // down is skipped by design, which is not what this test is about.
        val metrics = withTimeout(5_000) {
            var latest = model.collectMetricsWithData().first()
            while (latest.isEmpty()) {
                delay(10)
                latest = model.collectMetricsWithData().first()
            }
            latest
        }
        assertEquals(listOf(StepsRecord::class), metrics)
    }

    private companion object {
        val STEPS = PermissionsViewModel.recordLabel(StepsRecord::class)
        const val NO_DATA = "No health data found"
        const val READ_FAILED = "could not be read from Health Connect"
        const val RATE_LIMITED = "temporarily limiting how often this app can read"
        const val RETRY = "Try again"
    }
}

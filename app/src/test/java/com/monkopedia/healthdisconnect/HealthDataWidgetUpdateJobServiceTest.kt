package com.monkopedia.healthdisconnect

import android.app.job.JobParameters
import android.os.PersistableBundle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HealthDataWidgetUpdateJobServiceTest {

    @After
    fun tearDown() {
        unmockkObject(HealthDataWidgetUpdater)
    }

    @Test
    fun `onStartJob with invalid widget id finishes immediately`() = runBlocking {
        mockkObject(HealthDataWidgetUpdater)
        coEvery { HealthDataWidgetUpdater.updateWidget(any(), any(), any()) } returns Unit
        val service = Robolectric.buildService(HealthDataWidgetUpdateJobService::class.java)
            .create()
            .get()

        val started = service.onStartJob(jobParameters())

        assertFalse(started)
        assertTrue(shadowOf(service).isJobFinished)
        assertFalse(shadowOf(service).isRescheduleNeeded)
        coVerify(exactly = 0) { HealthDataWidgetUpdater.updateWidget(any(), any(), any()) }
    }

    @Test
    fun `onStartJob with valid widget id runs update and finishes`() = runBlocking {
        val widgetId = 44
        mockkObject(HealthDataWidgetUpdater)
        coEvery { HealthDataWidgetUpdater.updateWidget(any(), any(), any()) } returns Unit
        val service = Robolectric.buildService(HealthDataWidgetUpdateJobService::class.java)
            .create()
            .get()

        val started = service.onStartJob(jobParameters(widgetId))

        assertTrue(started)
        waitForCondition { shadowOf(service).isJobFinished }
        assertFalse(shadowOf(service).isRescheduleNeeded)
        coVerify(exactly = 1) { HealthDataWidgetUpdater.updateWidget(any(), widgetId, any()) }
    }

    @Test
    fun `onStopJob requests reschedule`() {
        val service = Robolectric.buildService(HealthDataWidgetUpdateJobService::class.java)
            .create()
            .get()

        val shouldReschedule = service.onStopJob(jobParameters(10))

        assertTrue(shouldReschedule)
    }

    @Test
    fun `onDestroy cancels running update work`() = runBlocking {
        val widgetId = 77
        mockkObject(HealthDataWidgetUpdater)
        coEvery { HealthDataWidgetUpdater.updateWidget(any(), any(), any()) } coAnswers {
            delay(10_000)
        }
        val service = Robolectric.buildService(HealthDataWidgetUpdateJobService::class.java)
            .create()
            .get()

        assertTrue(service.onStartJob(jobParameters(widgetId)))
        service.onDestroy()

        waitForCondition { shadowOf(service).isJobFinished }
        assertFalse(shadowOf(service).isRescheduleNeeded)
    }

    private fun jobParameters(widgetId: Int = -1): JobParameters {
        val bundle = PersistableBundle().apply {
            if (widgetId >= 0) {
                putInt(HealthDataWidgetContract.EXTRA_WIDGET_ID, widgetId)
            }
        }
        return mockk<JobParameters>(relaxed = true).also { params ->
            io.mockk.every { params.extras } returns bundle
        }
    }

    private fun waitForCondition(timeoutMs: Long = 2_500L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Condition was not met within ${timeoutMs}ms")
            }
            Thread.sleep(20)
        }
    }
}

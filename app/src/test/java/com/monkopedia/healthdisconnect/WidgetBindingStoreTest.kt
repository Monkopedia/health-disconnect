package com.monkopedia.healthdisconnect

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetBindingStoreTest {
    private lateinit var app: Application

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        val existing = app.widgetBindingsSnapshot().keys.toIntArray()
        app.unbindWidgets(existing)
        app.clearPendingWidgetRequests()
    }

    @Test
    fun bindWidgetToView_persistsSnapshotAndLookup() = runBlocking {
        app.bindWidgetToView(appWidgetId = 101, viewId = 7)

        val snapshot = app.widgetBindingsSnapshot()
        assertEquals(7, snapshot[101])
        assertEquals(7, app.widgetViewId(101))
    }

    @Test
    fun unbindWidgets_removesMappings() = runBlocking {
        app.bindWidgetToView(appWidgetId = 201, viewId = 4)
        app.bindWidgetToView(appWidgetId = 202, viewId = 4)
        app.bindWidgetToView(appWidgetId = 203, viewId = 5)

        app.unbindWidgets(intArrayOf(201, 203))

        val snapshot = app.widgetBindingsSnapshot()
        assertFalse(snapshot.containsKey(201))
        assertFalse(snapshot.containsKey(203))
        assertEquals(4, snapshot[202])
    }

    @Test
    fun hasWidgetForView_reflectsBoundWidgets() = runBlocking {
        app.bindWidgetToView(appWidgetId = 301, viewId = 11)

        assertTrue(app.hasWidgetForView(11))
        assertFalse(app.hasWidgetForView(12))
    }

    @Test
    fun pendingWidgetRequests_queueAndConsumeMatching() = runBlocking {
        app.enqueuePendingWidgetRequest(viewId = 1, updateWindowName = "HOURS_12")
        app.enqueuePendingWidgetRequest(viewId = 2, updateWindowName = "HOURS_6")
        assertEquals(2, app.pendingWidgetRequestCount())

        val removed = app.consumeMatchingPendingWidgetRequest(
            viewId = 2,
            updateWindowName = "HOURS_6"
        )
        assertTrue(removed)
        assertEquals(1, app.pendingWidgetRequestCount())

        val next = app.consumePendingWidgetRequest()
        assertEquals(1, next?.viewId)
        assertEquals("HOURS_12", next?.updateWindowName)
        assertEquals(0, app.pendingWidgetRequestCount())
    }

    /**
     * Regression for #85: the pin-success callback used to `return` on invalid extras without
     * touching the queue. The entry it was supposed to consume then stayed queued forever, and
     * because `HealthDataWidgetProvider.onUpdate` pops the *head* of that queue for any unbound
     * widget id, the orphan would later be handed to a widget the user never configured for it.
     * A callback that cannot be honoured must still retire its queue entry.
     */
    @Test
    fun pinSuccessCallback_withInvalidWidgetId_doesNotOrphanPendingRequest() = runBlocking {
        app.enqueuePendingWidgetRequest(viewId = 42, updateWindowName = "HOURS_12")
        assertEquals(1, app.pendingWidgetRequestCount())

        WidgetPinSuccessReceiver().onReceive(
            app,
            pinSuccessIntent(
                appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                viewId = 42,
                updateWindowName = "HOURS_12"
            )
        )

        awaitPendingWidgetRequestCount(0)
    }

    /**
     * Regression for #85, unmatchable variant: when the callback's view id is the one that is
     * garbage there is nothing to match on, so the receiver has to fall back to retiring the head
     * of the queue rather than leaving it to be mis-applied later.
     */
    @Test
    fun pinSuccessCallback_withInvalidViewId_doesNotOrphanPendingRequest() = runBlocking {
        app.enqueuePendingWidgetRequest(viewId = 42, updateWindowName = "HOURS_12")
        assertEquals(1, app.pendingWidgetRequestCount())

        WidgetPinSuccessReceiver().onReceive(
            app,
            pinSuccessIntent(appWidgetId = 909, viewId = -1, updateWindowName = null)
        )

        awaitPendingWidgetRequestCount(0)
    }

    @Test
    fun pinSuccessCallback_withInvalidExtras_leavesUnrelatedEntriesQueued() = runBlocking {
        app.enqueuePendingWidgetRequest(viewId = 42, updateWindowName = "HOURS_12")
        app.enqueuePendingWidgetRequest(viewId = 43, updateWindowName = "HOURS_6")

        WidgetPinSuccessReceiver().onReceive(
            app,
            pinSuccessIntent(
                appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                viewId = 42,
                updateWindowName = "HOURS_12"
            )
        )

        awaitPendingWidgetRequestCount(1)
        val remaining = app.consumePendingWidgetRequest()
        assertEquals(43, remaining?.viewId)
        assertEquals("HOURS_6", remaining?.updateWindowName)
    }

    /**
     * Part of #85: `clearPendingWidgetRequests()` used to have zero callers, which makes it
     * indistinguishable from having no bulk cleanup at all. It is now wired to `onDisabled`, the
     * point at which the last widget instance goes away and nothing is left that could ever
     * legitimately claim a queued entry.
     */
    @Test
    fun providerOnDisabled_drainsThePendingQueue() = runBlocking {
        app.enqueuePendingWidgetRequest(viewId = 1, updateWindowName = "HOURS_12")
        app.enqueuePendingWidgetRequest(viewId = 2, updateWindowName = null)
        assertEquals(2, app.pendingWidgetRequestCount())

        HealthDataWidgetProvider().onDisabled(app)

        awaitPendingWidgetRequestCount(0)
    }

    private fun pinSuccessIntent(
        appWidgetId: Int,
        viewId: Int,
        updateWindowName: String?
    ): Intent {
        return Intent(HealthDataWidgetContract.ACTION_WIDGET_PIN_SUCCESS).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(HealthDataWidgetContract.EXTRA_PRESELECT_VIEW_ID, viewId)
            updateWindowName?.let {
                putExtra(HealthDataWidgetContract.EXTRA_WIDGET_UPDATE_WINDOW, it)
            }
        }
    }

    /**
     * The receiver hands its work to a background coroutine via `goAsync()`, so the queue is
     * drained asynchronously; poll rather than asserting straight away.
     */
    private fun awaitPendingWidgetRequestCount(expected: Int, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var observed = -1
        while (System.currentTimeMillis() < deadline) {
            observed = runBlocking { app.pendingWidgetRequestCount() }
            if (observed == expected) return
            Thread.sleep(20)
        }
        fail(
            "Expected pendingWidgetRequestCount to reach $expected within ${timeoutMs}ms " +
                "but it was $observed"
        )
    }
}

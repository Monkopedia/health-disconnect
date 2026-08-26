package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A fixed wall-clock instant. Every TTL assertion is expressed relative to it, so nothing here
 * depends on how long the test takes to run.
 */
private const val NOW_MILLIS = 1_700_000_000_000L

/**
 * The payload shape written by every build before `enqueuedAtMillis` existed: queued requests with
 * no timestamp at all. Decoded, not constructed — an entry with a `null` field built in Kotlin
 * would not prove that a real on-disk payload takes the same path.
 *
 * `widgetToView` is the positive control. If this JSON stopped parsing, `widgetBindingsStateOrDefault`
 * would swallow the failure and hand back an empty state, and every "the queued entry is refused"
 * assertion below would pass for the wrong reason. Asserting the binding survives proves the
 * payload really was decoded.
 */
private const val LEGACY_PAYLOAD_WITHOUT_TIMESTAMPS =
    """{"widgetToView":{"901":42},"pendingRequests":[{"viewId":42,"updateWindowName":"HOURS_12"}]}"""

@RunWith(RobolectricTestRunner::class)
class WidgetBindingStoreTest {
    private lateinit var app: Application

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        app.widgetBindingsDataStore.edit { prefs -> prefs.remove(widgetBindingsJson) }
        Unit
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
     * Pins the ruled TTL itself. Every other assertion here is expressed relative to
     * [PENDING_WIDGET_REQUEST_TTL_MILLIS], so the suite would stay green if the constant were
     * widened — which is exactly the regression the owner's ruling forbids: "do not 'be safe' by
     * lengthening it."
     *
     * Widening buys legitimate pins nothing. `res/xml/health_graph_widget_info.xml` sets
     * `android:updatePeriodMillis="0"`, so no periodic update is scheduled and the legitimate path
     * (accept the pin, widget created, `onUpdate` consumes the entry) completes in seconds. A longer
     * window only lengthens the period in which an unrelated widget from the launcher's picker can
     * pick up a stale entry, so this is a deliberate guard rather than a redundant restatement.
     */
    @Test
    fun pendingWidgetRequestTtl_isTheRuledFiveMinutes() {
        assertEquals(300_000L, PENDING_WIDGET_REQUEST_TTL_MILLIS)
    }

    @Test
    fun consumePendingWidgetRequest_returnsEntryJustInsideTtl() = runBlocking {
        app.enqueuePendingWidgetRequest(
            viewId = 5,
            updateWindowName = "HOURS_12",
            nowMillis = NOW_MILLIS - PENDING_WIDGET_REQUEST_TTL_MILLIS + 1
        )

        val consumed = app.consumePendingWidgetRequest(nowMillis = NOW_MILLIS)

        assertEquals(5, consumed?.viewId)
        assertEquals("HOURS_12", consumed?.updateWindowName)
        assertFalse(queuedRequestsOnDisk())
    }

    @Test
    fun consumePendingWidgetRequest_refusesAndDropsEntryAtTtl() = runBlocking {
        app.enqueuePendingWidgetRequest(
            viewId = 5,
            updateWindowName = "HOURS_12",
            nowMillis = NOW_MILLIS - PENDING_WIDGET_REQUEST_TTL_MILLIS
        )
        assertTrue(queuedRequestsOnDisk())

        assertNull(app.consumePendingWidgetRequest(nowMillis = NOW_MILLIS))

        assertFalse(queuedRequestsOnDisk())
    }

    @Test
    fun consumePendingWidgetRequest_dropsExpiredHeadAndReturnsLiveEntry() = runBlocking {
        app.enqueuePendingWidgetRequest(
            viewId = 5,
            updateWindowName = "HOURS_12",
            nowMillis = NOW_MILLIS - PENDING_WIDGET_REQUEST_TTL_MILLIS
        )
        app.enqueuePendingWidgetRequest(
            viewId = 6,
            updateWindowName = "HOURS_6",
            nowMillis = NOW_MILLIS - 1_000
        )

        val consumed = app.consumePendingWidgetRequest(nowMillis = NOW_MILLIS)

        assertEquals(6, consumed?.viewId)
        assertEquals(0, app.pendingWidgetRequestCount(nowMillis = NOW_MILLIS))
        assertFalse(queuedRequestsOnDisk())
    }

    @Test
    fun consumeMatchingPendingWidgetRequest_removesEntryJustInsideTtl() = runBlocking {
        app.enqueuePendingWidgetRequest(
            viewId = 5,
            updateWindowName = "HOURS_12",
            nowMillis = NOW_MILLIS - PENDING_WIDGET_REQUEST_TTL_MILLIS + 1
        )

        val removed = app.consumeMatchingPendingWidgetRequest(
            viewId = 5,
            updateWindowName = "HOURS_12",
            nowMillis = NOW_MILLIS
        )

        assertTrue(removed)
        assertFalse(queuedRequestsOnDisk())
    }

    @Test
    fun consumeMatchingPendingWidgetRequest_refusesAndDropsEntryAtTtl() = runBlocking {
        app.enqueuePendingWidgetRequest(
            viewId = 5,
            updateWindowName = "HOURS_12",
            nowMillis = NOW_MILLIS - PENDING_WIDGET_REQUEST_TTL_MILLIS
        )
        assertTrue(queuedRequestsOnDisk())

        val removed = app.consumeMatchingPendingWidgetRequest(
            viewId = 5,
            updateWindowName = "HOURS_12",
            nowMillis = NOW_MILLIS
        )

        assertFalse(removed)
        assertFalse(queuedRequestsOnDisk())
    }

    @Test
    fun consumePendingWidgetRequest_refusesLegacyEntryWithoutTimestamp() = runBlocking {
        writeLegacyPayload()

        assertNull(app.consumePendingWidgetRequest(nowMillis = NOW_MILLIS))

        assertFalse(queuedRequestsOnDisk())
        assertEquals(42, app.widgetBindingsSnapshot()[901])
    }

    @Test
    fun consumeMatchingPendingWidgetRequest_refusesLegacyEntryWithoutTimestamp() = runBlocking {
        writeLegacyPayload()

        val removed = app.consumeMatchingPendingWidgetRequest(
            viewId = 42,
            updateWindowName = "HOURS_12",
            nowMillis = NOW_MILLIS
        )

        assertFalse(removed)
        assertFalse(queuedRequestsOnDisk())
        assertEquals(42, app.widgetBindingsSnapshot()[901])
    }

    @Test
    fun pendingWidgetRequestCount_countsOnlyLiveEntries() = runBlocking {
        app.enqueuePendingWidgetRequest(
            viewId = 5,
            updateWindowName = "HOURS_12",
            nowMillis = NOW_MILLIS - PENDING_WIDGET_REQUEST_TTL_MILLIS
        )
        app.enqueuePendingWidgetRequest(
            viewId = 6,
            updateWindowName = "HOURS_6",
            nowMillis = NOW_MILLIS - 1_000
        )

        assertEquals(1, app.pendingWidgetRequestCount(nowMillis = NOW_MILLIS))
    }

    /**
     * Writes the pre-timestamp payload straight into the store, so the entries under test reach the
     * consume paths through the real decode rather than through [enqueuePendingWidgetRequest].
     */
    private suspend fun writeLegacyPayload() {
        app.widgetBindingsDataStore.edit { prefs ->
            prefs[widgetBindingsJson] = LEGACY_PAYLOAD_WITHOUT_TIMESTAMPS
        }
        assertTrue(queuedRequestsOnDisk())
        assertEquals(42, app.widgetBindingsSnapshot()[901])
    }

    /**
     * Whether the persisted payload still carries any queued request. The codec does not encode
     * defaults, so an emptied queue drops the property entirely.
     */
    private suspend fun queuedRequestsOnDisk(): Boolean {
        val raw = app.widgetBindingsDataStore.data.first()[widgetBindingsJson] ?: return false
        return raw.contains("pendingRequests")
    }
}

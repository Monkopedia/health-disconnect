package com.monkopedia.healthdisconnect.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive window list keyed to bucket size (see [BucketSize.windows]) both declutters the
 * window menu and caps the resolved point count, so it needs to stay in lockstep with the agreed
 * bucket→window mapping and snap sensibly when a bucket change invalidates the current window.
 */
class BucketWindowsTest {

    @Test
    fun `windows match the agreed per-bucket mapping`() {
        assertEquals(
            listOf(TimeWindow.HOURS_1, TimeWindow.HOURS_3, TimeWindow.HOURS_6, TimeWindow.HOURS_24),
            BucketSize.MINUTE.windows()
        )
        assertEquals(
            listOf(TimeWindow.HOURS_24, TimeWindow.DAYS_7, TimeWindow.DAYS_30),
            BucketSize.HOUR.windows()
        )
        assertEquals(
            listOf(TimeWindow.DAYS_7, TimeWindow.DAYS_30, TimeWindow.DAYS_90, TimeWindow.YEAR_1, TimeWindow.ALL),
            BucketSize.DAY.windows()
        )
        assertEquals(
            listOf(TimeWindow.DAYS_90, TimeWindow.YEAR_1, TimeWindow.ALL),
            BucketSize.WEEK.windows()
        )
        assertEquals(
            listOf(TimeWindow.YEAR_1, TimeWindow.ALL),
            BucketSize.MONTH.windows()
        )
    }

    @Test
    fun `intraday flag is set only for sub-day windows`() {
        val intraday = TimeWindow.entries.filter { it.isIntraday }.toSet()
        assertEquals(
            setOf(TimeWindow.HOURS_1, TimeWindow.HOURS_3, TimeWindow.HOURS_6, TimeWindow.HOURS_24),
            intraday
        )
    }

    @Test
    fun `nearestWindow keeps the current window when it is already valid`() {
        assertEquals(TimeWindow.DAYS_30, BucketSize.DAY.nearestWindow(TimeWindow.DAYS_30))
        assertEquals(TimeWindow.HOURS_6, BucketSize.MINUTE.nearestWindow(TimeWindow.HOURS_6))
    }

    @Test
    fun `nearestWindow snaps an invalid window to the closest valid one`() {
        // Day → Minute: a coarse DAYS_90 has no minute equivalent; snap to the closest (HOURS_24).
        assertEquals(TimeWindow.HOURS_24, BucketSize.MINUTE.nearestWindow(TimeWindow.DAYS_90))
        // Minute → Day: an intraday HOURS_1 snaps up to the shortest daily window (DAYS_7).
        assertEquals(TimeWindow.DAYS_7, BucketSize.DAY.nearestWindow(TimeWindow.HOURS_1))
        // Hour keeps a mid span: DAYS_90 (not in Hour's list) snaps to the nearest DAYS_30.
        assertEquals(TimeWindow.DAYS_30, BucketSize.HOUR.nearestWindow(TimeWindow.DAYS_90))
    }

    @Test
    fun `requiresHistoryPermission is set only for windows past 30 days`() {
        val gated = TimeWindow.entries.filter { it.requiresHistoryPermission }.toSet()
        assertEquals(
            setOf(TimeWindow.DAYS_90, TimeWindow.YEAR_1, TimeWindow.ALL),
            gated
        )
    }

    @Test
    fun `without history permission all sub-30-day windows stay available`() {
        // Hour bucket keeps the intraday HOURS_24 plus Days 7/30, dropping only the long windows.
        assertEquals(
            listOf(TimeWindow.HOURS_24, TimeWindow.DAYS_7, TimeWindow.DAYS_30),
            availableTimeWindows(BucketSize.HOUR, hasHistoryPermission = false)
        )
        // Minute bucket keeps every intraday span — none of them need history.
        assertEquals(
            listOf(TimeWindow.HOURS_1, TimeWindow.HOURS_3, TimeWindow.HOURS_6, TimeWindow.HOURS_24),
            availableTimeWindows(BucketSize.MINUTE, hasHistoryPermission = false)
        )
        // Day bucket keeps Days 7/30 but gates Days 90 / Year / All.
        assertEquals(
            listOf(TimeWindow.DAYS_7, TimeWindow.DAYS_30),
            availableTimeWindows(BucketSize.DAY, hasHistoryPermission = false)
        )
    }

    @Test
    fun `without history permission the long windows are excluded`() {
        listOf(BucketSize.MINUTE, BucketSize.HOUR, BucketSize.DAY).forEach { bucket ->
            val available = availableTimeWindows(bucket, hasHistoryPermission = false)
            assertTrue(
                "bucket $bucket must not offer history-gated windows without permission",
                available.none { it.requiresHistoryPermission }
            )
        }
    }

    @Test
    fun `with history permission the long windows are included for each bucket`() {
        BucketSize.entries.forEach { bucket ->
            assertEquals(
                "bucket $bucket should offer its full window list with history permission",
                bucket.windows(),
                availableTimeWindows(bucket, hasHistoryPermission = true)
            )
        }
    }

    @Test
    fun `week and month buckets fall back to a non-empty menu without history permission`() {
        // Both buckets only list >30-day windows, so the filtered list would be empty; the fallback
        // surfaces Days 7/30 (the range the no-permission clamp snaps to) instead of an empty menu.
        assertEquals(
            listOf(TimeWindow.DAYS_7, TimeWindow.DAYS_30),
            availableTimeWindows(BucketSize.WEEK, hasHistoryPermission = false)
        )
        assertEquals(
            listOf(TimeWindow.DAYS_7, TimeWindow.DAYS_30),
            availableTimeWindows(BucketSize.MONTH, hasHistoryPermission = false)
        )
    }

    @Test
    fun `every window is intraday-consistent with its buckets`() {
        // A bucket that offers intraday windows must be sub-day (Minute/Hour) and vice-versa: no
        // day-or-coarser bucket should surface an intraday span.
        BucketSize.entries.forEach { bucket ->
            val offersIntraday = bucket.windows().any { it.isIntraday }
            val isSubDay = bucket == BucketSize.MINUTE || bucket == BucketSize.HOUR ||
                bucket.windows().all { it.isIntraday }
            assertTrue(
                "bucket $bucket intraday windows must be a sub-day bucket",
                !offersIntraday || isSubDay
            )
        }
    }
}

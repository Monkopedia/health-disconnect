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

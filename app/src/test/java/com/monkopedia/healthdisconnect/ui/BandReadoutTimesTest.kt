package com.monkopedia.healthdisconnect.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.HealthDataModel.BandExtremeTimes
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #137: a selected band bucket shows when its max and min were recorded, read from Health
 * Connect on demand. These pin the read budget (one read per settled selection, none while
 * scrubbing), that a late read never lands on a different selection, and the failure note.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BandReadoutTimesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 2, 15)
    private val clock = Clock.fixed(today.atStartOfDay(zone).toInstant(), zone)
    private val days = (0..7).map { today.minusDays(7L - it) }

    // Eight daily buckets across a 7-day window: slot k sits at exactly k/7 of the plot width.
    private val series = HealthDataModel.MetricSeries(
        label = "Heart rate",
        unit = "bpm",
        points = days.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 70.0 + i) },
        bandMin = days.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 50.0 + i) },
        bandMax = days.mapIndexed { i, day -> HealthDataModel.MetricPoint(day, 130.0 + i) },
        aggregation = AggregationMode.MIN_MAX_AVG
    )

    private val reads = mutableListOf<Pair<Instant, Instant>>()
    private var selection by mutableStateOf<Int?>(null)
    private val openedRanges = mutableListOf<ChartRange>()

    private fun dayStart(slot: Int): Instant = days[slot].atStartOfDay(zone).toInstant()

    /** The max at 4:05 PM and the min at 3:12 AM of whichever day is read. */
    private fun timesFor(start: Instant) = listOf(
        BandExtremeTimes(maxAt = start.plus(Duration.ofMinutes(16 * 60 + 5)), minAt = start.plus(Duration.ofMinutes(3 * 60 + 12)))
    )

    private fun setChart(lookup: ExtremeTimesLookup = { _, start, end -> reads += start to end; timesFor(start) }) {
        val state = ExtremeTimesState(lookup)
        composeRule.setContent {
            CompositionLocalProvider(LocalClock provides clock) {
                HealthDisconnectTheme(dynamicColor = false) {
                    Surface {
                        MetricOverTimeChart(
                            seriesList = listOf(series),
                            settings = ChartSettings(aggregation = AggregationMode.MIN_MAX_AVG, timeWindow = TimeWindow.DAYS_7),
                            selectedSlot = selection,
                            onSelectedSlotChange = { selection = it },
                            extremeTimes = state,
                            onOpenRange = { openedRanges += it }
                        )
                    }
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
    }

    private fun TouchInjectionScope.slotX(slot: Int): Float {
        val pad = 8.dp.toPx()
        return pad + (width - 2 * pad) * slot / 7f
    }


    private fun plot() = composeRule.onNodeWithTag("data_view_chart_plot")

    private fun text(value: String) = composeRule.onNodeWithText(value, useUnmergedTree = true)

    private fun tap(slot: Int) = plot().performTouchInput { click(Offset(slotX(slot), centerY)) }

    /**
     * Runs [millis] of frames on the test clock. The clock only moves when told to, so "before the
     * selection settles" and "after" are exact rather than however long the animations ran.
     */
    private fun advance(millis: Long) {
        // Idling first applies the input's state change, so the selection lands before time moves.
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    @Test
    fun aSettledSelectionReadsItsBucketOnceAndShowsTheValuesFirst() {
        setChart()
        tap(3)
        advance(SELECTION_SETTLE_MS - 100)
        // The values are there at once; the read waits for the selection to settle.
        text("133 bpm").assertExists()
        assertEquals(emptyList<Pair<Instant, Instant>>(), reads)

        advance(200)
        assertEquals(listOf(dayStart(3) to dayStart(4)), reads)
        text("4:05 PM").assertIsDisplayed()
        text("3:12 AM").assertIsDisplayed()

        // Back to the same bucket later: it was read already, so it is not read again.
        composeRule.onNodeWithTag("chart_readout_clear").performClick()
        advance(SELECTION_SETTLE_MS)
        tap(3)
        advance(SELECTION_SETTLE_MS + 100)
        assertEquals(1, reads.size)
        text("4:05 PM").assertIsDisplayed()
    }

    @Test
    fun scrubbingAcrossBucketsReadsNothingUntilTheSelectionSettles() {
        setChart()
        tap(1)
        advance(SELECTION_SETTLE_MS + 100)
        assertEquals(listOf(dayStart(1) to dayStart(2)), reads)

        // A scrub moves the selection every few frames. It is driven here as the drag detector
        // drives it (ChartReadoutInteractionTest covers the gesture itself), so the time each
        // bucket is held is exact.
        (2..6).forEach { slot ->
            composeRule.runOnUiThread { selection = slot }
            advance(80)
            text("${130 + slot} bpm").assertExists()
        }
        assertEquals(1, reads.size)

        advance(SELECTION_SETTLE_MS + 100)
        assertEquals(listOf(dayStart(1) to dayStart(2), dayStart(6) to dayStart(7)), reads)
    }

    @Test
    fun aTimeReadForAnEarlierSelectionNeverShowsOnTheNewOne() {
        // A read already in flight in Health Connect cannot be interrupted: it finishes even after
        // the selection has moved on, and its result must not be shown for the new selection.
        val pending = mutableMapOf<Instant, Continuation<List<BandExtremeTimes?>>>()
        setChart { _, start, _ -> suspendCoroutine { pending[start] = it } }

        tap(2)
        advance(SELECTION_SETTLE_MS + 100)
        tap(5)
        advance(SELECTION_SETTLE_MS + 100)
        assertEquals(setOf(dayStart(2), dayStart(5)), pending.keys)

        // Slot 2's read lands, with its max at 1:05 AM, while slot 5 is shown.
        val earlier = timesFor(dayStart(2)).map { it.copy(maxAt = it.maxAt.minus(Duration.ofHours(15))) }
        composeRule.runOnUiThread { pending.getValue(dayStart(2)).resume(earlier) }
        advance(100)
        text("135 bpm").assertExists()
        text("1:05 AM").assertDoesNotExist()
        text("3:12 AM").assertDoesNotExist()

        composeRule.runOnUiThread { pending.getValue(dayStart(5)).resume(timesFor(dayStart(5))) }
        advance(300)
        text("4:05 PM").assertIsDisplayed()
    }

    @Test
    fun aFailedReadSaysTheTimeIsUnavailable() {
        setChart { _, start, end -> reads += start to end; listOf(null) }

        tap(4)
        advance(SELECTION_SETTLE_MS + 300)

        assertEquals(1, reads.size)
        text("Time unavailable").assertIsDisplayed()
        text("134 bpm").assertIsDisplayed()
    }

    @Test
    fun theZoomLinkOpensTheSelectedDayHourByHour() {
        setChart()
        tap(3)
        advance(300)
        val weekday = days[3].dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        text("Show $weekday hour by hour").assertIsDisplayed()

        composeRule.onNodeWithTag("chart_readout_zoom").performClick()
        advance(100)

        assertEquals(listOf(ChartRange(dayStart(3), dayStart(4), BucketSize.HOUR)), openedRanges)
    }
}

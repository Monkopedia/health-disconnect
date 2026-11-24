package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the chart readout through real touch input, with the chart hosted the way the app hosts
 * it: inside a vertically scrolling list inside the horizontal view pager. The gesture tests are
 * the point — a scrub must not steal the list's vertical scroll, and an idle chart must not steal
 * the pager's swipe.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ChartReadoutInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 2, 15)
    private val clock = Clock.fixed(today.atStartOfDay(zone).toInstant(), zone)

    // Eight daily points across a 7-day window: slot k sits at exactly k/7 of the plot width.
    private val series = HealthDataModel.MetricSeries(
        label = "Heart rate",
        unit = "bpm",
        points = (0..7).map { HealthDataModel.MetricPoint(today.minusDays(7L - it), 100.0 + it) }
    )

    private var selection by mutableStateOf<Int?>(null)
    private var openEntriesClicks = 0
    private lateinit var pagerState: PagerState
    private lateinit var listState: LazyListState

    private fun setChart() {
        composeRule.setContent {
            CompositionLocalProvider(LocalClock provides clock) {
                HealthDisconnectTheme(dynamicColor = false) {
                    Surface {
                        pagerState = rememberPagerState { 2 }
                        val pageListState = rememberLazyListState()
                        listState = pageListState
                        HorizontalPager(state = pagerState) { page ->
                            LazyColumn(
                                state = if (page == 0) pageListState else rememberLazyListState(),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (page == 0) {
                                    item {
                                        MetricOverTimeChart(
                                            seriesList = listOf(series),
                                            settings = ChartSettings(timeWindow = TimeWindow.DAYS_7),
                                            selectedSlot = selection,
                                            onSelectedSlotChange = { selection = it },
                                            onOpenEntries = { openEntriesClicks++ }
                                        )
                                    }
                                }
                                items(40) { Text("Row $it", Modifier.height(48.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** X of [slot] inside the plot node: the canvas insets its plot by 8dp on each side. */
    private fun TouchInjectionScope.slotX(slot: Int): Float {
        val pad = 8.dp.toPx()
        return pad + (width - 2 * pad) * slot / 7f
    }

    private fun plot() = composeRule.onNodeWithTag("data_view_chart_plot")

    @Test
    fun idlePanelSummarisesTheRange() {
        setChart()
        composeRule.onNodeWithText("Tap the graph for details", useUnmergedTree = true).assertIsDisplayed()
        // The axis also labels 107 bpm; the panel's copy is the range max.
        composeRule.onNode(
            hasText("107 bpm") and hasAnyAncestor(hasTestTag("chart_readout_panel")),
            useUnmergedTree = true
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Max", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("chart_readout_clear").assertDoesNotExist()
    }

    @Test
    fun tapSelectsTheNearestBucketAndShowsIt() {
        setChart()
        plot().performTouchInput { click(Offset(slotX(3) + 4.dp.toPx(), centerY)) }
        composeRule.runOnIdle { assertEquals(3, selection) }
        composeRule.onNodeWithText("103 bpm", useUnmergedTree = true).assertIsDisplayed()
        val heading = formatBucketLabel(today.minusDays(4).atStartOfDay(zone).toInstant(), series.bucketSize, false, zone)
        composeRule.onNodeWithText(heading, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Tap the graph for details", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun clearButtonReturnsToTheSummary() {
        setChart()
        plot().performTouchInput { click(Offset(slotX(5), centerY)) }
        composeRule.onNodeWithTag("chart_readout_clear").performClick()
        composeRule.runOnIdle { assertNull(selection) }
        composeRule.onNodeWithText("Tap the graph for details", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun tappingTheSelectedBucketAgainClearsIt() {
        setChart()
        plot().performTouchInput { click(Offset(slotX(2), centerY)) }
        composeRule.runOnIdle { assertEquals(2, selection) }
        plot().performTouchInput { click(Offset(slotX(2), centerY)) }
        composeRule.runOnIdle { assertNull(selection) }
    }

    @Test
    fun viewEntriesOpensTheEntriesList() {
        setChart()
        plot().performTouchInput { click(Offset(slotX(1), centerY)) }
        composeRule.onNodeWithTag("chart_readout_view_entries").performClick()
        composeRule.runOnIdle { assertEquals(1, openEntriesClicks) }
    }

    @Test
    fun dragScrubsTheSelectionWithoutPaging() {
        setChart()
        plot().performTouchInput { click(Offset(slotX(1), centerY)) }
        plot().performTouchInput {
            swipe(start = Offset(slotX(1), centerY), end = Offset(slotX(6), centerY), durationMillis = 400)
        }
        composeRule.runOnIdle {
            assertEquals(6, selection)
            assertEquals(0, pagerState.currentPage)
        }
        plot().performTouchInput {
            swipe(start = Offset(slotX(6), centerY), end = Offset(slotX(4), centerY), durationMillis = 400)
        }
        composeRule.runOnIdle { assertEquals(4, selection) }
    }

    @Test
    fun idleHorizontalSwipeOnTheChartStillPages() {
        setChart()
        plot().performTouchInput { swipeLeft() }
        composeRule.runOnIdle {
            assertEquals(1, pagerState.currentPage)
            assertNull(selection)
        }
    }

    @Test
    fun verticalDragOnTheChartScrollsTheScreenAndKeepsTheSelection() {
        setChart()
        plot().performTouchInput { click(Offset(slotX(2), centerY)) }
        plot().performTouchInput { swipeUp(startY = bottom - 1f, endY = top + 1f) }
        composeRule.runOnIdle {
            assertTrue(listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
            assertEquals(2, selection)
            assertEquals(0, pagerState.currentPage)
        }
    }

    @Test
    fun tapHitTestingHonoursThePlotInsetOnADenseChart() {
        // An hour of minute buckets: slots ~5dp apart, so the 8dp plot inset is worth more than a
        // slot. On the 7-day chart above the spacing would hide an inset bug.
        val now = clock.instant()
        val dense = HealthDataModel.MetricSeries(
            label = "Heart rate",
            unit = "bpm",
            points = (0 until 60).map { HealthDataModel.MetricPoint(now.minusSeconds((59L - it) * 60), 70.0 + it) },
            bucketSize = BucketSize.MINUTE
        )
        val settings = ChartSettings(timeWindow = TimeWindow.HOURS_1, bucketSize = BucketSize.MINUTE)
        val geometry = ChartGeometry.create(listOf(dense), settings, ChartGeometry.XAxisMode.CONTINUOUS_DATE, now, zone)
        composeRule.setContent {
            CompositionLocalProvider(LocalClock provides clock) {
                HealthDisconnectTheme(dynamicColor = false) {
                    Surface {
                        MetricOverTimeChart(
                            seriesList = listOf(dense),
                            settings = settings,
                            selectedSlot = selection,
                            onSelectedSlotChange = { selection = it }
                        )
                    }
                }
            }
        }
        listOf(2, 57).forEach { slot ->
            plot().performTouchInput {
                val pad = 8.dp.toPx()
                click(Offset(pad + (width - 2 * pad) * geometry.slotXFraction(slot), centerY))
            }
            composeRule.runOnIdle { assertEquals(slot, selection) }
        }
    }

    @Test
    fun dataChangingDuringTheFadeOutDoesNotIndexTheNewData() {
        // Regression (#138 review): DataViewView clears the selection in the same frame the series
        // change, while the outgoing selected content is still fading out. That content used to
        // re-read slot 25 against the new 5-point geometry and crash.
        fun daily(n: Int) = HealthDataModel.MetricSeries(
            label = "HR",
            unit = "bpm",
            points = (0 until n).map { HealthDataModel.MetricPoint(today.minusDays((n - 1L) - it), 100.0 + it) }
        )
        var data by mutableStateOf(daily(30))
        selection = 25
        composeRule.setContent {
            CompositionLocalProvider(LocalClock provides clock) {
                HealthDisconnectTheme(dynamicColor = false) {
                    Surface {
                        MetricOverTimeChart(
                            seriesList = listOf(data),
                            settings = ChartSettings(timeWindow = TimeWindow.DAYS_30),
                            selectedSlot = selection,
                            onSelectedSlotChange = { selection = it }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            data = daily(5)
            selection = null
        }
        repeat(20) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithText("Tap the graph for details", useUnmergedTree = true).assertIsDisplayed()
    }
}

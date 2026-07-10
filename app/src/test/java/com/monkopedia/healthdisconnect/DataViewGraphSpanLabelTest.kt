package com.monkopedia.healthdisconnect

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.ui.DataViewView
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DataViewGraphSpanLabelTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * A MONTH-bucket view plots one point per month; the subtitle must count those buckets as
     * months, not as the distinct calendar dates it used to count (which read "N days"). See #50.
     */
    @Test
    fun monthBucketViewLabelsSubtitleInMonths() {
        setViewContent(
            bucketSize = BucketSize.MONTH,
            timeWindow = TimeWindow.YEAR_1,
            bucketStarts = listOf(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1))
        )

        // The graph subtitle reads its bucket granularity: monthly buckets → "months", never the
        // "N days" the old distinct-calendar-date count produced (#50).
        composeRule.onNodeWithText("Graph (", substring = true)
            .assertTextContains("Graph (2 months)")
    }

    private fun setViewContent(
        bucketSize: BucketSize,
        timeWindow: TimeWindow,
        bucketStarts: List<LocalDate>
    ) {
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(
                    fqn = PermissionsViewModel.CLASSES.first().qualifiedName!!,
                    metricSettings = MetricChartSettings(
                        timeWindow = timeWindow,
                        bucketSize = bucketSize
                    )
                )
            ),
            chartSettings = ChartSettings(timeWindow = timeWindow, bucketSize = bucketSize)
        )
        val viewModel = mockk<DataViewAdapterViewModel>(relaxed = true)
        val info = DataViewInfo(id = 1, name = "Sample View")
        val dataViews = MutableStateFlow(DataViewInfoList(dataViews = mapOf(1 to info), ordering = listOf(1)))
        every { viewModel.dataViews } returns dataViews
        every { viewModel.dataView(1) } returns MutableStateFlow(dataView)

        val healthDataModel = mockk<HealthDataModel>(relaxed = true)
        every { healthDataModel.collectRecordCount(any()) } returns flowOf(bucketStarts.size)
        every { healthDataModel.collectAggregatedSeries(any()) } returns flowOf(
            listOf(
                HealthDataModel.MetricSeries(
                    label = "Weight",
                    unit = "lb",
                    points = bucketStarts.mapIndexed { index, date ->
                        HealthDataModel.MetricPoint(
                            date.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                            150.0 + index
                        )
                    }
                )
            )
        )

        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        every { permissionsViewModel.grantedPermissions } returns
            MutableStateFlow(setOf(PermissionsViewModel.HISTORY_PERMISSION))

        composeRule.setContent {
            HealthDisconnectTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DataViewView(
                        viewModel = viewModel,
                        page = 0,
                        healthDataModel = healthDataModel,
                        permissionsViewModel = permissionsViewModel
                    )
                }
            }
        }
    }
}

package com.monkopedia.healthdisconnect

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import com.monkopedia.healthdisconnect.HealthDataModel.ChartLoadIssues
import com.monkopedia.healthdisconnect.HealthDataModel.ReadFailure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #112: a chart whose Health Connect read failed (an exhausted read quota, say) rendered as
 * if there were no data, and one cut short by the raw-record cap looked complete. Each must say so.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DataViewChartReadFailedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anEmptyChartAfterAnExhaustedReadQuotaSaysSoInsteadOfNoData() {
        setViewContent(series = emptyList(), issues = ChartLoadIssues(readFailure = ReadFailure.RATE_LIMITED))

        composeRule.onNodeWithText(RATE_LIMITED, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(NO_DATA_HINT, substring = true).assertDoesNotExist()
    }

    @Test
    fun aPartialChartAfterAFailedReadSaysSo() {
        setViewContent(series = listOf(stepsSeries), issues = ChartLoadIssues(readFailure = ReadFailure.OTHER))

        composeRule.onNodeWithText(READ_FAILED, substring = true).assertIsDisplayed()
    }

    @Test
    fun aChartCutShortByTheRecordCapSaysWhereItStarts() {
        val since = LocalDate.of(2026, 7, 12)
        setViewContent(
            series = listOf(stepsSeries),
            issues = ChartLoadIssues(truncatedSince = since.atStartOfDay(ZoneId.systemDefault()).toInstant())
        )

        val date = since.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        composeRule.onNodeWithText("Showing data since $date", substring = true).assertIsDisplayed()
    }

    private fun setViewContent(series: List<HealthDataModel.MetricSeries>, issues: ChartLoadIssues) {
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(fqn = PermissionsViewModel.CLASSES.first().qualifiedName!!)),
            chartSettings = ChartSettings()
        )
        val viewModel = mockk<DataViewAdapterViewModel>(relaxed = true)
        val info = DataViewInfo(id = 1, name = "Sample View")
        every { viewModel.dataViews } returns
            MutableStateFlow(DataViewInfoList(dataViews = mapOf(1 to info), ordering = listOf(1)))
        every { viewModel.dataView(1) } returns MutableStateFlow(dataView)

        val healthDataModel = mockk<HealthDataModel>(relaxed = true)
        every { healthDataModel.collectRecordCount(any()) } returns flowOf(HealthDataModel.RecordCount(series.size))
        every { healthDataModel.collectAggregatedSeries(any()) } returns flowOf(series)
        every { healthDataModel.collectChartLoadIssues(1) } returns flowOf(issues)

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

    private val stepsSeries = HealthDataModel.MetricSeries(
        label = "Steps",
        unit = "count",
        points = listOf(HealthDataModel.MetricPoint(Instant.parse("2026-10-06T12:00:00Z"), 4321.0))
    )

    private companion object {
        const val RATE_LIMITED = "Health Connect is temporarily limiting"
        const val READ_FAILED = "Some of this data could not be read from Health Connect"
        const val NO_DATA_HINT = "No graphable values"
    }
}

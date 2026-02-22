package com.monkopedia.healthdisconnect

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.semantics.SemanticsActions
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.ui.DataViewView
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DataViewViewEditFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dirtyStateAppearsOnlyAfterUserEdits() {
        val harness = setupHarness()
        setViewContent(
            permissionsViewModel = harness.permissionsViewModel,
            healthDataModel = harness.healthDataModel,
            viewModel = harness.viewModel
        )

        assertNodeDoesNotExist("data_view_save_button")
        assertNodeDoesNotExist("data_view_discard_button")

        openViewConfiguration()
        toggleShowDataPoints()

        composeRule.onNodeWithTag("data_view_save_button").assertIsDisplayed()
        composeRule.onNodeWithTag("data_view_discard_button").assertIsDisplayed()

        composeRule.onNodeWithTag("data_view_discard_button").performClick()
        composeRule.waitForIdle()

        openViewConfiguration()
        composeRule.onNodeWithTag("data_view_show_data_points_checkbox").assertIsOff()
        coVerify(exactly = 0) { harness.viewModel.updateView(any()) }
    }

    @Test
    fun saveChangesCommitsExpectedValues() {
        val harness = setupHarness()
        setViewContent(
            permissionsViewModel = harness.permissionsViewModel,
            healthDataModel = harness.healthDataModel,
            viewModel = harness.viewModel
        )

        openViewConfiguration()
        toggleShowDataPoints()
        composeRule.onNodeWithTag("data_view_save_button").performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) {
            harness.viewModel.updateView(match {
                it.chartSettings.showDataPoints
            })
        }

        openViewConfiguration()
        composeRule.onNodeWithTag("data_view_show_data_points_checkbox").assertIsOn()
    }

    @Test
    fun discardChangesRestoresLastPersistedState() {
        val harness = setupHarness()
        setViewContent(
            permissionsViewModel = harness.permissionsViewModel,
            healthDataModel = harness.healthDataModel,
            viewModel = harness.viewModel
        )

        openViewConfiguration()
        toggleShowDataPoints()
        composeRule.onNodeWithTag("data_view_discard_button").performClick()
        composeRule.waitForIdle()

        openViewConfiguration()
        composeRule.onNodeWithTag("data_view_show_data_points_checkbox").assertIsOff()
        coVerify(exactly = 0) { harness.viewModel.updateView(any()) }
    }

    @Test
    fun timeWindowRestoresAfterHistoryPermissionGranted() {
        val harness = setupHarness(
            initialTimeWindow = TimeWindow.YEAR_1,
            initialPermissions = emptySet()
        )
        setViewContent(
            permissionsViewModel = harness.permissionsViewModel,
            healthDataModel = harness.healthDataModel,
            viewModel = harness.viewModel
        )

        openViewConfiguration()
        composeRule.onNodeWithText("Days 30").assertIsDisplayed()

        composeRule.runOnIdle {
            harness.grantedPermissions.value = setOf(PermissionsViewModel.HISTORY_PERMISSION)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Year 1").assertIsDisplayed()
    }

    @Test
    fun metricMinMaxLegendTogglesPersistOnSave() {
        val harness = setupHarness()
        setViewContent(
            permissionsViewModel = harness.permissionsViewModel,
            healthDataModel = harness.healthDataModel,
            viewModel = harness.viewModel
        )

        openViewConfiguration()
        composeRule.onNodeWithTag("data_view_metric_show_max_checkbox_0", useUnmergedTree = true).assertIsOn()
        composeRule.onNodeWithTag("data_view_metric_show_min_checkbox_0", useUnmergedTree = true).assertIsOff()

        composeRule.onNodeWithTag("data_view_metric_show_max_checkbox_0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("data_view_metric_show_min_checkbox_0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("data_view_metric_show_max_checkbox_0", useUnmergedTree = true).assertIsOff()
        composeRule.onNodeWithTag("data_view_metric_show_min_checkbox_0", useUnmergedTree = true).assertIsOn()
        composeRule.onNodeWithTag("data_view_save_button").assertIsDisplayed()
        composeRule.onNodeWithTag("data_view_save_button").performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) {
            harness.viewModel.updateView(match {
                val settings = it.records.first().metricSettings
                settings != null &&
                    !settings.showMaxLabel &&
                    settings.showMinLabel
            })
        }

        openViewConfiguration()
        composeRule.onNodeWithTag("data_view_metric_show_max_checkbox_0", useUnmergedTree = true).assertIsOff()
        composeRule.onNodeWithTag("data_view_metric_show_min_checkbox_0", useUnmergedTree = true).assertIsOn()
    }

    private fun setupHarness(
        initialTimeWindow: TimeWindow = TimeWindow.DAYS_30,
        initialPermissions: Set<String> = setOf(PermissionsViewModel.HISTORY_PERMISSION)
    ): EditFlowHarness {
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            chartSettings = ChartSettings(timeWindow = initialTimeWindow)
        )
        val viewModel = mockk<DataViewAdapterViewModel>(relaxed = true)
        val info = DataViewInfo(id = 1, name = "Sample View")
        val dataViews = MutableStateFlow(DataViewInfoList(dataViews = mapOf(1 to info), ordering = listOf(1)))
        every { viewModel.dataViews } returns dataViews

        val viewState = MutableStateFlow(dataView)
        every { viewModel.dataView(1) } returns viewState
        coEvery { viewModel.updateView(any()) } answers {
            viewState.value = firstArg()
        }

        val healthDataModel = mockk<HealthDataModel>(relaxed = true)
        every { healthDataModel.collectRecordCount(any(), any()) } returns flowOf(12)
        every { healthDataModel.collectAggregatedSeries(any(), any()) } returns flowOf(emptyList())
        every { healthDataModel.aggregateMetricSeriesList(any(), any()) } returns emptyList()

        val permissionsViewModel = mockk<PermissionsViewModel>(relaxed = true)
        val grantedPermissions = MutableStateFlow(initialPermissions)
        every { permissionsViewModel.grantedPermissions } returns grantedPermissions

        return EditFlowHarness(
            viewModel = viewModel,
            healthDataModel = healthDataModel,
            permissionsViewModel = permissionsViewModel,
            grantedPermissions = grantedPermissions
        )
    }

    private fun setViewContent(
        viewModel: DataViewAdapterViewModel,
        healthDataModel: HealthDataModel,
        permissionsViewModel: PermissionsViewModel
    ) = composeRule.setContent {
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

    private data class EditFlowHarness(
        val viewModel: DataViewAdapterViewModel,
        val healthDataModel: HealthDataModel,
        val permissionsViewModel: PermissionsViewModel,
        val grantedPermissions: MutableStateFlow<Set<String>>
    )

    private fun openViewConfiguration() {
        if (composeRule.onAllNodesWithTag(
                "data_view_show_data_points_checkbox",
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        ) {
            return
        }

        composeRule.onNodeWithTag("data_view_configuration_header").performClick()
        composeRule.waitForIdle()
    }

    private fun toggleShowDataPoints() {
        composeRule.onNodeWithTag("data_view_show_data_points_checkbox").performClick()
        composeRule.waitForIdle()
    }

    private fun assertNodeDoesNotExist(tag: String) {
        val matches = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        assert(matches.isEmpty()) { "Expected no nodes for tag $tag" }
    }
}

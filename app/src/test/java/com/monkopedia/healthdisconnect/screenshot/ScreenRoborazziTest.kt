package com.monkopedia.healthdisconnect.screenshot

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.LazyNavigation
import com.monkopedia.healthdisconnect.LazyNavigationModel
import com.monkopedia.healthdisconnect.NoSdkAvailable
import com.monkopedia.healthdisconnect.PermissionsRationaleScreen
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.RequestPermissions
import com.monkopedia.healthdisconnect.UpdateRequired
import com.monkopedia.healthdisconnect.model.ChartBackgroundStyle
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.SmoothingMode
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.UnitPreference
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.model.YAxisMode
import com.monkopedia.healthdisconnect.ui.CreateViewView
import com.monkopedia.healthdisconnect.ui.DataViewView
import com.monkopedia.healthdisconnect.ui.EntriesRouteScreen
import com.monkopedia.healthdisconnect.ui.HealthDisconnectIntro
import com.monkopedia.healthdisconnect.ui.LoadingScreen
import com.monkopedia.healthdisconnect.ui.SettingsScreen
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.every
import io.mockk.mockk
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class BaseScreenRoborazziTest {
    protected abstract val sizeBucket: String

    @Test
    fun loadingScreen() {
        captureScreen("loading") {
            LoadingScreen()
        }
    }

    @Test
    fun introScreen() {
        captureScreen("intro") {
            HealthDisconnectIntro()
        }
    }

    @Test
    fun noSdkScreen() {
        captureScreen("permissions_no_sdk") {
            NoSdkAvailable()
        }
    }

    @Test
    fun providerUpdateScreen() {
        captureScreen("permissions_update_required") {
            UpdateRequired()
        }
    }

    @Test
    fun requestPermissionsScreen() {
        captureScreen("permissions_request") {
            RequestPermissions()
        }
    }

    @Test
    fun permissionsRationaleActivityScreen() {
        captureScreen("permissions_rationale_activity") {
            PermissionsRationaleScreen()
        }
    }

    @Test
    fun settingsScreen() {
        captureScreen("settings") {
            SettingsScreen()
        }
    }

    @Test
    fun settingsScreenAdvancedExpanded() {
        captureScreen("settings_advanced") {
            SettingsScreen(
                initialDebugExpanded = true,
                previewDebugRows = listOf(
                    "Weight: perm=yes, oldest=2023-01-05 10:00, newest=2026-02-14 09:30",
                    "Blood glucose: perm=yes, oldest=2024-03-01 08:10, newest=2026-02-14 09:35",
                    "Body fat: perm=no, oldest=n/a, newest=n/a"
                )
            )
        }
    }

    @Test
    fun dataViewAdapterLoadingScreen() {
        val viewModel = mockk<DataViewAdapterViewModel>()
        every { viewModel.dataViews } returns MutableStateFlow(null)
        captureScreen("data_view_adapter_loading") {
            com.monkopedia.healthdisconnect.DataViewAdapter(viewModel = viewModel, showSettings = {})
        }
    }

    @Test
    fun dataViewAdapterCreateScreen() {
        val viewModel = mockk<DataViewAdapterViewModel>()
        every { viewModel.dataViews } returns 
            MutableStateFlow(DataViewInfoList(dataViews = emptyMap(), ordering = emptyList()))
        captureScreen("data_view_adapter_create") {
            com.monkopedia.healthdisconnect.DataViewAdapter(viewModel = viewModel, showSettings = {})
        }
    }

    @Test
    fun dataViewAdapterCreateTrailingScreen() {
        val info = DataViewInfo(id = 1, name = "Steps")
        val view = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            alwaysShowEntries = false
        )
        val viewModel = mockk<DataViewAdapterViewModel>()
        every { viewModel.dataViews } returns 
            MutableStateFlow(DataViewInfoList(dataViews = mapOf(1 to info), ordering = listOf(1)))
        every { viewModel.dataView(1) } returns MutableStateFlow(view)
        captureScreen("data_view_adapter_create_trailing") {
            com.monkopedia.healthdisconnect.DataViewAdapter(
                viewModel = viewModel,
                showSettings = {},
                initialPage = 1
            )
        }
    }

    @Test
    fun dataViewAdapterHeaderPagerFirstScreen() {
        val viewModel = mockDataViewAdapterWithViews(
            infos = listOf(
                DataViewInfo(id = 1, name = "Steps"),
                DataViewInfo(id = 2, name = "Weight")
            )
        )
        captureScreen("data_view_adapter_header_first") {
            com.monkopedia.healthdisconnect.DataViewAdapter(
                viewModel = viewModel,
                showSettings = {},
                initialPage = 0
            )
        }
    }

    @Test
    fun dataViewAdapterHeaderPagerSecondScreen() {
        val viewModel = mockDataViewAdapterWithViews(
            infos = listOf(
                DataViewInfo(id = 1, name = "Steps"),
                DataViewInfo(id = 2, name = "Weight")
            )
        )
        captureScreen("data_view_adapter_header_second") {
            com.monkopedia.healthdisconnect.DataViewAdapter(
                viewModel = viewModel,
                showSettings = {},
                initialPage = 1
            )
        }
    }

    @Test
    fun dataViewAdapterHeaderPagerCreateScreen() {
        val viewModel = mockDataViewAdapterWithViews(
            infos = listOf(
                DataViewInfo(id = 1, name = "Steps"),
                DataViewInfo(id = 2, name = "Weight")
            )
        )
        captureScreen("data_view_adapter_header_create") {
            com.monkopedia.healthdisconnect.DataViewAdapter(
                viewModel = viewModel,
                showSettings = {},
                initialPage = 2
            )
        }
    }

    @Test
    fun dataViewAdapterHeaderPagerOffset45Screen() {
        val viewModel = mockDataViewAdapterWithViews(
            infos = listOf(
                DataViewInfo(id = 1, name = "Steps"),
                DataViewInfo(id = 2, name = "Weight")
            )
        )
        captureScreen("data_view_adapter_header_offset_45") {
            com.monkopedia.healthdisconnect.DataViewAdapter(
                viewModel = viewModel,
                showSettings = {},
                initialPage = 0,
                initialPageOffsetFraction = 0.45f
            )
        }
    }

    @Test
    fun dataViewAdapterHeaderPagerOffset65Screen() {
        val viewModel = mockDataViewAdapterWithViews(
            infos = listOf(
                DataViewInfo(id = 1, name = "Steps"),
                DataViewInfo(id = 2, name = "Weight")
            )
        )
        captureScreen("data_view_adapter_header_offset_65") {
            com.monkopedia.healthdisconnect.DataViewAdapter(
                viewModel = viewModel,
                showSettings = {},
                initialPage = 1,
                // Equivalent to being ~65% transitioned away from previous page.
                initialPageOffsetFraction = -0.35f
            )
        }
    }

    @Test
    fun lazyNavigationLoadingScreen() {
        val viewModel = mockLazyNavigationModel(
            isLoading = true,
            isShowingIntro = false
        )
        captureScreen("lazy_navigation_loading") {
            LazyNavigation(viewModel = viewModel)
        }
    }

    @Test
    fun lazyNavigationIntroScreen() {
        val viewModel = mockLazyNavigationModel(
            isLoading = false,
            isShowingIntro = true
        )
        captureScreen("lazy_navigation_intro") {
            LazyNavigation(viewModel = viewModel)
        }
    }

    @Test
    fun createViewLoadingScreen() {
        val viewModel = mockk<DataViewAdapterViewModel>()
        val healthDataModel = mockHealthDataModel()
        every { healthDataModel.collectMetricsWithData(any()) } returns emptyFlow()
        captureScreen("create_view_loading") {
            CreateViewView(viewModel = viewModel, healthDataModel = healthDataModel)
        }
    }

    @Test
    fun createViewOptionsScreen() {
        val viewModel = mockk<DataViewAdapterViewModel>()
        val healthDataModel = mockHealthDataModel()
        every { healthDataModel.collectMetricsWithData(any()) } returns flowOf(PermissionsViewModel.CLASSES.take(6))
        captureScreen("create_view_options") {
            CreateViewView(viewModel = viewModel, healthDataModel = healthDataModel)
        }
    }

    @Test
    fun entriesRouteLoadingScreen() {
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            alwaysShowEntries = false
        )
        val viewModel = mockDataViewAdapterViewModel(
            info = DataViewInfo(id = 1, name = "Weight"),
            view = dataView
        )
        val healthDataModel = mockHealthDataModel()
        every { healthDataModel.collectData(any(), any()) } returns emptyFlow()
        captureScreen("entries_route_loading") {
            EntriesRouteScreen(
                viewId = 1,
                onBack = {},
                viewModel = viewModel,
                healthDataModel = healthDataModel
            )
        }
    }

    @Test
    fun entriesRoutePopulatedScreen() {
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            alwaysShowEntries = false
        )
        val viewModel = mockDataViewAdapterViewModel(
            info = DataViewInfo(id = 1, name = "Weight"),
            view = dataView
        )
        val healthDataModel = mockHealthDataModel()
        val records = listOf(
            fakeWeightRecord(72.4, "2026-02-05T08:30:00Z"),
            fakeWeightRecord(71.8, "2026-02-04T08:30:00Z")
        )
        every { healthDataModel.collectData(any(), any()) } returns flowOf(records)
        captureScreen("entries_route_populated") {
            EntriesRouteScreen(
                viewId = 1,
                onBack = {},
                viewModel = viewModel,
                healthDataModel = healthDataModel
            )
        }
    }

    @Test
    fun entriesRouteDetailsDialogScreen() {
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            alwaysShowEntries = false
        )
        val viewModel = mockDataViewAdapterViewModel(
            info = DataViewInfo(id = 1, name = "Weight"),
            view = dataView
        )
        val healthDataModel = mockHealthDataModel()
        val selectedRecord = fakeWeightRecord(72.4, "2026-02-05T08:30:00Z")
        every { healthDataModel.collectData(any(), any()) } returns flowOf(listOf(selectedRecord))
        captureScreen("entries_route_details_dialog") {
            EntriesRouteScreen(
                viewId = 1,
                onBack = {},
                viewModel = viewModel,
                healthDataModel = healthDataModel,
                initialSelectedEntry = selectedRecord
            )
        }
    }

    @Test
    fun dataViewCollapsedScreen() {
        val viewModel = mockDataViewAdapterViewModel(
            info = DataViewInfo(id = 1, name = "Sample View"),
            view = DataView(
                id = 1,
                type = ViewType.CHART,
                records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
                alwaysShowEntries = false
            )
        )
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            alwaysShowEntries = false
        )
        val healthDataModel = mockHealthDataModel()
        every { healthDataModel.collectData(any(), any()) } returns flowOf(emptyList<Record>())
        every { healthDataModel.collectMetricsWithData(any()) } returns flowOf(PermissionsViewModel.CLASSES.take(4))
        captureScreen("data_view_collapsed") {
            DataViewView(viewModel = viewModel, page = 0, healthDataModel = healthDataModel)
        }
    }

    @Test
    fun dataViewEditingScreen() {
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = emptyList(),
            alwaysShowEntries = true
        )
        val viewModel = mockDataViewAdapterViewModel(
            info = DataViewInfo(id = 1, name = "Edit Me"),
            view = dataView
        )
        val healthDataModel = mockHealthDataModel()
        every { healthDataModel.collectData(any(), any()) } returns flowOf(emptyList<Record>())
        every { healthDataModel.collectMetricsWithData(any()) } returns flowOf(PermissionsViewModel.CLASSES.take(4))
        captureScreen("data_view_editing") {
            DataViewView(viewModel = viewModel, page = 0, healthDataModel = healthDataModel)
        }
    }

    @Test
    fun dataViewMetricGraphScreen() {
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            alwaysShowEntries = false,
            chartSettings = ChartSettings(timeWindow = TimeWindow.DAYS_30)
        )
        val viewModel = mockDataViewAdapterViewModel(
            info = DataViewInfo(id = 1, name = "Steps"),
            view = dataView
        )
        val healthDataModel = mockHealthDataModel()
        val records = emptyList<Record>()
        every { healthDataModel.collectData(any(), any()) } returns flowOf(records)
        every { healthDataModel.collectAggregatedSeries(dataView, any()) } returns flowOf(
            listOf(
                HealthDataModel.MetricSeries(
                    label = "Steps",
                    unit = "count",
                    points = listOf(
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 3400.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 2), 5600.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 3), 6200.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 4), 4800.0)
                    )
                )
            )
        )
        every { healthDataModel.collectRecordCount(dataView, any()) } returns flowOf(4)
        every { healthDataModel.aggregateMetricSeries(dataView, records) } returns
            HealthDataModel.MetricSeries(
                label = "Steps",
                unit = "count",
                points = listOf(
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 3400.0),
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 2), 5600.0),
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 3), 6200.0),
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 4), 4800.0)
                )
            )
        every { healthDataModel.collectMetricsWithData(any()) } returns flowOf(PermissionsViewModel.CLASSES.take(4))
        captureScreen("data_view_metric_graph") {
            DataViewView(viewModel = viewModel, page = 0, healthDataModel = healthDataModel)
        }
    }

    @Test
    fun dataViewMetricGraphBarsGridScreen() {
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
            alwaysShowEntries = false,
            chartSettings = ChartSettings(
                chartType = ChartType.BARS,
                showDataPoints = true,
                yAxisMode = YAxisMode.START_AT_ZERO,
                smoothing = SmoothingMode.MOVING_AVERAGE_3,
                unitPreference = UnitPreference.METRIC,
                backgroundStyle = ChartBackgroundStyle.GRID,
                timeWindow = TimeWindow.DAYS_90
            )
        )
        val viewModel = mockDataViewAdapterViewModel(
            info = DataViewInfo(id = 1, name = "Distance"),
            view = dataView
        )
        val healthDataModel = mockHealthDataModel()
        val records = emptyList<Record>()
        every { healthDataModel.collectData(any(), any()) } returns flowOf(records)
        every { healthDataModel.collectAggregatedSeries(dataView, any()) } returns flowOf(
            listOf(
                HealthDataModel.MetricSeries(
                    label = "Distance",
                    unit = "kilometers",
                    points = listOf(
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 1, 4), 2.2),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 1, 11), 3.4),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 1, 18), 4.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 1, 25), 3.1)
                    )
                )
            )
        )
        every { healthDataModel.collectRecordCount(dataView, any()) } returns flowOf(4)
        every { healthDataModel.aggregateMetricSeries(dataView, records) } returns
            HealthDataModel.MetricSeries(
                label = "Distance",
                unit = "kilometers",
                points = listOf(
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 1, 4), 2.2),
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 1, 11), 3.4),
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 1, 18), 4.0),
                    HealthDataModel.MetricPoint(LocalDate.of(2026, 1, 25), 3.1)
                )
            )
        every { healthDataModel.collectMetricsWithData(any()) } returns flowOf(PermissionsViewModel.CLASSES.take(4))
        captureScreen("data_view_metric_graph_bars_grid") {
            DataViewView(viewModel = viewModel, page = 0, healthDataModel = healthDataModel)
        }
    }

    @Test
    fun dataViewMetricGraphMultiSeriesScreen() {
        val firstMetric = PermissionsViewModel.CLASSES.first()
        val secondMetric = PermissionsViewModel.CLASSES.drop(1).firstOrNull() ?: firstMetric
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(firstMetric),
                RecordSelection(secondMetric)
            ),
            alwaysShowEntries = false,
            chartSettings = ChartSettings(timeWindow = TimeWindow.DAYS_30)
        )
        val viewModel = mockDataViewAdapterViewModel(
            info = DataViewInfo(id = 1, name = "Activity"),
            view = dataView
        )
        val healthDataModel = mockHealthDataModel()
        val records = emptyList<Record>()
        every { healthDataModel.collectData(any(), any()) } returns flowOf(records)
        every { healthDataModel.collectAggregatedSeries(dataView, any()) } returns flowOf(
            listOf(
                HealthDataModel.MetricSeries(
                    label = "Steps",
                    unit = "count",
                    points = listOf(
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 3400.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 2), 5600.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 3), 6200.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 4), 4800.0)
                    )
                ),
                HealthDataModel.MetricSeries(
                    label = "Distance",
                    unit = "kilometers",
                    points = listOf(
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 2.1),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 2), 3.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 3), 3.5),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 4), 2.8)
                    )
                )
            )
        )
        every { healthDataModel.collectRecordCount(dataView, any()) } returns flowOf(8)
        every { healthDataModel.collectMetricsWithData(any()) } returns flowOf(PermissionsViewModel.CLASSES.take(4))
        captureScreen("data_view_metric_graph_multi_series") {
            DataViewView(viewModel = viewModel, page = 0, healthDataModel = healthDataModel)
        }
    }

    @Test
    fun dataViewMetricGraphBarsMultiSeriesScreen() {
        val firstMetric = PermissionsViewModel.CLASSES.first()
        val secondMetric = PermissionsViewModel.CLASSES.drop(1).firstOrNull() ?: firstMetric
        val dataView = DataView(
            id = 1,
            type = ViewType.CHART,
            records = listOf(
                RecordSelection(firstMetric),
                RecordSelection(secondMetric)
            ),
            alwaysShowEntries = false,
            chartSettings = ChartSettings(
                chartType = ChartType.BARS,
                backgroundStyle = ChartBackgroundStyle.GRID,
                yAxisMode = YAxisMode.START_AT_ZERO,
                timeWindow = TimeWindow.DAYS_30
            )
        )
        val viewModel = mockDataViewAdapterViewModel(
            info = DataViewInfo(id = 1, name = "Activity Bars"),
            view = dataView
        )
        val healthDataModel = mockHealthDataModel()
        val records = emptyList<Record>()
        every { healthDataModel.collectData(any(), any()) } returns flowOf(records)
        every { healthDataModel.collectAggregatedSeries(dataView, any()) } returns flowOf(
            listOf(
                HealthDataModel.MetricSeries(
                    label = "Steps",
                    unit = "count",
                    points = listOf(
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 3200.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 2), 5400.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 3), 6100.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 4), 4700.0)
                    )
                ),
                HealthDataModel.MetricSeries(
                    label = "Distance",
                    unit = "kilometers",
                    points = listOf(
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 1), 2.0),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 2), 2.8),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 3), 3.4),
                        HealthDataModel.MetricPoint(LocalDate.of(2026, 2, 4), 2.6)
                    )
                )
            )
        )
        every { healthDataModel.collectRecordCount(dataView, any()) } returns flowOf(8)
        every { healthDataModel.collectMetricsWithData(any()) } returns flowOf(PermissionsViewModel.CLASSES.take(4))
        captureScreen("data_view_metric_graph_bars_multi_series") {
            DataViewView(viewModel = viewModel, page = 0, healthDataModel = healthDataModel)
        }
    }

    private fun captureScreen(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/outputs/roborazzi/screens/${name}_$sizeBucket.png") {
            HealthDisconnectTheme(dynamicColor = false) {
                Surface {
                    content()
                }
            }
        }
    }

    private fun mockDataViewAdapterViewModel(
        info: DataViewInfo,
        view: DataView
    ): DataViewAdapterViewModel {
        val dataViews = DataViewInfoList(
            dataViews = mapOf(info.id to info),
            ordering = listOf(info.id)
        )
        val viewModel = mockk<DataViewAdapterViewModel>()
        every { viewModel.dataViews } returns MutableStateFlow(dataViews)
        every { viewModel.dataView(info.id) } returns MutableStateFlow(view)
        return viewModel
    }

    private fun mockDataViewAdapterWithViews(infos: List<DataViewInfo>): DataViewAdapterViewModel {
        val dataViews = DataViewInfoList(
            dataViews = infos.associateBy { it.id },
            ordering = infos.map { it.id }
        )
        val viewModel = mockk<DataViewAdapterViewModel>()
        every { viewModel.dataViews } returns MutableStateFlow(dataViews)
        infos.forEach { info ->
            val view = DataView(
                id = info.id,
                type = ViewType.CHART,
                records = listOf(RecordSelection(PermissionsViewModel.CLASSES.first())),
                alwaysShowEntries = false
            )
            every { viewModel.dataView(info.id) } returns MutableStateFlow(view)
        }
        return viewModel
    }

    private fun mockLazyNavigationModel(
        isLoading: Boolean,
        isShowingIntro: Boolean
    ): LazyNavigationModel {
        val viewModel = mockk<LazyNavigationModel>()
        every { viewModel.isLoading } returns MutableStateFlow(isLoading)
        every { viewModel.isShowingIntro } returns MutableStateFlow(isShowingIntro)
        return viewModel
    }

    private fun mockHealthDataModel(): HealthDataModel {
        val healthDataModel = mockk<HealthDataModel>()
        every { healthDataModel.collectMetricsWithData(any()) } returns flowOf(PermissionsViewModel.CLASSES.take(4))
        every { healthDataModel.collectData(any(), any()) } returns flowOf(emptyList<Record>())
        every { healthDataModel.collectRecordCount(any(), any()) } returns flowOf(0)
        every { healthDataModel.collectAggregatedSeries(any(), any()) } returns flowOf(emptyList())
        every { healthDataModel.aggregateMetricSeriesList(any(), any()) } returns emptyList()
        every { healthDataModel.aggregateMetricSeries(any(), any()) } returns null
        return healthDataModel
    }

    private fun fakeWeightRecord(kilograms: Double, isoInstant: String): WeightRecord {
        val record = mockk<WeightRecord>()
        every { record.weight } returns Mass.kilograms(kilograms)
        every { record.time } returns java.time.Instant.parse(isoInstant)
        every { record.metadata } returns Metadata.manualEntry()
        return record
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PhoneScreenRoborazziTest : BaseScreenRoborazziTest() {
    override val sizeBucket = "phone"
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h1280dp-xhdpi")
class TabletScreenRoborazziTest : BaseScreenRoborazziTest() {
    override val sizeBucket = "tablet"
}

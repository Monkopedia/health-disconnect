package com.monkopedia.healthdisconnect.screenshot

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.health.connect.client.records.Record
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.LazyNavigation
import com.monkopedia.healthdisconnect.LazyNavigationModel
import com.monkopedia.healthdisconnect.NoSdkAvailable
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
import com.monkopedia.healthdisconnect.ui.HealthDisconnectIntro
import com.monkopedia.healthdisconnect.ui.LoadingScreen
import com.monkopedia.healthdisconnect.ui.SettingsScreen
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
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
    fun settingsScreen() {
        captureScreen("settings") {
            SettingsScreen()
        }
    }

    @Test
    fun dataViewAdapterLoadingScreen() {
        val viewModel = mock(DataViewAdapterViewModel::class.java)
        `when`(viewModel.dataViews).thenReturn(MutableStateFlow(null))
        captureScreen("data_view_adapter_loading") {
            com.monkopedia.healthdisconnect.DataViewAdapter(viewModel = viewModel, showSettings = {})
        }
    }

    @Test
    fun dataViewAdapterCreateScreen() {
        val viewModel = mock(DataViewAdapterViewModel::class.java)
        `when`(viewModel.dataViews).thenReturn(
            MutableStateFlow(DataViewInfoList(dataViews = emptyMap(), ordering = emptyList()))
        )
        captureScreen("data_view_adapter_create") {
            com.monkopedia.healthdisconnect.DataViewAdapter(viewModel = viewModel, showSettings = {})
        }
    }

    @Test
    fun lazyNavigationLoadingScreen() {
        val viewModel = mockLazyNavigationModel(
            showingSettings = false,
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
            showingSettings = false,
            isLoading = false,
            isShowingIntro = true
        )
        captureScreen("lazy_navigation_intro") {
            LazyNavigation(viewModel = viewModel)
        }
    }

    @Test
    fun createViewLoadingScreen() {
        val viewModel = mock(DataViewAdapterViewModel::class.java)
        val healthDataModel = mock(HealthDataModel::class.java)
        `when`(healthDataModel.metricsWithData).thenReturn(MutableStateFlow(null))
        captureScreen("create_view_loading") {
            CreateViewView(viewModel = viewModel, healthDataModel = healthDataModel)
        }
    }

    @Test
    fun createViewOptionsScreen() {
        val viewModel = mock(DataViewAdapterViewModel::class.java)
        val healthDataModel = mock(HealthDataModel::class.java)
        `when`(healthDataModel.metricsWithData)
            .thenReturn(MutableStateFlow(PermissionsViewModel.CLASSES.take(6)))
        captureScreen("create_view_options") {
            CreateViewView(viewModel = viewModel, healthDataModel = healthDataModel)
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
        val healthDataModel = mock(HealthDataModel::class.java)
        `when`(healthDataModel.collectData(dataView)).thenReturn(flowOf(emptyList<Record>()))
        `when`(healthDataModel.metricsWithData)
            .thenReturn(MutableStateFlow(PermissionsViewModel.CLASSES.take(4)))
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
        val healthDataModel = mock(HealthDataModel::class.java)
        `when`(healthDataModel.collectData(dataView)).thenReturn(flowOf(emptyList<Record>()))
        `when`(healthDataModel.metricsWithData)
            .thenReturn(MutableStateFlow(PermissionsViewModel.CLASSES.take(4)))
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
        val healthDataModel = mock(HealthDataModel::class.java)
        val records = emptyList<Record>()
        `when`(healthDataModel.collectData(dataView)).thenReturn(flowOf(records))
        `when`(healthDataModel.aggregateMetricSeriesList(dataView, records)).thenReturn(
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
        `when`(healthDataModel.aggregateMetricSeries(dataView, records)).thenReturn(
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
        `when`(healthDataModel.metricsWithData)
            .thenReturn(MutableStateFlow(PermissionsViewModel.CLASSES.take(4)))
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
        val healthDataModel = mock(HealthDataModel::class.java)
        val records = emptyList<Record>()
        `when`(healthDataModel.collectData(dataView)).thenReturn(flowOf(records))
        `when`(healthDataModel.aggregateMetricSeriesList(dataView, records)).thenReturn(
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
        `when`(healthDataModel.aggregateMetricSeries(dataView, records)).thenReturn(
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
        `when`(healthDataModel.metricsWithData)
            .thenReturn(MutableStateFlow(PermissionsViewModel.CLASSES.take(4)))
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
        val healthDataModel = mock(HealthDataModel::class.java)
        val records = emptyList<Record>()
        `when`(healthDataModel.collectData(dataView)).thenReturn(flowOf(records))
        `when`(healthDataModel.aggregateMetricSeriesList(dataView, records)).thenReturn(
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
        `when`(healthDataModel.metricsWithData)
            .thenReturn(MutableStateFlow(PermissionsViewModel.CLASSES.take(4)))
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
        val healthDataModel = mock(HealthDataModel::class.java)
        val records = emptyList<Record>()
        `when`(healthDataModel.collectData(dataView)).thenReturn(flowOf(records))
        `when`(healthDataModel.aggregateMetricSeriesList(dataView, records)).thenReturn(
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
        `when`(healthDataModel.metricsWithData)
            .thenReturn(MutableStateFlow(PermissionsViewModel.CLASSES.take(4)))
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
        val viewModel = mock(DataViewAdapterViewModel::class.java)
        `when`(viewModel.dataViews).thenReturn(MutableStateFlow(dataViews))
        `when`(viewModel.dataView(info.id)).thenReturn(MutableStateFlow(view))
        return viewModel
    }

    private fun mockLazyNavigationModel(
        showingSettings: Boolean,
        isLoading: Boolean,
        isShowingIntro: Boolean
    ): LazyNavigationModel {
        val viewModel = mock(LazyNavigationModel::class.java)
        `when`(viewModel.isShowingSettings).thenReturn(MutableStateFlow(showingSettings))
        `when`(viewModel.isLoading).thenReturn(MutableStateFlow(isLoading))
        `when`(viewModel.isShowingIntro).thenReturn(MutableStateFlow(isShowingIntro))
        return viewModel
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

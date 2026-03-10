package com.monkopedia.healthdisconnect

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import com.monkopedia.healthdisconnect.LazyNavigationModel.Companion.navigationDataStore
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.MetricChartSettings
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.SmoothingMode
import com.monkopedia.healthdisconnect.model.TimeWindow
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first

object DemoDataSeeder {

    private val seededKey = booleanPreferencesKey("demo_seeded")

    private data class DemoViewSpec(
        val id: Int,
        val name: String,
        val records: List<String>,
        val chartType: ChartType,
        val timeWindow: TimeWindow,
        val smoothing: SmoothingMode = SmoothingMode.OFF,
        val showDataPoints: Boolean = false
    )

    private val views = listOf(
        DemoViewSpec(
            id = 1,
            name = "Weight",
            records = listOf(WeightRecord::class.qualifiedName!!),
            chartType = ChartType.LINE,
            timeWindow = TimeWindow.DAYS_90,
            smoothing = SmoothingMode.MOVING_AVERAGE_3,
            showDataPoints = true
        ),
        DemoViewSpec(
            id = 2,
            name = "Steps",
            records = listOf(StepsRecord::class.qualifiedName!!),
            chartType = ChartType.BARS,
            timeWindow = TimeWindow.DAYS_30
        ),
        DemoViewSpec(
            id = 3,
            name = "Heart Rate",
            records = listOf(HeartRateRecord::class.qualifiedName!!),
            chartType = ChartType.LINE,
            timeWindow = TimeWindow.DAYS_30,
            showDataPoints = true
        ),
        DemoViewSpec(
            id = 4,
            name = "Sleep",
            records = listOf(SleepSessionRecord::class.qualifiedName!!),
            chartType = ChartType.BARS,
            timeWindow = TimeWindow.DAYS_30
        ),
        DemoViewSpec(
            id = 5,
            name = "Body Fat",
            records = listOf(BodyFatRecord::class.qualifiedName!!),
            chartType = ChartType.LINE,
            timeWindow = TimeWindow.DAYS_90,
            smoothing = SmoothingMode.MOVING_AVERAGE_3
        ),
        DemoViewSpec(
            id = 6,
            name = "Weight + Body Fat",
            records = listOf(
                WeightRecord::class.qualifiedName!!,
                BodyFatRecord::class.qualifiedName!!
            ),
            chartType = ChartType.LINE,
            timeWindow = TimeWindow.DAYS_90,
            smoothing = SmoothingMode.MOVING_AVERAGE_3,
            showDataPoints = true
        )
    )

    suspend fun seedIfNeeded(context: Context) {
        val dataStore = context.navigationDataStore
        val alreadySeeded = dataStore.data.first()[seededKey] == true
        if (alreadySeeded) return

        val db = AppDatabase.getInstance(context)
        val dataViewDao = db.dataViewDao()
        val dataViewInfoDao = db.dataViewInfoDao()

        views.forEachIndexed { index, spec ->
            val dataView = DataView(
                id = spec.id,
                type = ViewType.CHART,
                records = spec.records.map { fqn ->
                    RecordSelection(
                        fqn = fqn,
                        metricSettings = MetricChartSettings(),
                        metricKey = null
                    )
                },
                chartSettings = ChartSettings(
                    chartType = spec.chartType,
                    timeWindow = spec.timeWindow,
                    smoothing = spec.smoothing,
                    showDataPoints = spec.showDataPoints
                )
            )
            dataViewDao.insert(encodeDataViewEntity(dataView))
            dataViewInfoDao.insert(
                DataViewInfoEntity(
                    id = spec.id,
                    name = spec.name,
                    ordering = index + 1
                )
            )
        }

        // Mark intro as dismissed and seeding as done
        dataStore.edit {
            it[booleanPreferencesKey("dismissed")] = true
            it[seededKey] = true
        }
    }
}

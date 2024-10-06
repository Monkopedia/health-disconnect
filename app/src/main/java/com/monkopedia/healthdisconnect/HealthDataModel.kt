package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HealthDataModel(app: Application) : AndroidViewModel(app) {
    val healthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }
    val metricsWithData = MutableStateFlow<List<KClass<out Record>>?>(null)

    init {
        viewModelScope.launch {
            val counts = PermissionsViewModel.CLASSES.map {
                async {
                    val records = healthConnectClient.readRecords(
                        ReadRecordsRequest(
                            it,
                            TimeRangeFilter.Companion.before(Instant.now()),
                            pageSize = 1
                        )
                    ).records
                    it to records.isNotEmpty()
                }
            }.awaitAll()
            metricsWithData.value = counts.filter { it.second }.map { it.first }
        }
    }
}

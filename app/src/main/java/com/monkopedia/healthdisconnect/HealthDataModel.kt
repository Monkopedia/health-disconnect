package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
                            TimeRangeFilter.before(Instant.now()),
                            pageSize = 1
                        )
                    ).records
                    it to records.isNotEmpty()
                }
            }.awaitAll()
            metricsWithData.value = counts.filter { it.second }.map { it.first }
        }
    }

    fun collectData(view: DataView): Flow<List<Record>> = flow {
        val typeMap: Map<String, KClass<out Record>> =
            PermissionsViewModel.CLASSES.associateBy { it.qualifiedName ?: "" }
        val selections: List<KClass<out Record>> = view.records.mapNotNull { sel: RecordSelection ->
            typeMap[sel.fqn]
        }
        val now = Instant.now()
        val all = mutableListOf<Record>()
        for (cls in selections) {
            try {
                @Suppress("UNCHECKED_CAST")
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        cls,
                        TimeRangeFilter.between(Instant.EPOCH, now),
                        pageSize = 100
                    )
                )

                @Suppress("UNCHECKED_CAST")
                val records = response.records as List<Record>
                all.addAll(records)
            } catch (_: Throwable) {
                // Ignore errors per type to keep UI responsive
            }
        }
        emit(all)
    }
}

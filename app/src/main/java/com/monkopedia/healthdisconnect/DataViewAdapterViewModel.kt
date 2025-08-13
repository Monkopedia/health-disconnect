package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Context
import androidx.datastore.dataStore
import androidx.health.connect.client.records.Record
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.monkopedia.healthdisconnect.datastore.DataViewListSerializer
import com.monkopedia.healthdisconnect.datastore.DataViewSerializer
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.DataViewInfo
import com.monkopedia.healthdisconnect.model.DataViewInfoList
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DataViewAdapterViewModel(app: Application, private val savedStateHandle: SavedStateHandle) :
    AndroidViewModel(app) {

    private val json = Json
    private val lastDataViews = savedStateHandle.getStateFlow<String?>("lastView", null)
    val dataViews = lastDataViews.map {
        it?.takeIf { it.isNotBlank() }?.let { json.decodeFromString<DataViewInfoList>(it) }
    }
    private val dataInfoStore by lazy {
        context.dataViewInfoDataStore
    }
    private val dataStore by lazy {
        context.dataViewDataStore
    }
    private val flows = MutableStateFlow(mapOf<Int, Flow<DataView>>())

    init {
        viewModelScope.launch {
            dataInfoStore.data.collect { list ->
                savedStateHandle["lastView"] = json.encodeToString(list)
            }
        }
    }

    suspend fun createView(cls: KClass<out Record>) {
        val newId = dataViews.firstOrNull()?.ordering?.maxOrNull()?.plus(1) ?: 1
        dataStore.updateData {
            it.copy(views = it.views + (newId to DataView(newId, cls)))
        }
        dataInfoStore.updateData {
            it.copy(
                dataViews = it.dataViews +
                    (newId to DataViewInfo(newId, PermissionsViewModel.RECORD_NAMES[cls]!!)),
                ordering = it.ordering + newId
            )
        }
    }

    fun dataView(id: Int): Flow<DataView> = flows.updateAndGet {
        it.takeIf { id in it } ?: (it + (id to createDataView(id)))
    }[id]!!

    suspend fun updateView(view: DataView) {
        dataStore.updateData { list ->
            list.copy(views = list.views + (view.id to view))
        }
    }

    private fun createDataView(id: Int): Flow<DataView> = dataStore.data.map { it.views[id]!! }

    companion object {
        val Context.dataViewInfoDataStore by dataStore("dataInfoStore", DataViewListSerializer)
        val Context.dataViewDataStore by dataStore("dataStore", DataViewSerializer)
    }
}

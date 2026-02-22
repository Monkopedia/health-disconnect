package com.monkopedia.healthdisconnect

import android.util.Log
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.room.DataViewEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

private const val CODEC_TAG = "DataViewEntityCodec"

internal fun decodeDataViewEntity(entity: DataViewEntity, json: Json = Json): DataView {
    val records = runCatching {
        json.decodeFromString(
            ListSerializer(RecordSelection.serializer()),
            entity.recordsJson
        )
    }.getOrElse { exception ->
        if (exception is CancellationException) {
            throw exception
        }
        Log.w(CODEC_TAG, "Failed to parse recordsJson for data view ${entity.id}", exception)
        emptyList()
    }
    val settings = runCatching {
        json.decodeFromString(ChartSettings.serializer(), entity.settingsJson)
    }.getOrElse { exception ->
        if (exception is CancellationException) {
            throw exception
        }
        Log.w(CODEC_TAG, "Failed to parse settingsJson for data view ${entity.id}", exception)
        ChartSettings()
    }
    val type = runCatching { ViewType.valueOf(entity.type) }
        .getOrDefault(ViewType.CHART)
    return DataView(
        id = entity.id,
        type = type,
        records = records,
        chartSettings = settings
    )
}

internal fun encodeDataViewEntity(view: DataView, json: Json = Json): DataViewEntity {
    val recordsJson = json.encodeToString(
        ListSerializer(RecordSelection.serializer()),
        view.records
    )
    val settingsJson = json.encodeToString(
        ChartSettings.serializer(),
        view.chartSettings
    )
    return DataViewEntity(
        id = view.id,
        type = view.type.name,
        recordsJson = recordsJson,
        settingsJson = settingsJson
    )
}

package com.monkopedia.healthdisconnect

import android.util.Log
import com.monkopedia.healthdisconnect.model.ChartSettings
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType
import com.monkopedia.healthdisconnect.room.DataViewEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.coroutines.cancellation.CancellationException

private const val CODEC_TAG = "DataViewEntityCodec"

internal fun decodeDataViewEntity(entity: DataViewEntity, json: Json = StorageJson): DataView {
    val records = decodeRecordSelections(entity, json)
    val settings = runCatching {
        json.decodeFromString(ChartSettings.serializer(), entity.settingsJson)
    }.getOrElse { exception ->
        if (exception is CancellationException) {
            throw exception
        }
        // Log the failure TYPE only. A kotlinx.serialization message embeds the offending JSON
        // verbatim, which would put the user's chart configuration into logcat (issue #63).
        Log.w(CODEC_TAG, "Failed to parse settingsJson for view ${entity.id} (${exception.errorLabel()})")
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

/**
 * Decodes the selections one element at a time so a single unreadable entry costs ONE metric
 * instead of the whole view.
 *
 * A list-level `decodeFromString` is all-or-nothing: any element that fails takes the entire list
 * with it, and the caller's fallback is `emptyList()` — i.e. the view renders empty, which is the
 * #56 symptom by a different route. Parsing to a [JsonArray] first and decoding each element
 * independently bounds the blast radius to the element that is actually broken.
 */
private fun decodeRecordSelections(entity: DataViewEntity, json: Json): List<RecordSelection> {
    val elements = runCatching {
        json.parseToJsonElement(entity.recordsJson).jsonArray
    }.getOrElse { exception ->
        if (exception is CancellationException) {
            throw exception
        }
        // Type only — never the message, which would carry the payload into logcat (issue #63).
        Log.w(CODEC_TAG, "Failed to parse recordsJson for view ${entity.id} (${exception.errorLabel()})")
        return emptyList()
    }
    return elements.mapNotNull { element ->
        runCatching {
            json.decodeFromJsonElement(RecordSelection.serializer(), element)
        }.getOrElse { exception ->
            if (exception is CancellationException) {
                throw exception
            }
            Log.w(
                CODEC_TAG,
                "Dropping one unreadable record selection in view ${entity.id} " +
                    "(${exception.errorLabel()}); the view keeps its remaining metrics"
            )
            null
        }
    }.map { selection ->
        // Heal a stale R8-obfuscated fqn from a pre-v1.2.1 build back to the real record-type name
        // (issue #56), so the recovered name persists on the next save rather than only resolving
        // in-memory via classForFqn.
        val recovered = LegacyFqnRecovery.normalize(selection.fqn)
        if (recovered == selection.fqn) selection else selection.copy(fqn = recovered)
    }
}

/** The exception's type, for logs — deliberately excluding the message, which embeds the JSON. */
private fun Throwable.errorLabel(): String = this::class.simpleName ?: "unknown error"

internal fun encodeDataViewEntity(view: DataView, json: Json = StorageJson): DataViewEntity {
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

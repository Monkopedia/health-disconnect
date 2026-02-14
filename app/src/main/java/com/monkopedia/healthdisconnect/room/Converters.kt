package com.monkopedia.healthdisconnect.room

import androidx.room.TypeConverter
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json

    @TypeConverter
    fun fromViewType(value: ViewType?): String? = value?.name

    @TypeConverter
    fun toViewType(value: String?): ViewType? = value?.let { ViewType.valueOf(it) }

    @TypeConverter
    fun fromRecordSelectionList(value: List<RecordSelection>?): String? =
        value?.let { json.encodeToString(ListSerializer(RecordSelection.serializer()), it) }

    @TypeConverter
    fun toRecordSelectionList(value: String?): List<RecordSelection>? =
        value?.let { json.decodeFromString(ListSerializer(RecordSelection.serializer()), it) }
}

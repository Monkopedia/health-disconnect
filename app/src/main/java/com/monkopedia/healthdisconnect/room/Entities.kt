package com.monkopedia.healthdisconnect.room

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.monkopedia.healthdisconnect.model.RecordSelection
import com.monkopedia.healthdisconnect.model.ViewType

@Entity(tableName = "data_views")
data class DataViewEntity(
    @PrimaryKey val id: Int,
    val type: String = ViewType.CHART.name,
    val recordsJson: String = "[]",
    val alwaysShowEntries: Boolean = false
)

@Entity(tableName = "data_view_info")
data class DataViewInfoEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val ordering: Int
)

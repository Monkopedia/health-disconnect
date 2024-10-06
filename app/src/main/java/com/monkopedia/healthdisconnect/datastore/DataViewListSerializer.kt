package com.monkopedia.healthdisconnect.datastore

import com.monkopedia.healthdisconnect.model.DataViewInfoList
import com.monkopedia.healthdisconnect.model.DataViewList

data object DataViewListSerializer :
    JsonSerializer<DataViewInfoList>(
        DataViewInfoList.serializer(),
        DataViewInfoList(emptyMap(), emptyList())
    )

data object DataViewSerializer :
    JsonSerializer<DataViewList>(DataViewList.serializer(), DataViewList(emptyMap()))

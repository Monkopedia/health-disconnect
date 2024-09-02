package com.monkopedia.healthdisconnect.datastore

import com.monkopedia.healthdisconnect.model.DataViewInfoList

data object DataViewListSerializer :
    JsonSerializer<DataViewInfoList>(DataViewInfoList.serializer(), DataViewInfoList(emptyMap()))


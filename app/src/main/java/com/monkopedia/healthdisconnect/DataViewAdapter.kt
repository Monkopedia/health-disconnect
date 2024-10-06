package com.monkopedia.healthdisconnect

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.ui.CreateViewView
import com.monkopedia.healthdisconnect.ui.DataViewView
import com.monkopedia.healthdisconnect.ui.LoadingScreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DataViewAdapter(viewModel: DataViewAdapterViewModel = viewModel(), showSettings: () -> Unit) {
    val listInfo by viewModel.dataViews.collectAsState(initial = null)
    val pagerState = rememberPagerState(pageCount = { listInfo?.ordering?.size ?: 0 })

    if (listInfo == null) {
        LoadingScreen()
        return
    }
    if (listInfo?.ordering?.size == 0) {
        CreateViewView(viewModel)
    }
    HorizontalPager(state = pagerState) { page ->
        DataViewView(viewModel, page)
    }
}

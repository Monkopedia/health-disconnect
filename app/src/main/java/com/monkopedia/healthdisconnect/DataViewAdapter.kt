package com.monkopedia.healthdisconnect

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.ui.CreateViewView
import com.monkopedia.healthdisconnect.ui.DataViewView
import com.monkopedia.healthdisconnect.ui.LoadingScreen
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DataViewAdapter(
    viewModel: DataViewAdapterViewModel = viewModel(),
    initialPage: Int = 0,
    initialPageOffsetFraction: Float = 0f,
    showSettings: () -> Unit,
    onOpenEntries: (Int) -> Unit = {}
) {
    val listInfo by viewModel.dataViews.collectAsState(initial = null)
    val viewCount = listInfo?.ordering?.size ?: 0
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        initialPageOffsetFraction = initialPageOffsetFraction,
        pageCount = { viewCount + 1 }
    )

    if (listInfo == null) {
        LoadingScreen()
        return
    }
    val scope = rememberCoroutineScope()
    val createViewTitle = stringResource(R.string.create_view_title)
    val headerOverlayHeight = 56.dp
    var headerRowWidthPx by remember { mutableStateOf(0f) }
    var headerClickJob by remember { mutableStateOf<Job?>(null) }
    var renameTargetId by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }
    val fallbackSlotWidthPx = with(LocalDensity.current) { 120.dp.toPx() }
    fun headerTitle(page: Int): String {
        return if (page < viewCount) {
            val id = listInfo!!.ordering[page]
            listInfo!!.dataViews[id]?.name ?: ""
        } else {
            createViewTitle
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 19.dp, top = headerOverlayHeight, end = 19.dp),
            pageSpacing = 8.dp,
            beyondViewportPageCount = 1
        ) { page ->
            val signedOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val clampedOffset = abs(signedOffset).coerceIn(0f, 1f)
            val easedOffset = clampedOffset.toDouble().pow(0.7).toFloat()
            val scale = 1f - (0.06f * easedOffset)
            val alpha = 1f - (0.55f * easedOffset)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        translationY = -28f * easedOffset
                        rotationY = signedOffset * 7f
                        cameraDistance = 24f * density
                    }
            ) {
                if (page < viewCount) {
                    DataViewView(
                        viewModel = viewModel,
                        page = page,
                        showHeader = false,
                        onOpenEntriesRequested = onOpenEntries
                    )
                } else {
                    CreateViewView(viewModel, showHeader = false)
                }
            }
        }
        val currentPage = pagerState.currentPage
        val headerPages = listOf(currentPage - 1, currentPage, currentPage + 1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 13.dp, start = 11.dp, end = 11.dp)
                .onSizeChanged { headerRowWidthPx = it.width.toFloat() }
        ) {
            val slotWidthPx = if (headerRowWidthPx > 0f) headerRowWidthPx / 3f else fallbackSlotWidthPx
            headerPages.forEach { page ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (page in 0..viewCount) {
                        val signedOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        val clampedOffset = abs(signedOffset).coerceIn(0f, 1f)
                        val rowShiftX = -pagerState.currentPageOffsetFraction * slotWidthPx
                        Text(
                            text = headerTitle(page),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .clickable {
                                    if (page == pagerState.currentPage && page < viewCount) {
                                        val id = listInfo!!.ordering[page]
                                        renameTargetId = id
                                        renameText = listInfo!!.dataViews[id]?.name.orEmpty()
                                    } else if (page != pagerState.currentPage) {
                                        headerClickJob?.cancel()
                                        headerClickJob = scope.launch {
                                            pagerState.animateScrollToPage(page)
                                        }
                                    }
                                }
                                .graphicsLayer {
                                    translationX = rowShiftX
                                    alpha = 1f - (0.60f * clampedOffset)
                                    val scale = 1f - (0.08f * clampedOffset)
                                    scaleX = scale
                                    scaleY = scale
                                }
                        )
                    }
                }
            }
        }
    }
    if (renameTargetId != null) {
        AlertDialog(
            onDismissRequest = { renameTargetId = null },
            title = { Text(stringResource(R.string.rename_view_title)) },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = renameTargetId
                        if (target != null && renameText.isNotBlank()) {
                            scope.launch {
                                viewModel.renameView(target, renameText)
                            }
                        }
                        renameTargetId = null
                    }
                ) {
                    Text(stringResource(R.string.data_view_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetId = null }) {
                    Text(stringResource(R.string.data_view_cancel))
                }
            }
        )
    }
}

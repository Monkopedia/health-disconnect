package com.monkopedia.healthdisconnect

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.ui.CreateViewView
import com.monkopedia.healthdisconnect.ui.DataViewView
import com.monkopedia.healthdisconnect.ui.LoadingScreen
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DataViewAdapter(
    viewModel: DataViewAdapterViewModel = koinViewModel(),
    healthDataModel: HealthDataModel = koinViewModel(),
    permissionsViewModel: PermissionsViewModel = koinViewModel(),
    initialPage: Int = 0,
    initialViewId: Int? = null,
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
    var handledInitialViewId by remember { mutableStateOf<Int?>(null) }

    if (listInfo == null) {
        LoadingScreen()
        return
    }
    LaunchedEffect(initialViewId, listInfo?.ordering) {
        val targetViewId = initialViewId
        if (targetViewId == null) {
            handledInitialViewId = null
            return@LaunchedEffect
        }
        if (handledInitialViewId == targetViewId) return@LaunchedEffect
        val targetPage = listInfo!!.ordering.indexOf(targetViewId)
        if (targetPage < 0) {
            handledInitialViewId = targetViewId
            return@LaunchedEffect
        }
        val boundedPage = targetPage.coerceIn(0, pagerState.pageCount - 1)
        if (boundedPage != pagerState.currentPage) {
            pagerState.scrollToPage(boundedPage)
        }
        handledInitialViewId = targetViewId
    }
    val scope = rememberCoroutineScope()
    val createViewTitle = stringResource(R.string.create_view_title)
    val headerOverlayHeight = 56.dp
    val pageLiftPx = with(LocalDensity.current) { 180.dp.toPx() }
    var headerRowWidthPx by remember { mutableStateOf(0f) }
    var headerClickJob by remember { mutableStateOf<Job?>(null) }
    var renameTargetId by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }
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
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { clip = false },
            contentPadding = PaddingValues(start = 19.dp, top = headerOverlayHeight, end = 19.dp),
            pageSpacing = 8.dp,
            beyondViewportPageCount = 1
        ) { page ->
            val signedOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val clampedOffset = abs(signedOffset).coerceIn(0f, 1f)
            val easedOffset = clampedOffset.toDouble().pow(0.7).toFloat()
            val scale = 1f - (0.06f * easedOffset)
            val alpha = (1f - (0.95f * clampedOffset)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        translationY = -pageLiftPx * easedOffset
                        rotationY = signedOffset * 7f
                        cameraDistance = 24f * density
                    }
            ) {
                if (page < viewCount) {
                    DataViewView(
                        viewModel = viewModel,
                        healthDataModel = healthDataModel,
                        permissionsViewModel = permissionsViewModel,
                        page = page,
                        showHeader = false,
                        onOpenEntriesRequested = onOpenEntries
                    )
                } else {
                    CreateViewView(viewModel, healthDataModel, showHeader = false)
                }
            }
        }
        val titles = buildList {
            listInfo!!.ordering.forEach { id ->
                add(listInfo!!.dataViews[id]?.name ?: "")
            }
            add(createViewTitle)
        }
        DataViewHeaderStrip(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 13.dp, start = 11.dp, end = 11.dp)
            .height(40.dp),
            titles = titles,
            currentPage = pagerState.currentPage,
            currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
            onPageClick = { page ->
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
        )
        IconButton(
            onClick = showSettings,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 4.dp, top = 8.dp)
                .testTag("data_view_settings_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.settings_title)
            )
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

@Composable
internal fun DataViewHeaderStrip(
    titles: List<String>,
    currentPage: Int,
    currentPageOffsetFraction: Float,
    onPageClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var headerRowWidthPx by remember { mutableStateOf(0f) }
    val fallbackSlotWidthPx = with(LocalDensity.current) { 120.dp.toPx() }
    Box(
        modifier = modifier
            .onSizeChanged { headerRowWidthPx = it.width.toFloat() }
            .graphicsLayer {
                clip = false
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Fade only at the very screen edges.
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.08f to Color.White,
                            0.92f to Color.White,
                            1.00f to Color.Transparent
                        ),
                        startX = 0f,
                        endX = size.width
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        val slotWidthPx = if (headerRowWidthPx > 0f) headerRowWidthPx / 3f else fallbackSlotWidthPx
        val headerSpacingFactor = 1.28f
        for (page in titles.indices) {
            val pageIndex = page
            val title = titles[pageIndex]
            key(page) {
                DataViewHeaderItem(
                    modifier = Modifier.align(Alignment.Center),
                    title = title,
                    page = pageIndex,
                    currentPage = currentPage,
                    currentPageOffsetFraction = currentPageOffsetFraction,
                    slotWidthPx = slotWidthPx,
                    headerSpacingFactor = headerSpacingFactor,
                    onHeaderClick = { onPageClick(pageIndex) }
                )
            }
        }
    }
}

@Composable
private fun DataViewHeaderItem(
    modifier: Modifier,
    title: String,
    page: Int,
    currentPage: Int,
    currentPageOffsetFraction: Float,
    slotWidthPx: Float,
    headerSpacingFactor: Float,
    onHeaderClick: () -> Unit
) {
    Box(
        modifier = modifier
            .widthIn(max = 170.dp)
            .padding(horizontal = 4.dp)
            .offset {
                IntOffset(
                    x = (-(currentPage - page + currentPageOffsetFraction) * slotWidthPx * headerSpacingFactor).roundToInt(),
                    y = 0
                )
            }
            .graphicsLayer {
                val signedOffset = (currentPage - page) + currentPageOffsetFraction
                val clampedOffset = abs(signedOffset).coerceIn(0f, 1f)
                alpha = (1f - (0.62f * clampedOffset)).coerceIn(0f, 1f)
                val scale = 1f - (0.22f * clampedOffset)
                scaleX = scale
                scaleY = scale
            }
            .clickable { onHeaderClick() }
            .testTag("header_title_$page")
            .height(40.dp)
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .align(Alignment.Center)
        )
    }
}

package com.monkopedia.healthdisconnect.ui

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.monkopedia.healthdisconnect.DataViewAdapterViewModel
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.zoomedTo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.emptyFlow
import org.koin.androidx.compose.koinViewModel

/**
 * The navigation routes for one range of a view: its chart ([ChartRangeScreen]) and its entries
 * ([EntriesRouteScreen], issue #139). Everything the screen needs is in the route, so any screen
 * can open a view's chart or entries for a range.
 */
internal object ChartRangeRoute {
    const val PATTERN = "chart/{viewId}/{start}/{end}/{bucket}"
    const val ENTRIES_PATTERN = "entries/{viewId}/{start}/{end}/{bucket}"

    val arguments = listOf(
        navArgument("viewId") { type = NavType.IntType },
        navArgument("start") { type = NavType.LongType },
        navArgument("end") { type = NavType.LongType },
        navArgument("bucket") { type = NavType.StringType }
    )

    fun of(viewId: Int, range: ChartRange): String = "chart/$viewId/${range.path}"

    fun entriesOf(viewId: Int, range: ChartRange): String = "entries/$viewId/${range.path}"

    private val ChartRange.path: String
        get() = "${start.toEpochMilli()}/${end.toEpochMilli()}/${bucketSize.name}"

    fun viewId(arguments: Bundle): Int = arguments.getInt("viewId")

    /** The route's range, or null when it names no bucket size or does not end after it starts. */
    fun range(arguments: Bundle): ChartRange? {
        val bucketSize = BucketSize.entries.firstOrNull { it.name == arguments.getString("bucket") }
            ?: return null
        val start = Instant.ofEpochMilli(arguments.getLong("start"))
        val end = Instant.ofEpochMilli(arguments.getLong("end"))
        return ChartRange(start, end, bucketSize).takeIf { start < end }
    }
}

/**
 * View [viewId]'s chart over [range] alone, bucketed by the range's bucket (issue #137): the
 * readout's "Show Thu hour by hour". It reads and draws only that range, and leaves the saved view
 * as it is; back returns to where it was opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartRangeScreen(
    viewId: Int,
    range: ChartRange,
    onBack: () -> Unit,
    onOpenRange: (ChartRange) -> Unit,
    viewModel: DataViewAdapterViewModel = koinViewModel(),
    healthDataModel: HealthDataModel = koinViewModel(),
    /** Opens a selected bucket's entries (issue #139). */
    onOpenEntries: ((ChartRange) -> Unit)? = null
) {
    val infos by viewModel.dataViews.collectAsState(initial = null)
    val info = infos?.dataViews?.get(viewId)
    val view by viewModel.dataView(viewId).collectAsState(initial = null)
    if (infos != null && info == null) {
        RouteUnavailableScreen(R.string.route_view_deleted, onBack)
        return
    }
    val seriesFlow = remember(view, range) {
        view?.let { healthDataModel.collectSeriesInRange(it, range) } ?: emptyFlow()
    }
    val loaded by seriesFlow.collectAsState(initial = null)
    var selection by remember(loaded?.series) { mutableStateOf<Int?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = info?.name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = rangeSubtitle(range),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chart_range_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.data_view_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 11.dp, vertical = 8.dp)
                .testTag("chart_range_screen")
        ) {
            val current = loaded
            val zoomed = view?.zoomedTo(range)
            when {
                current == null || zoomed == null -> GraphStatePlaceholder(
                    isLoading = true,
                    message = stringResource(R.string.loading_message),
                    reserveLegendRows = 1
                )
                current.series.isEmpty() -> GraphStatePlaceholder(
                    isLoading = false,
                    message = stringResource(current.issues.readFailure?.messageRes() ?: R.string.data_view_no_graphable),
                    reserveLegendRows = 1
                )
                else -> {
                    ChartLoadIssueNotices(current.issues)
                    MetricOverTimeChart(
                        seriesList = current.series,
                        settings = zoomed.chartSettings,
                        selectedSlot = selection,
                        onSelectedSlotChange = { selection = it },
                        onOpenEntries = onOpenEntries,
                        window = range,
                        onOpenRange = onOpenRange
                    )
                }
            }
        }
    }
}

/** Names [range] and its buckets: `Thu, Feb 12 · Hour by hour`, `February 2026 · Day by day`. */
@Composable
private fun rangeSubtitle(range: ChartRange, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val first = range.start.atZone(zoneId).toLocalDate()
    val last = range.end.atZone(zoneId).minusNanos(1).toLocalDate()
    val span = when {
        first == last -> first.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        first.dayOfMonth == 1 && last == first.withDayOfMonth(first.lengthOfMonth()) ->
            first.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        first.month == last.month ->
            "${first.format(DateTimeFormatter.ofPattern("MMM d"))} – ${last.dayOfMonth}"
        else -> "${first.format(DateTimeFormatter.ofPattern("MMM d"))} – " +
            last.format(DateTimeFormatter.ofPattern("MMM d"))
    }
    return when (range.bucketSize) {
        BucketSize.MINUTE, BucketSize.HOUR -> stringResource(R.string.chart_range_hourly, span)
        else -> stringResource(R.string.chart_range_daily, span)
    }
}

/**
 * Shown in place of a route's screen when its view was deleted or its range cannot be read: says
 * so, and back leaves, rather than a spinner that never ends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteUnavailableScreen(@StringRes message: Int, onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("route_unavailable_back")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.data_view_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .testTag("route_unavailable")
        )
    }
}

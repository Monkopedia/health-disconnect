package com.monkopedia.healthdisconnect.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.monkopedia.healthdisconnect.HealthDataModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.formatExactValueWithUnit
import com.monkopedia.healthdisconnect.model.AggregationMode
import com.monkopedia.healthdisconnect.model.BucketSize
import com.monkopedia.healthdisconnect.model.ChartRange
import com.monkopedia.healthdisconnect.model.ChartType
import com.monkopedia.healthdisconnect.model.bucketEnd
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** One labelled number in a readout row; [caption] is null when the row's own caption says it. */
private data class ReadoutStat(val caption: String?, val value: String)

/**
 * When a selected band bucket's max and min were recorded, as one row shows it: still being read,
 * not known, or the two formatted times.
 */
private sealed interface RowTimes {
    data object Pending : RowTimes
    data object Unavailable : RowTimes
    data class Known(val max: String, val min: String) : RowTimes
}

/**
 * One series' line in the readout: its colour dot, an optional caption, and one or three stats.
 * [times] is set on a selected band row whose max and min can be timed.
 */
private data class ReadoutRow(
    val color: Color,
    val caption: String?,
    val stats: List<ReadoutStat>,
    val times: RowTimes? = null
)

/** The readout's link to the selected bucket drawn on its own: [range] with finer buckets. */
private data class ZoomLink(val label: String, val range: ChartRange)

/**
 * Everything the panel shows, already formatted. The panel animates on this snapshot rather than
 * on the slot index: the outgoing content keeps rendering for the length of the fade, and by then
 * the series may have changed under it (a reload or settings change clears the selection in the
 * same frame), so it must not index live data.
 */
private data class ReadoutContent(
    val heading: String,
    val rows: List<ReadoutRow>,
    val isSelection: Boolean,
    val linksToEntries: Boolean,
    val zoom: ZoomLink? = null
) {
    /**
     * What the panel cross-fades on. Times arriving for the selection shown fill in where they
     * are, without fading the whole panel, so every time state keys the same.
     */
    fun ignoringTimes(): ReadoutContent =
        copy(rows = rows.map { row -> row.copy(times = row.times?.let { RowTimes.Pending }) })
}

/**
 * Reads when a selected band bucket's max and min were recorded: the chart's series hold one value
 * per bucket, not when it was read. One entry per bucket, null where the times are not known.
 */
internal typealias ExtremeTimesLookup = suspend (
    buckets: List<HealthDataModel.BandBucket>,
    start: Instant,
    end: Instant
) -> List<HealthDataModel.BandExtremeTimes?>

/**
 * The band times read so far for one set of series, by bucket start. Keyed by bucket so a read that
 * finishes after the selection has moved on can only fill in its own bucket, never the one now
 * shown, and so going back to a bucket does not read it again.
 */
@Stable
internal class ExtremeTimesState(val lookup: ExtremeTimesLookup) {
    val known = mutableStateMapOf<Instant, List<HealthDataModel.BandExtremeTimes?>>()
}

/** An [ExtremeTimesState] that starts over whenever [seriesList] changes. */
@Composable
internal fun rememberExtremeTimesState(
    seriesList: List<HealthDataModel.MetricSeries>?,
    lookup: ExtremeTimesLookup
): ExtremeTimesState = remember(seriesList) { ExtremeTimesState(lookup) }

/**
 * The panel under the in-app chart. With nothing selected it summarises the visible range (what
 * the legend used to show, a total for summed bars, max/avg/min for a band) and hints that the
 * graph can be tapped; with [selectedSlot] set it shows that bucket's values, one row per series.
 *
 * Idle and selected deliberately share one layout (a heading line over a row per series) so the
 * two are about the same height, and the content cross-fades while the container animates any
 * remaining height difference instead of jumping.
 */
@Composable
internal fun ChartReadoutPanel(
    geometry: ChartGeometry,
    seriesColors: List<Color>,
    selectedSlot: Int?,
    onClear: () -> Unit,
    onOpenEntries: (() -> Unit)?,
    modifier: Modifier = Modifier,
    extremeTimes: ExtremeTimesState? = null,
    onOpenRange: ((ChartRange) -> Unit)? = null
) {
    val bandTimes = selectedBandTimes(geometry, selectedSlot, extremeTimes)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .animateContentSize()
            .testTag("chart_readout_panel")
    ) {
        val compact = maxWidth < COMPACT_PANEL_WIDTH
        val content = ReadoutContent(
            heading = if (selectedSlot == null) {
                stringResource(R.string.chart_readout_hint)
            } else {
                slotHeading(geometry, selectedSlot)
            },
            rows = readoutRows(geometry, seriesColors, selectedSlot, bandTimes),
            isSelection = selectedSlot != null,
            // A band's bucket is many readings, so it links to the bucket drawn on its own instead.
            linksToEntries = onOpenEntries != null && geometry.seriesList.none { it.hasBand },
            zoom = selectedSlot?.takeIf { onOpenRange != null && geometry.seriesList.any { it.hasBand } }
                ?.let { zoomLink(geometry, it) }
        )
        AnimatedContent(
            targetState = content,
            transitionSpec = {
                (fadeIn(tween(READOUT_FADE_MS)) togetherWith fadeOut(tween(READOUT_FADE_MS))).using(null)
            },
            contentKey = { it.ignoringTimes() },
            label = "chart_readout"
        ) { shown ->
            // A band's Max/Average/Min grid needs the panel's full width on a small phone, so its
            // ✕ sits beside the heading (as in the mock) instead of taking a column of its own.
            val clearBesideHeading = shown.isSelection && shown.rows.any { it.stats.size > 1 }
            Box {
                Column {
                    Row(
                        modifier = Modifier.padding(
                            start = 14.dp,
                            end = 4.dp,
                            top = 10.dp,
                            bottom = if (shown.zoom != null) 0.dp else 10.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = shown.heading,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = if (clearBesideHeading) Modifier.padding(end = 40.dp) else Modifier
                            )
                            shown.rows.forEach { ReadoutRowContent(it, compact) }
                        }
                        if (shown.isSelection) {
                            // On a narrow phone the labelled link would squeeze the value into wrapping,
                            // so it shrinks to its arrow there.
                            if (onOpenEntries != null && shown.linksToEntries) {
                                val viewEntries = stringResource(R.string.data_view_view_entries)
                                if (compact) {
                                    IconButton(
                                        onClick = onOpenEntries,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .testTag("chart_readout_view_entries")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                            contentDescription = viewEntries,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                } else {
                                    TextButton(
                                        onClick = onOpenEntries,
                                        modifier = Modifier.testTag("chart_readout_view_entries")
                                    ) {
                                        Text(viewEntries)
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                            contentDescription = null
                                        )
                                    }
                                }
                            }
                            if (!clearBesideHeading) ClearSelectionButton(onClear)
                        }
                    }
                    shown.zoom?.let { zoom ->
                        TextButton(
                            onClick = { onOpenRange?.invoke(zoom.range) },
                            modifier = Modifier
                                .padding(start = 2.dp, bottom = 2.dp)
                                .testTag("chart_readout_zoom")
                        ) {
                            Text(zoom.label)
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null
                            )
                        }
                    }
                }
                if (clearBesideHeading) {
                    ClearSelectionButton(onClear, Modifier.align(Alignment.TopEnd).padding(end = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ClearSelectionButton(onClear: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClear,
        modifier = modifier
            .size(44.dp)
            .testTag("chart_readout_clear")
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.chart_readout_clear),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReadoutRowContent(row: ReadoutRow, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(row.color)
        )
        Spacer(Modifier.width(10.dp))
        val single = row.stats.singleOrNull()
        if (single != null) {
            Text(
                text = single.value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            row.caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        } else {
            Column(Modifier.weight(1f)) {
                row.caption?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Each stat stays on one line so idle and selected keep the same height; a narrow
                // panel steps the values down a size so "141 bpm" fits a third of a small phone.
                Row(horizontalArrangement = Arrangement.spacedBy(STAT_SPACING)) {
                    row.stats.forEach { stat ->
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stat.caption.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stat.value,
                                style = if (compact) {
                                    MaterialTheme.typography.titleSmall
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Always one line tall, pending or not, so the panel keeps its height as the times
                // arrive; only the line itself fades.
                row.times?.let { times ->
                    Crossfade(times, animationSpec = tween(READOUT_FADE_MS), label = "chart_readout_times") {
                        ExtremeTimesLine(it, columns = row.stats.size)
                    }
                }
            }
        }
    }
}

/** The times under a band row's Max and Min columns (its first and last), or why there are none. */
@Composable
private fun ExtremeTimesLine(times: RowTimes, columns: Int) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (times == RowTimes.Unavailable) {
        Text(
            text = stringResource(R.string.chart_readout_time_unavailable),
            style = style,
            color = color,
            maxLines = 1,
            modifier = Modifier.testTag("chart_readout_time_unavailable")
        )
        return
    }
    val maxAt = (times as? RowTimes.Known)?.let { stringResource(R.string.chart_readout_max_at, it.max) }
    val minAt = (times as? RowTimes.Known)?.let { stringResource(R.string.chart_readout_min_at, it.min) }
    Row(horizontalArrangement = Arrangement.spacedBy(STAT_SPACING)) {
        repeat(columns) { column ->
            Box(Modifier.weight(1f)) {
                when {
                    column != 0 && column != columns - 1 -> Unit
                    times is RowTimes.Known -> Text(
                        text = if (column == 0) times.max else times.min,
                        style = style,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics {
                            contentDescription = (if (column == 0) maxAt else minAt).orEmpty()
                        }
                    )
                    // A placeholder the size of a time, so the line is already its final height.
                    else -> Text(
                        text = TIME_PLACEHOLDER,
                        style = style,
                        color = Color.Transparent,
                        modifier = Modifier
                            .clearAndSetSemantics {}
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}

/**
 * The times of the band buckets at [slot], by series index, reading them once the selection has
 * settled; null when nothing selected can be timed. Values show at once and times fill in.
 */
@Composable
private fun selectedBandTimes(geometry: ChartGeometry, slot: Int?, state: ExtremeTimesState?): Map<Int, RowTimes>? {
    if (state == null || slot == null) return null
    val buckets = geometry.seriesList.withIndex().mapNotNull { (seriesIndex, series) ->
        if (!series.bandEdgesAreReadings) return@mapNotNull null
        val index = geometry.pointIndexAt(seriesIndex, slot) ?: return@mapNotNull null
        val max = series.bandMax?.get(index)?.value ?: return@mapNotNull null
        val min = series.bandMin?.get(index)?.value ?: return@mapNotNull null
        seriesIndex to HealthDataModel.BandBucket(series, max, min)
    }
    if (buckets.isEmpty()) return null
    val zoneId = ZoneId.systemDefault()
    val start = geometry.slotStart(slot, zoneId)
    LaunchedEffect(state, start) {
        if (start in state.known) return@LaunchedEffect
        // A scrub crosses a bucket in a few frames; moving on cancels this before it reads.
        delay(SELECTION_SETTLE_MS)
        val end = geometry.slotBucket.bucketEnd(start, zoneId)
        state.known[start] = state.lookup(buckets.map { it.second }, start, end)
    }
    // Only this bucket's own result: never one read for a bucket the selection has since left.
    val known = state.known[start]
    val formatter = DateTimeFormatter.ofPattern(timePattern(geometry.slotBucket))
    return buckets.withIndex().associate { (position, entry) ->
        val times = when (known) {
            null -> RowTimes.Pending
            else -> known.getOrNull(position)?.let {
                RowTimes.Known(it.maxAt.atZone(zoneId).format(formatter), it.minAt.atZone(zoneId).format(formatter))
            } ?: RowTimes.Unavailable
        }
        entry.first to times
    }
}

/** A time within a bucket: the clock time, with the day once a bucket spans several. */
private fun timePattern(bucket: BucketSize): String = when (bucket) {
    BucketSize.MINUTE -> "h:mm:ss a"
    BucketSize.HOUR, BucketSize.DAY -> "h:mm a"
    BucketSize.WEEK -> "EEE h:mm a"
    BucketSize.MONTH -> "MMM d, h:mm a"
}

@Composable
private fun zoomLink(geometry: ChartGeometry, slot: Int): ZoomLink? {
    val zoneId = ZoneId.systemDefault()
    val start = geometry.slotStart(slot, zoneId)
    val range = ChartRange.zoomInto(start, geometry.slotBucket, zoneId) ?: return null
    val local = start.atZone(zoneId)
    val label = when (geometry.slotBucket) {
        BucketSize.WEEK -> stringResource(R.string.chart_readout_show_week_daily)
        BucketSize.MONTH ->
            stringResource(R.string.chart_readout_show_month_daily, local.format(DateTimeFormatter.ofPattern("MMMM")))
        else -> stringResource(R.string.chart_readout_show_day_hourly, local.format(DateTimeFormatter.ofPattern("EEE")))
    }
    return ZoomLink(label, range)
}

@Composable
private fun readoutRows(
    geometry: ChartGeometry,
    seriesColors: List<Color>,
    slot: Int?,
    bandTimes: Map<Int, RowTimes>?
): List<ReadoutRow> {
    val multiSeries = geometry.seriesCount > 1
    val max = stringResource(R.string.chart_readout_max)
    val min = stringResource(R.string.chart_readout_min)
    val average = stringResource(R.string.chart_readout_average)
    val total = stringResource(R.string.chart_readout_total)
    val noValue = stringResource(R.string.chart_readout_no_value)
    val seriesCaptionFormat = stringResource(R.string.chart_readout_series_caption)
    val isBars = geometry.settings.chartType == ChartType.BARS
    return geometry.seriesList.flatMapIndexed { seriesIndex, series ->
        val color = seriesColors[seriesIndex % seriesColors.size]
        // A lone series needs no name, and a selected value needs no stat name; with several
        // series each row says whose it is.
        fun row(stat: String?, vararg stats: ReadoutStat, times: RowTimes? = null): ReadoutRow {
            val caption = when {
                !multiSeries -> stat
                stat == null -> series.label
                else -> seriesCaptionFormat.format(series.label, stat)
            }
            return ReadoutRow(color, caption, stats.toList(), times)
        }
        fun format(value: Double?) = value?.let { formatExactValueWithUnit(it, series.unit) } ?: noValue
        val bandMin = series.bandMin
        val bandMax = series.bandMax
        if (slot == null) {
            when {
                // The average of the bucket averages: what the plotted avg line averages to.
                bandMin != null && bandMax != null -> listOf(
                    row(
                        null,
                        ReadoutStat(max, format(bandMax.maxOfOrNull { it.value })),
                        ReadoutStat(average, format(series.points.map { it.value }.average())),
                        ReadoutStat(min, format(bandMin.minOfOrNull { it.value }))
                    )
                )
                // Summing buckets is only meaningful when each bucket is itself a sum (steps,
                // distance); a bar chart of averages falls back to the legend's max/min.
                isBars && series.aggregation == AggregationMode.SUM ->
                    listOf(row(total, ReadoutStat(null, format(series.points.sumOf { it.value }))))
                else -> listOfNotNull(
                    row(max, ReadoutStat(null, format(series.peakValueInWindow))).takeIf { series.showMaxLabel },
                    row(min, ReadoutStat(null, format(series.minValueInWindow))).takeIf { series.showMinLabel }
                )
            }
        } else {
            val index = geometry.pointIndexAt(seriesIndex, slot)
            if (bandMin != null && bandMax != null) {
                listOf(
                    row(
                        null,
                        ReadoutStat(max, format(index?.let { bandMax[it].value })),
                        ReadoutStat(average, format(index?.let { series.points[it].value })),
                        ReadoutStat(min, format(index?.let { bandMin[it].value })),
                        times = bandTimes?.get(seriesIndex)
                    )
                )
            } else {
                listOf(row(null, ReadoutStat(null, format(index?.let { series.points[it].value }))))
            }
        }
    }
}

@Composable
private fun slotHeading(geometry: ChartGeometry, slot: Int): String {
    val bucket = geometry.slotBucket
    val label = formatBucketLabel(
        start = geometry.slotStart(slot),
        bucket = bucket,
        withYear = geometry.sortedDates.first().year != geometry.sortedDates.last().year
    )
    return if (bucket == BucketSize.WEEK) stringResource(R.string.chart_readout_week_of, label) else label
}

/**
 * Names the bucket that starts at [start]: a time for minute/hour buckets, a day, a week's date
 * range (`Jan 12 – 18`, or `Jan 26 – Feb 1` across months), or a month. [withYear] adds the year
 * when the chart spans more than one, matching the axis labels.
 */
internal fun formatBucketLabel(
    start: Instant,
    bucket: BucketSize,
    withYear: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val local = start.atZone(zoneId)
    val year = if (withYear) ", yyyy" else ""
    return when (bucket) {
        BucketSize.MINUTE, BucketSize.HOUR -> local.format(DateTimeFormatter.ofPattern("MMM d$year, h:mm a"))
        BucketSize.DAY -> local.format(DateTimeFormatter.ofPattern("EEE, MMM d$year"))
        BucketSize.WEEK -> {
            val end = local.plusDays(6)
            val endPattern = if (end.month == local.month) "d" else "MMM d"
            "${local.format(DateTimeFormatter.ofPattern("MMM d"))} – " +
                end.format(DateTimeFormatter.ofPattern("$endPattern$year"))
        }
        BucketSize.MONTH -> local.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    }
}

private const val READOUT_FADE_MS = 180

/** How long a selection must rest on a band bucket before its times are read. */
internal const val SELECTION_SETTLE_MS = 300L

private val STAT_SPACING = 8.dp

/** Sizes the pending-time placeholder like a typical time. */
private const val TIME_PLACEHOLDER = "00:00 AM"

/** Below this panel width the "View entries" link drops its label. */
private val COMPACT_PANEL_WIDTH = 300.dp

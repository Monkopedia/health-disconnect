package com.monkopedia.healthdisconnect.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One labelled number in a readout row; [caption] is null when the row's own caption says it.
 * [time] is when a selected band bucket's max or min was read.
 */
private data class ReadoutStat(val caption: String?, val value: String, val time: String? = null)

/** One series' line in the readout: its colour dot, an optional caption, and one or three stats. */
private data class ReadoutRow(val color: Color, val caption: String?, val stats: List<ReadoutStat>)

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
)

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
    onOpenRange: ((ChartRange) -> Unit)? = null
) {
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
            rows = readoutRows(geometry, seriesColors, selectedSlot),
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
                            // Two lines at most: a week or month's "Tue Feb 10, 4:05 PM" wraps in a
                            // third of a small phone rather than losing the time.
                            stat.time?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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

/**
 * How a band bucket's max/min time reads: the clock time, with the date once the bucket spans
 * several days, or only the date when the band is of whole days. Null for a day of daily totals.
 */
private fun extremeTimePattern(series: HealthDataModel.MetricSeries): String? = when {
    series.bandTimesAreDays -> "EEE MMM d".takeIf { series.bucketSize > BucketSize.DAY }
    series.bucketSize == BucketSize.MINUTE -> "h:mm:ss a"
    series.bucketSize > BucketSize.DAY -> "EEE MMM d, h:mm a"
    else -> "h:mm a"
}

@Composable
private fun readoutRows(geometry: ChartGeometry, seriesColors: List<Color>, slot: Int?): List<ReadoutRow> {
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
        fun row(stat: String?, vararg stats: ReadoutStat): ReadoutRow {
            val caption = when {
                !multiSeries -> stat
                stat == null -> series.label
                else -> seriesCaptionFormat.format(series.label, stat)
            }
            return ReadoutRow(color, caption, stats.toList())
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
                val times = index?.let { series.bandTimes?.get(it) }
                val formatter = extremeTimePattern(series)?.let(DateTimeFormatter::ofPattern)
                fun time(at: Instant?) = formatter?.let { at?.atZone(ZoneId.systemDefault())?.format(it) }
                listOf(
                    row(
                        null,
                        ReadoutStat(max, format(index?.let { bandMax[it].value }), time(times?.maxAt)),
                        ReadoutStat(average, format(index?.let { series.points[it].value })),
                        ReadoutStat(min, format(index?.let { bandMin[it].value }), time(times?.minAt))
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

private val STAT_SPACING = 8.dp

/** Below this panel width the "View entries" link drops its label. */
private val COMPACT_PANEL_WIDTH = 300.dp

package com.monkopedia.healthdisconnect.model

import java.time.Instant
import java.time.ZoneId

/**
 * A span of a view's chart, drawn on its own and bucketed by [bucketSize]: [start] inclusive to
 * [end] exclusive. It is a temporary zoom (issue #137): a view shown for a range is built in memory
 * by [zoomedTo] and never saved.
 */
data class ChartRange(val start: Instant, val end: Instant, val bucketSize: BucketSize) {
    /**
     * The window whose axis labels suit this range: time-of-day for hourly buckets, dates for
     * daily ones. The range's own [start]..[end] sets the span; this only picks the labelling.
     */
    val timeWindow: TimeWindow
        get() = when (bucketSize) {
            BucketSize.MINUTE, BucketSize.HOUR -> TimeWindow.HOURS_24
            else -> TimeWindow.DAYS_30
        }

    companion object {
        /**
         * The range a selected bucket opens into: a day hour by hour, and a week or a month day by
         * day. Null for an hour or a minute, which are already the finest buckets worth drawing.
         */
        fun zoomInto(bucketStart: Instant, bucket: BucketSize, zoneId: ZoneId): ChartRange? {
            val finer = when (bucket) {
                BucketSize.DAY -> BucketSize.HOUR
                BucketSize.WEEK, BucketSize.MONTH -> BucketSize.DAY
                BucketSize.MINUTE, BucketSize.HOUR -> return null
            }
            return ChartRange(bucketStart, bucket.bucketEnd(bucketStart, zoneId), finer)
        }
    }
}

/**
 * The end (exclusive) of the bucket that starts at [start], on the calendar of [zoneId], so a day
 * across a DST change is 23 or 25 hours, as the aggregation buckets it.
 */
fun BucketSize.bucketEnd(start: Instant, zoneId: ZoneId): Instant {
    val local = start.atZone(zoneId)
    return when (this) {
        BucketSize.MINUTE -> local.plusMinutes(1)
        BucketSize.HOUR -> local.plusHours(1)
        BucketSize.DAY -> local.plusDays(1)
        BucketSize.WEEK -> local.plusWeeks(1)
        BucketSize.MONTH -> local.plusMonths(1)
    }.toInstant()
}

/** This view drawn over [range] alone, bucketed by its bucket size. In memory only: never saved. */
fun DataView.zoomedTo(range: ChartRange): DataView {
    fun MetricChartSettings.zoomed() = copy(bucketSize = range.bucketSize, timeWindow = range.timeWindow)
    return copy(
        chartSettings = chartSettings.copy(bucketSize = range.bucketSize, timeWindow = range.timeWindow),
        records = records.map { it.copy(metricSettings = it.metricSettings?.zoomed()) }
    )
}

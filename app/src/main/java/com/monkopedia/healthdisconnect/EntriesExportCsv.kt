package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.Record
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection
import java.time.Instant

enum class EntriesExportMode {
    AGGREGATED,
    RAW
}

fun buildAggregatedEntriesCsv(
    view: DataView,
    seriesList: List<HealthDataModel.MetricSeries>
): String {
    val rows = mutableListOf<List<String>>()
    rows += listOf(
        "view_id",
        "view_type",
        "series_index",
        "record_fqn",
        "series_label",
        "series_unit",
        "bucket_date",
        "value",
        "peak_value_in_window",
        "time_window",
        "chart_type",
        "background_style",
        "aggregation",
        "bucket_size",
        "y_axis_mode",
        "smoothing",
        "unit_preference",
        "show_data_points"
    )

    seriesList.forEachIndexed { index, series ->
        val selection = view.records.getOrNull(index)
        val settings = metricSettingsForExport(view, selection)
        if (series.points.isEmpty()) {
            rows += listOf(
                view.id.toString(),
                view.type.name,
                index.toString(),
                selection?.fqn.orEmpty(),
                series.label,
                series.unit.orEmpty(),
                "",
                "",
                series.peakValueInWindow.toString(),
                view.chartSettings.timeWindow.name,
                view.chartSettings.chartType.name,
                view.chartSettings.backgroundStyle.name,
                settings.aggregation,
                settings.bucketSize,
                settings.yAxisMode,
                settings.smoothing,
                settings.unitPreference,
                view.chartSettings.showDataPoints.toString()
            )
        } else {
            series.points.forEach { point ->
                rows += listOf(
                    view.id.toString(),
                    view.type.name,
                    index.toString(),
                    selection?.fqn.orEmpty(),
                    series.label,
                    series.unit.orEmpty(),
                    point.date.toString(),
                    point.value.toString(),
                    series.peakValueInWindow.toString(),
                    view.chartSettings.timeWindow.name,
                    view.chartSettings.chartType.name,
                    view.chartSettings.backgroundStyle.name,
                    settings.aggregation,
                    settings.bucketSize,
                    settings.yAxisMode,
                    settings.smoothing,
                    settings.unitPreference,
                    view.chartSettings.showDataPoints.toString()
                )
            }
        }
    }

    return rows.toCsvText()
}

fun buildRawEntriesCsv(records: List<Record>): String {
    val rowMaps = records.map { record ->
        val values = exportGetterValues(record)
        ExportRow(
            record = record,
            timestamp = recordTimestampIso(record),
            values = values
        )
    }
    val dynamicHeaders = rowMaps
        .flatMap { it.values.keys }
        .distinct()
        .sorted()
    val header = listOf("record_type", "record_fqn", "timestamp") + dynamicHeaders
    val rows = mutableListOf<List<String>>()
    rows.add(header)
    rowMaps.forEach { row ->
        rows.add(buildList {
            add(row.record::class.simpleName ?: "Record")
            add(row.record::class.qualifiedName.orEmpty())
            add(row.timestamp.orEmpty())
            dynamicHeaders.forEach { key ->
                add(row.values[key].orEmpty())
            }
        })
    }
    return rows.toCsvText()
}

private data class ExportRow(
    val record: Record,
    val timestamp: String?,
    val values: Map<String, String>
)

private data class MetricExportSettings(
    val aggregation: String,
    val bucketSize: String,
    val yAxisMode: String,
    val smoothing: String,
    val unitPreference: String
)

private fun metricSettingsForExport(
    view: DataView,
    selection: RecordSelection?
): MetricExportSettings {
    val metricSettings = selection?.metricSettings
    return MetricExportSettings(
        aggregation = (metricSettings?.aggregation ?: view.chartSettings.aggregation).name,
        bucketSize = (metricSettings?.bucketSize ?: view.chartSettings.bucketSize).name,
        yAxisMode = (metricSettings?.yAxisMode ?: view.chartSettings.yAxisMode).name,
        smoothing = (metricSettings?.smoothing ?: view.chartSettings.smoothing).name,
        unitPreference = (metricSettings?.unitPreference ?: view.chartSettings.unitPreference).name
    )
}

private fun exportGetterValues(record: Record): Map<String, String> {
    return record.javaClass.methods
        .asSequence()
        .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" }
        .sortedBy { it.name }
        .mapNotNull { method ->
            val fieldName = method.name.removePrefix("get")
            val value = runCatching { method.invoke(record) }.getOrNull() ?: return@mapNotNull null
            fieldName to value.toString()
        }
        .toMap()
}

private fun recordTimestampIso(record: Record): String? {
    val getterNames = listOf("getTime", "getStartTime", "getEndTime")
    val instant = getterNames.asSequence().mapNotNull { name ->
        runCatching { record.javaClass.getMethod(name).invoke(record) as? Instant }.getOrNull()
    }.firstOrNull() ?: runCatching { record.metadata.lastModifiedTime }.getOrNull()
    return instant?.toString()
}

private fun List<List<String>>.toCsvText(): String {
    return joinToString(separator = "\n") { row ->
        row.joinToString(separator = ",") { csvEscape(it) }
    }
}

private fun csvEscape(value: String): String {
    val requiresQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!requiresQuotes) return value
    return "\"${value.replace("\"", "\"\"")}\""
}

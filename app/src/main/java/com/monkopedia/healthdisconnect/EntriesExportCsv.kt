package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import com.monkopedia.healthdisconnect.model.DataView
import com.monkopedia.healthdisconnect.model.RecordSelection

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
    return when (record) {
        is WeightRecord -> mapOf("Weight" to record.weight.toString())
        is StepsRecord -> mapOf("Count" to record.count.toString())
        is DistanceRecord -> mapOf("Distance" to record.distance.toString())
        is BloodGlucoseRecord -> mapOf("Level" to record.level.toString(), "SpecimenSource" to record.specimenSource.toString(), "MealType" to record.mealType.toString(), "RelationToMeal" to record.relationToMeal.toString())
        is BloodPressureRecord -> mapOf("Systolic" to record.systolic.toString(), "Diastolic" to record.diastolic.toString(), "BodyPosition" to record.bodyPosition.toString(), "MeasurementLocation" to record.measurementLocation.toString())
        is TotalCaloriesBurnedRecord -> mapOf("Energy" to record.energy.toString())
        is ActiveCaloriesBurnedRecord -> mapOf("Energy" to record.energy.toString())
        is SleepSessionRecord -> mapOf("StartTime" to record.startTime.toString(), "EndTime" to record.endTime.toString(), "StagesCount" to record.stages.size.toString())
        is NutritionRecord -> buildMap {
            record.energy?.let { put("Energy", it.toString()) }
            record.protein?.let { put("Protein", it.toString()) }
            record.totalCarbohydrate?.let { put("TotalCarbohydrate", it.toString()) }
            record.totalFat?.let { put("TotalFat", it.toString()) }
            record.sugar?.let { put("Sugar", it.toString()) }
            record.dietaryFiber?.let { put("DietaryFiber", it.toString()) }
            record.name?.let { put("Name", it) }
        }
        is HeartRateRecord -> mapOf("SamplesCount" to record.samples.size.toString(), "AvgBpm" to if (record.samples.isEmpty()) "" else record.samples.map { it.beatsPerMinute.toDouble() }.average().toString())
        is BodyFatRecord -> mapOf("Percentage" to record.percentage.toString())
        is BodyTemperatureRecord -> mapOf("Temperature" to record.temperature.toString(), "MeasurementLocation" to record.measurementLocation.toString())
        is BasalBodyTemperatureRecord -> mapOf("Temperature" to record.temperature.toString(), "MeasurementLocation" to record.measurementLocation.toString())
        is BasalMetabolicRateRecord -> mapOf("BasalMetabolicRate" to record.basalMetabolicRate.toString())
        is HeightRecord -> mapOf("Height" to record.height.toString())
        is BodyWaterMassRecord -> mapOf("Mass" to record.mass.toString())
        is BoneMassRecord -> mapOf("Mass" to record.mass.toString())
        is LeanBodyMassRecord -> mapOf("Mass" to record.mass.toString())
        is OxygenSaturationRecord -> mapOf("Percentage" to record.percentage.toString())
        is RespiratoryRateRecord -> mapOf("Rate" to record.rate.toString())
        is RestingHeartRateRecord -> mapOf("BeatsPerMinute" to record.beatsPerMinute.toString())
        is Vo2MaxRecord -> mapOf("Vo2MillilitersPerMinuteKilogram" to record.vo2MillilitersPerMinuteKilogram.toString())
        is HeartRateVariabilityRmssdRecord -> mapOf("HeartRateVariabilityMillis" to record.heartRateVariabilityMillis.toString())
        is FloorsClimbedRecord -> mapOf("Floors" to record.floors.toString())
        is HydrationRecord -> mapOf("Volume" to record.volume.toString())
        is ElevationGainedRecord -> mapOf("Elevation" to record.elevation.toString())
        is ExerciseSessionRecord -> mapOf("ExerciseType" to record.exerciseType.toString())
        is WheelchairPushesRecord -> mapOf("Count" to record.count.toString())
        is SpeedRecord -> mapOf("SamplesCount" to record.samples.size.toString())
        is PowerRecord -> mapOf("SamplesCount" to record.samples.size.toString())
        is StepsCadenceRecord -> mapOf("SamplesCount" to record.samples.size.toString())
        is CyclingPedalingCadenceRecord -> mapOf("SamplesCount" to record.samples.size.toString())
        is MenstruationPeriodRecord -> mapOf("StartTime" to record.startTime.toString(), "EndTime" to record.endTime.toString())
        else -> emptyMap()
    }
}

private fun recordTimestampIso(record: Record): String? {
    return recordTimestamp(record)?.toString()
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

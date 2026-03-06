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
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

data class ParsedMeasurementText(val value: Double, val unit: String?)

fun formatAxisValue(value: Double): String {
    if (!value.isFinite()) return value.toString()
    if (value == 0.0) return "0"
    val rounded = roundToSignificantFigures(value, significantFigures = 3)
    val normalized = if (rounded == -0.0) 0.0 else rounded
    return BigDecimal.valueOf(normalized).stripTrailingZeros().toPlainString()
}

fun formatValueWithUnit(value: Double, unit: String?): String {
    val normalizedUnit = unit?.trim().takeUnless { it.isNullOrBlank() } ?: return formatAxisValue(value)
    if (isMinuteUnit(normalizedUnit)) {
        return formatMinutesWithUnit(value)
    }
    val abbreviatedUnit = abbreviateUnit(normalizedUnit)
    if (abbreviatedUnit == "%") {
        return "${formatAxisValue(value)}%"
    }
    return "${formatAxisValue(value)} $abbreviatedUnit"
}

fun recordDetailsText(record: Record): String {
    val lines = mutableListOf<String>()
    lines += "Type: ${record::class.simpleName ?: "Record"}"
    val fields = recordDetailFields(record)
    if (fields.isNotEmpty()) {
        lines += ""
        fields.forEach { (name, value) ->
            lines += "$name: ${formatDetailValue(value)}"
        }
    }
    return lines.joinToString("\n")
}

fun recordTimestamp(record: Record): Instant? {
    return when (record) {
        is WeightRecord -> record.time
        is BloodGlucoseRecord -> record.time
        is BloodPressureRecord -> record.time
        is BodyFatRecord -> record.time
        is BodyTemperatureRecord -> record.time
        is BasalBodyTemperatureRecord -> record.time
        is BasalMetabolicRateRecord -> record.time
        is BodyWaterMassRecord -> record.time
        is BoneMassRecord -> record.time
        is HeightRecord -> record.time
        is LeanBodyMassRecord -> record.time
        is OxygenSaturationRecord -> record.time
        is RespiratoryRateRecord -> record.time
        is RestingHeartRateRecord -> record.time
        is Vo2MaxRecord -> record.time
        is HeartRateVariabilityRmssdRecord -> record.time
        is CervicalMucusRecord -> record.time
        is MenstruationFlowRecord -> record.time
        is OvulationTestRecord -> record.time
        is IntermenstrualBleedingRecord -> record.time
        is SexualActivityRecord -> record.time
        is StepsRecord -> record.startTime
        is DistanceRecord -> record.startTime
        is TotalCaloriesBurnedRecord -> record.startTime
        is ActiveCaloriesBurnedRecord -> record.startTime
        is SleepSessionRecord -> record.startTime
        is NutritionRecord -> record.startTime
        is ExerciseSessionRecord -> record.startTime
        is FloorsClimbedRecord -> record.startTime
        is HydrationRecord -> record.startTime
        is ElevationGainedRecord -> record.startTime
        is HeartRateRecord -> record.startTime
        is SpeedRecord -> record.startTime
        is PowerRecord -> record.startTime
        is StepsCadenceRecord -> record.startTime
        is CyclingPedalingCadenceRecord -> record.startTime
        is WheelchairPushesRecord -> record.startTime
        is MenstruationPeriodRecord -> record.startTime
        else -> null
    }
}

fun recordTimestampLabel(record: Record): String? {
    return recordTimestamp(record)?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a"))
}

fun recordPrimaryValueLabel(record: Record): String? {
    return when (record) {
        is WeightRecord -> formatValueWithUnit(record.weight.inKilograms, "kg")
        is StepsRecord -> "${record.count}"
        is DistanceRecord -> formatValueWithUnit(record.distance.inKilometers, "km")
        is BloodGlucoseRecord -> formatValueWithUnit(record.level.inMilligramsPerDeciliter, "mg/dL")
        is BloodPressureRecord -> "${formatAxisValue(record.systolic.inMillimetersOfMercury)}/${formatAxisValue(record.diastolic.inMillimetersOfMercury)} mmHg"
        is TotalCaloriesBurnedRecord -> formatValueWithUnit(record.energy.inKilocalories, "kcal")
        is ActiveCaloriesBurnedRecord -> formatValueWithUnit(record.energy.inKilocalories, "kcal")
        is SleepSessionRecord -> {
            if (record.endTime.isBefore(record.startTime)) null
            else formatValueWithUnit(ChronoUnit.SECONDS.between(record.startTime, record.endTime).toDouble() / 60.0, "minutes")
        }
        is NutritionRecord -> record.energy?.let { formatValueWithUnit(it.inKilocalories, "kcal") }
        is HeartRateRecord -> if (record.samples.isEmpty()) null else formatValueWithUnit(record.samples.map { it.beatsPerMinute.toDouble() }.average(), "bpm")
        is BodyFatRecord -> formatValueWithUnit(record.percentage.value, "%")
        is BodyTemperatureRecord -> formatValueWithUnit(record.temperature.inCelsius, "celsius")
        is BasalBodyTemperatureRecord -> formatValueWithUnit(record.temperature.inCelsius, "celsius")
        is BasalMetabolicRateRecord -> formatValueWithUnit(record.basalMetabolicRate.inWatts, "watts")
        is HeightRecord -> formatValueWithUnit(record.height.inMeters * 100.0, "cm")
        is OxygenSaturationRecord -> formatValueWithUnit(record.percentage.value, "%")
        is RespiratoryRateRecord -> formatValueWithUnit(record.rate, "breaths/min")
        is RestingHeartRateRecord -> "${record.beatsPerMinute} bpm"
        is Vo2MaxRecord -> formatValueWithUnit(record.vo2MillilitersPerMinuteKilogram, "mL/min/kg")
        is HeartRateVariabilityRmssdRecord -> formatValueWithUnit(record.heartRateVariabilityMillis, "ms")
        is FloorsClimbedRecord -> formatValueWithUnit(record.floors.toDouble(), "floors")
        is HydrationRecord -> formatValueWithUnit(record.volume.inLiters, "liters")
        is BodyWaterMassRecord -> formatValueWithUnit(record.mass.inKilograms, "kg")
        is BoneMassRecord -> formatValueWithUnit(record.mass.inKilograms, "kg")
        is LeanBodyMassRecord -> formatValueWithUnit(record.mass.inKilograms, "kg")
        is ElevationGainedRecord -> formatValueWithUnit(record.elevation.inMeters, "meters")
        is WheelchairPushesRecord -> "${record.count}"
        is SpeedRecord -> if (record.samples.isEmpty()) null else formatValueWithUnit(record.samples.map { it.speed.inKilometersPerHour }.average(), "km/h")
        is PowerRecord -> if (record.samples.isEmpty()) null else formatValueWithUnit(record.samples.map { it.power.inWatts }.average(), "watts")
        is StepsCadenceRecord -> if (record.samples.isEmpty()) null else formatValueWithUnit(record.samples.map { it.rate }.average(), "steps/min")
        is CyclingPedalingCadenceRecord -> if (record.samples.isEmpty()) null else formatValueWithUnit(record.samples.map { it.revolutionsPerMinute }.average(), "rpm")
        is ExerciseSessionRecord -> {
            if (record.endTime.isBefore(record.startTime)) null
            else formatValueWithUnit(ChronoUnit.SECONDS.between(record.startTime, record.endTime).toDouble() / 60.0, "minutes")
        }
        is MenstruationPeriodRecord -> {
            if (record.endTime.isBefore(record.startTime)) null
            else formatValueWithUnit(ChronoUnit.SECONDS.between(record.startTime, record.endTime).toDouble() / 60.0, "minutes")
        }
        else -> null
    }
}

private fun recordDetailFields(record: Record): List<Pair<String, Any>> {
    return when (record) {
        is WeightRecord -> listOf("Time" to record.time, "Weight" to record.weight)
        is StepsRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Count" to record.count)
        is DistanceRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Distance" to record.distance)
        is BloodGlucoseRecord -> listOf("Time" to record.time, "Level" to record.level, "SpecimenSource" to record.specimenSource, "MealType" to record.mealType, "RelationToMeal" to record.relationToMeal)
        is BloodPressureRecord -> listOf("Time" to record.time, "Systolic" to record.systolic, "Diastolic" to record.diastolic, "BodyPosition" to record.bodyPosition, "MeasurementLocation" to record.measurementLocation)
        is TotalCaloriesBurnedRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Energy" to record.energy)
        is ActiveCaloriesBurnedRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Energy" to record.energy)
        is SleepSessionRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Stages" to record.stages.size)
        is NutritionRecord -> buildList {
            add("StartTime" to record.startTime as Any)
            add("EndTime" to record.endTime as Any)
            record.energy?.let { add("Energy" to it) }
            record.protein?.let { add("Protein" to it) }
            record.totalCarbohydrate?.let { add("TotalCarbohydrate" to it) }
            record.totalFat?.let { add("TotalFat" to it) }
            record.sugar?.let { add("Sugar" to it) }
            record.dietaryFiber?.let { add("DietaryFiber" to it) }
            record.name?.let { add("Name" to it) }
        }
        is HeartRateRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Samples" to "${record.samples.size} samples")
        is BodyFatRecord -> listOf("Time" to record.time, "Percentage" to record.percentage)
        is BodyTemperatureRecord -> listOf("Time" to record.time, "Temperature" to record.temperature, "MeasurementLocation" to record.measurementLocation)
        is BasalBodyTemperatureRecord -> listOf("Time" to record.time, "Temperature" to record.temperature, "MeasurementLocation" to record.measurementLocation)
        is BasalMetabolicRateRecord -> listOf("Time" to record.time, "BasalMetabolicRate" to record.basalMetabolicRate)
        is HeightRecord -> listOf("Time" to record.time, "Height" to record.height)
        is BodyWaterMassRecord -> listOf("Time" to record.time, "Mass" to record.mass)
        is BoneMassRecord -> listOf("Time" to record.time, "Mass" to record.mass)
        is LeanBodyMassRecord -> listOf("Time" to record.time, "Mass" to record.mass)
        is OxygenSaturationRecord -> listOf("Time" to record.time, "Percentage" to record.percentage)
        is RespiratoryRateRecord -> listOf("Time" to record.time, "Rate" to record.rate)
        is RestingHeartRateRecord -> listOf("Time" to record.time, "BeatsPerMinute" to record.beatsPerMinute)
        is Vo2MaxRecord -> listOf("Time" to record.time, "Vo2MillilitersPerMinuteKilogram" to record.vo2MillilitersPerMinuteKilogram)
        is HeartRateVariabilityRmssdRecord -> listOf("Time" to record.time, "HeartRateVariabilityMillis" to record.heartRateVariabilityMillis)
        is FloorsClimbedRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Floors" to record.floors)
        is HydrationRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Volume" to record.volume)
        is ElevationGainedRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Elevation" to record.elevation)
        is ExerciseSessionRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "ExerciseType" to record.exerciseType)
        is WheelchairPushesRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Count" to record.count)
        is SpeedRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Samples" to "${record.samples.size} samples")
        is PowerRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Samples" to "${record.samples.size} samples")
        is StepsCadenceRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Samples" to "${record.samples.size} samples")
        is CyclingPedalingCadenceRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime, "Samples" to "${record.samples.size} samples")
        is MenstruationPeriodRecord -> listOf("StartTime" to record.startTime, "EndTime" to record.endTime)
        is CervicalMucusRecord -> listOf("Time" to record.time, "Appearance" to record.appearance, "Sensation" to record.sensation)
        is MenstruationFlowRecord -> listOf("Time" to record.time, "Flow" to record.flow)
        is OvulationTestRecord -> listOf("Time" to record.time, "Result" to record.result)
        is IntermenstrualBleedingRecord -> listOf("Time" to record.time)
        is SexualActivityRecord -> listOf("Time" to record.time, "ProtectionUsed" to record.protectionUsed)
        else -> listOf("Type" to (record::class.qualifiedName ?: "Unknown") as Any)
    }
}

fun parseMeasurementFromText(text: String): ParsedMeasurementText? {
    val match = Regex("""^\s*([-+]?\d+(?:\.\d+)?)\s*([^\d].*)?\s*$""").find(text)
        ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit = match.groupValues.getOrNull(2)?.trim().orEmpty().ifBlank { null }
        ?.takeIf(::isLikelyMeasurementUnit)
    return ParsedMeasurementText(value, unit)
}

private fun formatDetailValue(value: Any): String {
    return when (value) {
        is Number -> formatAxisValue(value.toDouble())
        else -> {
            val text = value.toString()
            val parsed = parseMeasurementFromText(text)
            if (parsed != null) {
                if (parsed.unit.isNullOrBlank()) {
                    formatAxisValue(parsed.value)
                } else {
                    formatValueWithUnit(parsed.value, parsed.unit)
                }
            } else {
                text
            }
        }
    }
}

private fun roundToSignificantFigures(value: Double, significantFigures: Int): Double {
    if (value == 0.0) return 0.0
    val exponent = floor(log10(abs(value))).toInt()
    val scale = significantFigures - exponent - 1
    return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toDouble()
}

private fun isMinuteUnit(unit: String): Boolean {
    return when (canonicalUnit(unit)) {
        "minute",
        "minutes",
        "min",
        "mins" -> true
        else -> false
    }
}

private fun formatMinutesWithUnit(value: Double): String {
    val roundedMinutes = value.roundToInt()
    val sign = if (roundedMinutes < 0) "-" else ""
    val absMinutes = kotlin.math.abs(roundedMinutes)
    if (absMinutes < 60) {
        return "$sign${absMinutes} mins"
    }
    val hours = absMinutes / 60
    val minutes = absMinutes % 60
    val hourLabel = if (hours == 1) "hr" else "hrs"
    return "$sign$hours $hourLabel $minutes mins"
}

private fun abbreviateUnit(unit: String): String {
    return when (canonicalUnit(unit)) {
        "pounds" -> "lbs"
        "pound", "lb", "lbs" -> "lb"
        "ounces", "ounce", "oz" -> "oz"
        "kilograms", "kilogram", "kg" -> "kg"
        "grams", "gram", "g" -> "g"
        "milligrams", "milligram", "mg" -> "mg"
        "micrograms", "microgram", "mcg" -> "mcg"
        "miles", "mile", "mi" -> "mi"
        "kilometers", "kilometer", "km" -> "km"
        "meters", "meter", "m" -> "m"
        "meterspersecond", "meterssec", "mps", "ms" -> "m/s"
        "kilometersperhour", "kmh" -> "km/h"
        "milesperhour", "milesh", "mph" -> "mph"
        "liters", "liter", "litres", "litre", "l" -> "L"
        "milliliters", "milliliter", "millilitres", "millilitre", "ml" -> "mL"
        "kilocalories", "kilocalorie", "kcal" -> "kcal"
        "calories", "calorie", "cal" -> "cal"
        "kilojoules", "kilojoule", "kj" -> "kJ"
        "joules", "joule", "j" -> "J"
        "millimetersofmercury", "millimetersmercury", "mmhg" -> "mmHg"
        "millimolesperliter", "mmoll" -> "mmol/L"
        "milligramsperdeciliter", "mgdl" -> "mg/dL"
        "beatsperminute", "bpm" -> "bpm"
        "revolutionsperminute", "rpm" -> "rpm"
        "count", "counts" -> "ct"
        "percent", "percentage", "pct", "%" -> "%"
        "minutes", "minute", "min", "mins" -> "mins"
        else -> unit
    }
}

private fun canonicalUnit(unit: String): String {
    return unit.trim().lowercase().replace(Regex("[^a-z0-9%]+"), "")
}

private fun isLikelyMeasurementUnit(unit: String): Boolean {
    val first = unit.trim().firstOrNull() ?: return false
    return first.isLetter() || first == '%' || first == '°'
}

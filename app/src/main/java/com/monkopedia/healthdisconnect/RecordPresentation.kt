package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.Record
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    lines += "Type: ${record::class.qualifiedName ?: record::class.simpleName ?: "Record"}"
    val methodLines = record.javaClass.methods
        .asSequence()
        .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" }
        .sortedBy { it.name }
        .mapNotNull { method ->
            val value = runCatching { method.invoke(record) }.getOrNull() ?: return@mapNotNull null
            "${method.name.removePrefix("get")}: ${formatDetailValue(value)}"
        }
        .take(40)
        .toList()
    if (methodLines.isNotEmpty()) {
        lines += ""
        lines += methodLines
    }
    return lines.joinToString("\n")
}

fun recordTimestampLabel(record: Record): String? {
    val names = listOf("getTime", "getStartTime", "getEndTime")
    val instant = names.asSequence().mapNotNull { method ->
        runCatching { record.javaClass.getMethod(method).invoke(record) as? Instant }.getOrNull()
    }.firstOrNull()
        ?: runCatching { record.metadata.lastModifiedTime }.getOrNull()
    return instant?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a"))
}

fun recordPrimaryValueLabel(record: Record): String? {
    val ignored = setOf(
        "getClass",
        "getMetadata",
        "getZoneOffset",
        "getStartZoneOffset",
        "getEndZoneOffset",
        "getTime",
        "getStartTime",
        "getEndTime"
    )
    val preferredFields = listOf(
        "level", "weight", "distance", "energy", "count", "temperature", "rate", "speed"
    )
    data class Candidate(val valueText: String, val score: Int)
    val candidates = mutableListOf<Candidate>()
    record.javaClass.methods
        .asSequence()
        .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name !in ignored }
        .forEach { getter ->
            val field = getter.name.removePrefix("get")
            val fieldLower = field.lowercase()
            val baseScore = preferredFields.indexOfFirst { fieldLower.contains(it) }
                .let { if (it >= 0) 100 - it else 10 }
            val raw = runCatching { getter.invoke(record) }.getOrNull() ?: return@forEach
            if (raw is Number) {
                candidates += Candidate(
                    valueText = formatAxisValue(raw.toDouble()),
                    score = baseScore
                )
            } else {
                val unitGetter = raw.javaClass.methods.firstOrNull { unitMethod ->
                    unitMethod.parameterCount == 0 &&
                        (unitMethod.name.startsWith("getIn") || unitMethod.name.startsWith("in")) &&
                        (
                            Number::class.java.isAssignableFrom(unitMethod.returnType) ||
                                unitMethod.returnType == java.lang.Double.TYPE ||
                                unitMethod.returnType == java.lang.Float.TYPE ||
                                unitMethod.returnType == java.lang.Integer.TYPE ||
                                unitMethod.returnType == java.lang.Long.TYPE
                            )
                }
                if (unitGetter != null) {
                    val value = runCatching { (unitGetter.invoke(raw) as? Number)?.toDouble() }.getOrNull()
                    val unit = unitGetter.name.removePrefix("getIn").removePrefix("in")
                        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                    if (value != null) {
                        candidates += Candidate(
                            valueText = formatValueWithUnit(value, unit),
                            score = baseScore + 20
                        )
                    }
                } else {
                    parseMeasurementFromText(raw.toString())?.let { measurement ->
                        candidates += Candidate(
                            valueText = if (measurement.unit.isNullOrBlank()) {
                                formatAxisValue(measurement.value)
                            } else {
                                formatValueWithUnit(measurement.value, measurement.unit)
                            },
                            score = baseScore + 5
                        )
                    }
                }
            }
        }
    return candidates.maxByOrNull { it.score }?.valueText
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

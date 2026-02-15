package com.monkopedia.healthdisconnect

import androidx.health.connect.client.records.Record
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

data class ParsedMeasurementText(val value: Double, val unit: String?)

fun formatAxisValue(value: Double): String {
    return if (abs(value) < 10) {
        String.format("%.1f", value)
    } else {
        value.roundToInt().toString()
    }
}

fun unitSuffix(unit: String?): String {
    return if (unit.isNullOrBlank()) "" else " $unit"
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
            "${method.name.removePrefix("get")}: $value"
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
                            valueText = "${formatAxisValue(value)} $unit",
                            score = baseScore + 20
                        )
                    }
                } else {
                    parseMeasurementFromText(raw.toString())?.let { measurement ->
                        candidates += Candidate(
                            valueText = if (measurement.unit.isNullOrBlank()) {
                                formatAxisValue(measurement.value)
                            } else {
                                "${formatAxisValue(measurement.value)} ${measurement.unit}"
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
    val match = Regex("""^\s*([-+]?\d+(?:\.\d+)?)\s*([^\d].+)?\s*$""").find(text)
        ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit = match.groupValues.getOrNull(2)?.trim().orEmpty().ifBlank { null }
    return ParsedMeasurementText(value, unit)
}

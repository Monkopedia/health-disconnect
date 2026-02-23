package com.monkopedia.healthdisconnect

import android.util.Log
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.monkopedia.healthdisconnect.model.UnitPreference
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

data class MetricMeasurement(
    val timestamp: Instant,
    val value: Double,
    val unitLabel: String?,
    val sourceField: String?
)

data class ExtractableMetric(
    val key: String? = null,
    val label: String? = null
)

interface HealthRecordMeasurementExtractor {
    fun extractMeasurement(record: Record, unitPreference: UnitPreference): MetricMeasurement?
    fun extractMeasurement(
        record: Record,
        unitPreference: UnitPreference,
        metricKey: String?
    ): MetricMeasurement? {
        return extractMeasurement(record, unitPreference)
    }
    fun availableMetrics(recordClass: KClass<out Record>): List<ExtractableMetric> {
        return listOf(ExtractableMetric())
    }
    fun metricLabel(recordClass: KClass<out Record>, metricKey: String?): String? {
        return availableMetrics(recordClass)
            .firstOrNull { it.key == metricKey }
            ?.label
    }
    fun recordTimestamp(record: Record): Instant?
}

class DefaultHealthRecordMeasurementExtractor : HealthRecordMeasurementExtractor {
    override fun extractMeasurement(record: Record, unitPreference: UnitPreference): MetricMeasurement? {
        return extractMeasurement(record, unitPreference, metricKey = null)
    }

    override fun extractMeasurement(
        record: Record,
        unitPreference: UnitPreference,
        metricKey: String?
    ): MetricMeasurement? {
        val timestamp = recordTimestamp(record) ?: return null
        extractSleepSessionMetric(
            record = record,
            timestamp = timestamp,
            metricKey = metricKey
        )?.let { return it }
        extractBloodPressureMetric(
            record = record,
            timestamp = timestamp,
            metricKey = metricKey
        )?.let { return it }
        extractNutritionMetric(
            record = record,
            timestamp = timestamp,
            metricKey = metricKey,
            unitPreference = unitPreference
        )?.let { return it }
        staticExtractors[record::class]?.let { extractor ->
            return extractor(record, unitPreference, timestamp)
        }
        val typePreference = reflectiveTypePreferencesByFqn[record::class.qualifiedName]

        val methods = record.javaClass.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") }
            .filterNot {
                it.name in setOf(
                    "getMetadata",
                    "getZoneOffset",
                    "getStartZoneOffset",
                    "getEndZoneOffset",
                    "getTime",
                    "getStartTime",
                    "getEndTime"
                )
            }

        val candidates = mutableListOf<CandidateMeasurement>()
        methods.forEach { method ->
            val raw = runCatching { method.invoke(record) }.getOrNull() ?: return@forEach
            val methodName = method.name.removePrefix("get")
            var addedUnitCandidate = false

            (raw as? Number)?.toDouble()?.let { value ->
                candidates += CandidateMeasurement(
                    value = value,
                    unitLabel = null,
                    score = fieldScore(methodName) + preferredFieldScore(methodName, typePreference),
                    sourceField = methodName
                )
            }
            val unitGetters = raw.javaClass.methods.filter { candidate ->
                candidate.parameterCount == 0 &&
                    (candidate.name.startsWith("getIn") || candidate.name.startsWith("in")) &&
                    isNumericType(candidate.returnType)
            }
            unitGetters.forEach { unitGetter ->
                val unitRaw = unitGetter.name
                    .removePrefix("getIn")
                    .removePrefix("in")
                val value = runCatching { (unitGetter.invoke(raw) as? Number)?.toDouble() }.getOrNull()
                    ?: return@forEach
                candidates += CandidateMeasurement(
                    value = value,
                    unitLabel = normalizeUnitLabel(unitRaw),
                    score = unitScore(unitRaw, unitPreference, value) + fieldScore(methodName) + preferredFieldScore(methodName, typePreference),
                    sourceField = methodName
                )
                addedUnitCandidate = true
            }
            if (raw is Collection<*> && raw.isNotEmpty()) {
                val samples = raw.filterNotNull().take(50)
                if (samples.isNotEmpty()) {
                    val sampleMethods = samples.first().javaClass.methods.filter { sampleMethod ->
                        sampleMethod.parameterCount == 0 &&
                            sampleMethod.name.startsWith("get") &&
                            isNumericType(sampleMethod.returnType)
                    }
                    sampleMethods.forEach { sampleMethod ->
                        val sampleName = sampleMethod.name.removePrefix("get")
                        val values = samples.mapNotNull { sample ->
                            runCatching { (sampleMethod.invoke(sample) as? Number)?.toDouble() }.getOrNull()
                        }
                        if (values.isNotEmpty()) {
                            val mean = values.average()
                            candidates += CandidateMeasurement(
                                value = mean,
                                unitLabel = null,
                                score = fieldScore(methodName) + fieldScore(sampleName) +
                                    preferredFieldScore(methodName, typePreference) + preferredFieldScore(sampleName, typePreference),
                                sourceField = "$methodName.$sampleName"
                            )
                        }
                    }
                }
            }
            if (!addedUnitCandidate) {
                parseMeasurementFromString(raw.toString())?.let { parsed ->
                    candidates += CandidateMeasurement(
                        value = parsed.value,
                        unitLabel = parsed.unitLabel,
                        score = unitScore(parsed.unitLabel ?: "", unitPreference, parsed.value) +
                            fieldScore(methodName) + preferredFieldScore(methodName, typePreference) + 3,
                        sourceField = methodName
                    )
                }
            }
        }

        val prioritized = candidates
            .let { list ->
                val withUnits = list.filter { !it.unitLabel.isNullOrBlank() }
                if (withUnits.isNotEmpty()) withUnits else list
            }
        val best = prioritized.maxByOrNull { it.score }
        if (best == null && typePreference?.fallbackToDurationMinutes == true) {
            val start = safeGetTime(record, "getStartTime")
            val end = safeGetTime(record, "getEndTime")
            if (start != null && end != null && !end.isBefore(start)) {
                val minutes = ChronoUnit.SECONDS.between(start, end).toDouble() / 60.0
                return MetricMeasurement(
                    timestamp = timestamp,
                    value = minutes,
                    unitLabel = "minutes",
                    sourceField = "Duration"
                )
            }
        }
        if (best == null) return null
        logMeasurementChoice(record, unitPreference, best)
        return MetricMeasurement(
            timestamp = timestamp,
            value = best.value,
            unitLabel = best.unitLabel,
            sourceField = best.sourceField
        )
    }

    override fun availableMetrics(recordClass: KClass<out Record>): List<ExtractableMetric> {
        return when (recordClass) {
            SleepSessionRecord::class -> {
                listOf(
                    ExtractableMetric(
                        key = null,
                        label = "Sleep Duration"
                    ),
                    ExtractableMetric(
                        key = SLEEP_STAGE_METRIC_KEY,
                        label = "Sleep Stage"
                    )
                )
            }
            BloodPressureRecord::class -> {
                listOf(
                    ExtractableMetric(
                        key = null,
                        label = "Blood Pressure Systolic"
                    ),
                    ExtractableMetric(
                        key = BLOOD_PRESSURE_DIASTOLIC_METRIC_KEY,
                        label = "Blood Pressure Diastolic"
                    )
                )
            }
            NutritionRecord::class -> {
                listOf(
                    ExtractableMetric(
                        key = null,
                        label = "Nutrition Energy"
                    ),
                    ExtractableMetric(
                        key = NUTRITION_ENERGY_FROM_FAT_METRIC_KEY,
                        label = "Nutrition Energy From Fat"
                    ),
                    ExtractableMetric(
                        key = NUTRITION_PROTEIN_METRIC_KEY,
                        label = "Nutrition Protein"
                    ),
                    ExtractableMetric(
                        key = NUTRITION_TOTAL_CARBOHYDRATE_METRIC_KEY,
                        label = "Nutrition Total Carbohydrate"
                    ),
                    ExtractableMetric(
                        key = NUTRITION_TOTAL_FAT_METRIC_KEY,
                        label = "Nutrition Total Fat"
                    ),
                    ExtractableMetric(
                        key = NUTRITION_SUGAR_METRIC_KEY,
                        label = "Nutrition Sugar"
                    ),
                    ExtractableMetric(
                        key = NUTRITION_DIETARY_FIBER_METRIC_KEY,
                        label = "Nutrition Dietary Fiber"
                    )
                )
            }
            else -> listOf(ExtractableMetric())
        }
    }

    override fun recordTimestamp(record: Record): Instant? {
        val getterNames = listOf("getTime", "getStartTime", "getEndTime")
        for (getterName in getterNames) {
            try {
                val value = record.javaClass.getMethod(getterName).invoke(record)
                if (value is Instant) return value
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                // Some record types don't expose this getter. Fall back below.
            }
        }
        return try {
            record.metadata.lastModifiedTime
        } catch (exception: Exception) {
            if (exception is CancellationException) {
                throw exception
            }
            null
        }
    }

    private fun extractSleepSessionMetric(
        record: Record,
        timestamp: Instant,
        metricKey: String?
    ): MetricMeasurement? {
        val sleepRecord = record as? SleepSessionRecord ?: return null
        return when (metricKey) {
            null -> sleepDurationMeasurement(sleepRecord, timestamp)
            SLEEP_STAGE_METRIC_KEY -> sleepStageMeasurement(sleepRecord, timestamp)
            else -> null
        }
    }

    private fun sleepDurationMeasurement(
        record: SleepSessionRecord,
        timestamp: Instant
    ): MetricMeasurement? {
        if (record.endTime.isBefore(record.startTime)) return null
        val minutes = ChronoUnit.SECONDS.between(record.startTime, record.endTime).toDouble() / 60.0
        return MetricMeasurement(
            timestamp = timestamp,
            value = minutes,
            unitLabel = "minutes",
            sourceField = "Duration"
        )
    }

    private fun sleepStageMeasurement(
        record: SleepSessionRecord,
        timestamp: Instant
    ): MetricMeasurement? {
        if (record.stages.isEmpty()) return null
        val weightedDuration = record.stages.mapNotNull { stage ->
            if (stage.endTime.isBefore(stage.startTime)) return@mapNotNull null
            val seconds = ChronoUnit.SECONDS.between(stage.startTime, stage.endTime).coerceAtLeast(0)
            seconds to stage.stage.toDouble()
        }
        if (weightedDuration.isEmpty()) return null
        val totalSeconds = weightedDuration.sumOf { it.first }
        val value = if (totalSeconds > 0L) {
            weightedDuration.sumOf { (seconds, stageValue) -> seconds * stageValue } / totalSeconds
        } else {
            weightedDuration.map { it.second }.average()
        }
        return MetricMeasurement(
            timestamp = timestamp,
            value = value,
            unitLabel = "stage",
            sourceField = "Stages.Stage"
        )
    }

    private fun extractBloodPressureMetric(
        record: Record,
        timestamp: Instant,
        metricKey: String?
    ): MetricMeasurement? {
        val bloodPressureRecord = record as? BloodPressureRecord ?: return null
        return when (metricKey) {
            null -> MetricMeasurement(
                timestamp = timestamp,
                value = bloodPressureRecord.systolic.inMillimetersOfMercury,
                unitLabel = "mmHg",
                sourceField = "Systolic"
            )
            BLOOD_PRESSURE_DIASTOLIC_METRIC_KEY -> MetricMeasurement(
                timestamp = timestamp,
                value = bloodPressureRecord.diastolic.inMillimetersOfMercury,
                unitLabel = "mmHg",
                sourceField = "Diastolic"
            )
            else -> null
        }
    }

    private fun extractNutritionMetric(
        record: Record,
        timestamp: Instant,
        metricKey: String?,
        unitPreference: UnitPreference
    ): MetricMeasurement? {
        val nutritionRecord = record as? NutritionRecord ?: return null
        return when (metricKey) {
            null -> nutritionEnergyMeasurement(
                timestamp = timestamp,
                energy = nutritionRecord.energy,
                sourceField = "Energy",
                unitPreference = unitPreference
            )
            NUTRITION_ENERGY_FROM_FAT_METRIC_KEY -> nutritionEnergyMeasurement(
                timestamp = timestamp,
                energy = nutritionRecord.energyFromFat,
                sourceField = "EnergyFromFat",
                unitPreference = unitPreference
            )
            NUTRITION_PROTEIN_METRIC_KEY -> nutritionMassMeasurement(
                timestamp = timestamp,
                mass = nutritionRecord.protein,
                sourceField = "Protein",
                unitPreference = unitPreference
            )
            NUTRITION_TOTAL_CARBOHYDRATE_METRIC_KEY -> nutritionMassMeasurement(
                timestamp = timestamp,
                mass = nutritionRecord.totalCarbohydrate,
                sourceField = "TotalCarbohydrate",
                unitPreference = unitPreference
            )
            NUTRITION_TOTAL_FAT_METRIC_KEY -> nutritionMassMeasurement(
                timestamp = timestamp,
                mass = nutritionRecord.totalFat,
                sourceField = "TotalFat",
                unitPreference = unitPreference
            )
            NUTRITION_SUGAR_METRIC_KEY -> nutritionMassMeasurement(
                timestamp = timestamp,
                mass = nutritionRecord.sugar,
                sourceField = "Sugar",
                unitPreference = unitPreference
            )
            NUTRITION_DIETARY_FIBER_METRIC_KEY -> nutritionMassMeasurement(
                timestamp = timestamp,
                mass = nutritionRecord.dietaryFiber,
                sourceField = "DietaryFiber",
                unitPreference = unitPreference
            )
            else -> null
        }
    }

    private fun nutritionEnergyMeasurement(
        timestamp: Instant,
        energy: Energy?,
        sourceField: String,
        unitPreference: UnitPreference
    ): MetricMeasurement? {
        val value = energy ?: return null
        val useImperial = when (unitPreference) {
            UnitPreference.IMPERIAL -> true
            UnitPreference.METRIC -> false
            UnitPreference.AUTO -> true
        }
        return MetricMeasurement(
            timestamp = timestamp,
            value = if (useImperial) value.inKilocalories else value.inKilojoules,
            unitLabel = if (useImperial) "kilocalories" else "kilojoules",
            sourceField = sourceField
        )
    }

    private fun nutritionMassMeasurement(
        timestamp: Instant,
        mass: Mass?,
        sourceField: String,
        unitPreference: UnitPreference
    ): MetricMeasurement? {
        val value = mass ?: return null
        val useImperial = unitPreference == UnitPreference.IMPERIAL
        return MetricMeasurement(
            timestamp = timestamp,
            value = if (useImperial) value.inOunces else value.inGrams,
            unitLabel = if (useImperial) "ounces" else "grams",
            sourceField = sourceField
        )
    }

    private fun safeGetTime(record: Record, method: String): Instant? {
        return try {
            record.javaClass.getMethod(method).invoke(record) as? Instant
        } catch (exception: Exception) {
            if (exception is CancellationException) {
                throw exception
            }
            Log.v(EXTRACTION_LOG_TAG, "Unable to read $method", exception)
            null
        }
    }

    private val staticExtractors: Map<KClass<out Record>, (Record, UnitPreference, Instant) -> MetricMeasurement> =
        mapOf(
            mappingLookup(
                fieldName = "Weight",
                recordClass = WeightRecord::class,
                valueField = WeightRecord::weight,
                imperialValue = { it.inPounds },
                imperialUnit = "pounds",
                metricValue = { it.inKilograms },
                metricUnit = "kilograms"
            ),
            mappingLookup(
                fieldName = "Distance",
                recordClass = DistanceRecord::class,
                valueField = DistanceRecord::distance,
                imperialValue = { it.inMiles },
                imperialUnit = "miles",
                metricValue = { it.inKilometers },
                metricUnit = "kilometers"
            ),
            mappingLookup(
                fieldName = "Level",
                recordClass = BloodGlucoseRecord::class,
                valueField = BloodGlucoseRecord::level,
                imperialValue = { it.inMilligramsPerDeciliter },
                imperialUnit = "mg/dL",
                metricValue = { it.inMillimolesPerLiter },
                metricUnit = "mmol/L"
            ),
            mappingLookup(
                fieldName = "Energy",
                recordClass = TotalCaloriesBurnedRecord::class,
                valueField = TotalCaloriesBurnedRecord::energy,
                imperialValue = { it.inKilocalories },
                imperialUnit = "kilocalories",
                metricValue = { it.inKilojoules },
                metricUnit = "kilojoules",
                autoPreference = UnitPreference.IMPERIAL
            ),
            scalarMappingLookup(
                fieldName = "Count",
                recordClass = StepsRecord::class,
                valueField = { it.count.toDouble() },
                unitLabel = "count"
            )
        )

    private fun <T : Record, U> mappingLookup(
        fieldName: String,
        recordClass: KClass<T>,
        valueField: (T) -> U,
        imperialValue: (U) -> Double,
        imperialUnit: String,
        metricValue: (U) -> Double,
        metricUnit: String,
        autoPreference: UnitPreference = UnitPreference.METRIC
    ): Pair<KClass<out Record>, (Record, UnitPreference, Instant) -> MetricMeasurement> {
        val extractor: (Record, UnitPreference, Instant) -> MetricMeasurement = { record, preference, timestamp ->
            val typedRecord = record as T
            val value = valueField(typedRecord)
            val useImperial = when (preference) {
                UnitPreference.IMPERIAL -> true
                UnitPreference.METRIC -> false
                UnitPreference.AUTO -> autoPreference == UnitPreference.IMPERIAL
            }
            MetricMeasurement(
                timestamp = timestamp,
                value = if (useImperial) imperialValue(value) else metricValue(value),
                unitLabel = if (useImperial) imperialUnit else metricUnit,
                sourceField = fieldName
            )
        }
        return recordClass to extractor
    }

    private fun <T : Record> scalarMappingLookup(
        fieldName: String,
        recordClass: KClass<T>,
        valueField: (T) -> Double,
        unitLabel: String? = null
    ): Pair<KClass<out Record>, (Record, UnitPreference, Instant) -> MetricMeasurement> {
        val extractor: (Record, UnitPreference, Instant) -> MetricMeasurement = { record, _, timestamp ->
            MetricMeasurement(
                timestamp = timestamp,
                value = valueField(record as T),
                unitLabel = unitLabel,
                sourceField = fieldName
            )
        }
        return recordClass to extractor
    }

    private val reflectiveTypePreferencesByFqn = mapOf(
        "androidx.health.connect.client.records.ActiveCaloriesBurnedRecord" to ReflectiveTypePreference(listOf("Energy", "Calories")),
        "androidx.health.connect.client.records.BasalBodyTemperatureRecord" to ReflectiveTypePreference(listOf("Temperature")),
        "androidx.health.connect.client.records.BasalMetabolicRateRecord" to ReflectiveTypePreference(listOf("BasalMetabolicRate", "Power")),
        "androidx.health.connect.client.records.BloodGlucoseRecord" to ReflectiveTypePreference(listOf("Level", "Glucose")),
        "androidx.health.connect.client.records.BloodPressureRecord" to ReflectiveTypePreference(listOf("Systolic", "Diastolic", "Pressure")),
        "androidx.health.connect.client.records.BodyFatRecord" to ReflectiveTypePreference(listOf("Percentage", "Percent")),
        "androidx.health.connect.client.records.BodyTemperatureRecord" to ReflectiveTypePreference(listOf("Temperature")),
        "androidx.health.connect.client.records.BodyWaterMassRecord" to ReflectiveTypePreference(listOf("Mass", "Weight")),
        "androidx.health.connect.client.records.BoneMassRecord" to ReflectiveTypePreference(listOf("Mass", "Weight")),
        "androidx.health.connect.client.records.CervicalMucusRecord" to ReflectiveTypePreference(listOf("Appearance", "Sensation", "Type")),
        "androidx.health.connect.client.records.CyclingPedalingCadenceRecord" to ReflectiveTypePreference(listOf("RevolutionsPerMinute", "Cadence", "Rate", "Rpm", "Samples")),
        "androidx.health.connect.client.records.DistanceRecord" to ReflectiveTypePreference(listOf("Distance")),
        "androidx.health.connect.client.records.ElevationGainedRecord" to ReflectiveTypePreference(listOf("Elevation", "Height", "Gain")),
        "androidx.health.connect.client.records.ExerciseSessionRecord" to ReflectiveTypePreference(listOf("Duration", "ActiveTime"), fallbackToDurationMinutes = true),
        "androidx.health.connect.client.records.FloorsClimbedRecord" to ReflectiveTypePreference(listOf("Floors", "Count")),
        "androidx.health.connect.client.records.HeartRateRecord" to ReflectiveTypePreference(listOf("BeatsPerMinute", "Bpm", "Rate", "Samples")),
        "androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord" to ReflectiveTypePreference(listOf("Rmssd", "Millis", "Variability")),
        "androidx.health.connect.client.records.HeightRecord" to ReflectiveTypePreference(listOf("Height")),
        "androidx.health.connect.client.records.HydrationRecord" to ReflectiveTypePreference(listOf("Volume", "Hydration")),
        "androidx.health.connect.client.records.IntermenstrualBleedingRecord" to ReflectiveTypePreference(listOf("Type", "Flow", "Amount")),
        "androidx.health.connect.client.records.LeanBodyMassRecord" to ReflectiveTypePreference(listOf("Mass", "Weight")),
        "androidx.health.connect.client.records.MenstruationFlowRecord" to ReflectiveTypePreference(listOf("Flow", "Type", "Severity")),
        "androidx.health.connect.client.records.MenstruationPeriodRecord" to ReflectiveTypePreference(listOf("Duration", "Length"), fallbackToDurationMinutes = true),
        "androidx.health.connect.client.records.NutritionRecord" to ReflectiveTypePreference(listOf("Energy", "Calories", "Protein", "Carbohydrate", "Fat", "Sugar")),
        "androidx.health.connect.client.records.OvulationTestRecord" to ReflectiveTypePreference(listOf("Result", "Type")),
        "androidx.health.connect.client.records.OxygenSaturationRecord" to ReflectiveTypePreference(listOf("Percentage", "Saturation", "Percent")),
        "androidx.health.connect.client.records.PowerRecord" to ReflectiveTypePreference(listOf("Power", "Watts", "Samples")),
        "androidx.health.connect.client.records.RespiratoryRateRecord" to ReflectiveTypePreference(listOf("Rate", "Respiratory", "Breaths")),
        "androidx.health.connect.client.records.RestingHeartRateRecord" to ReflectiveTypePreference(listOf("BeatsPerMinute", "Bpm", "Rate")),
        "androidx.health.connect.client.records.SexualActivityRecord" to ReflectiveTypePreference(listOf("ProtectionUsed", "Type", "Result")),
        "androidx.health.connect.client.records.SleepSessionRecord" to ReflectiveTypePreference(listOf("Duration", "Sleep"), fallbackToDurationMinutes = true),
        "androidx.health.connect.client.records.SpeedRecord" to ReflectiveTypePreference(listOf("Speed", "MetersPerSecond", "Samples")),
        "androidx.health.connect.client.records.StepsCadenceRecord" to ReflectiveTypePreference(listOf("Rate", "Cadence", "StepsPerMinute", "Samples")),
        "androidx.health.connect.client.records.StepsRecord" to ReflectiveTypePreference(listOf("Count", "Steps")),
        "androidx.health.connect.client.records.TotalCaloriesBurnedRecord" to ReflectiveTypePreference(listOf("Energy", "Calories")),
        "androidx.health.connect.client.records.Vo2MaxRecord" to ReflectiveTypePreference(listOf("Vo2", "MillilitersPerMinuteKilogram", "MlPerMinPerKg")),
        "androidx.health.connect.client.records.WeightRecord" to ReflectiveTypePreference(listOf("Weight", "Mass")),
        "androidx.health.connect.client.records.WheelchairPushesRecord" to ReflectiveTypePreference(listOf("Count", "Pushes"))
    )

    private data class CandidateMeasurement(
        val value: Double,
        val unitLabel: String?,
        val score: Int,
        val sourceField: String
    )

    private data class ParsedMeasurement(
        val value: Double,
        val unitLabel: String?
    )

    private data class ReflectiveTypePreference(
        val preferredFields: List<String>,
        val fallbackToDurationMinutes: Boolean = false
    )

    private fun unitScore(rawUnit: String, unitPreference: UnitPreference, value: Double): Int {
        val unit = rawUnit.lowercase()
        val imperial = listOf(
            "mile", "foot", "feet", "inch", "yard",
            "pound", "ounce", "fahrenheit", "fluidounce", "gallon", "cup", "calorie",
            "deciliter"
        )
        val metric = listOf(
            "meter", "metre", "kilometer", "centimeter", "millimeter",
            "gram", "kilogram", "celsius", "joule", "liter", "litre", "milliliter",
            "mole", "millimole"
        )
        val base = when {
            imperial.any(unit::contains) -> 2
            metric.any(unit::contains) -> 2
            else -> 1
        }
        val preferenceBoost = when (unitPreference) {
            UnitPreference.AUTO -> 0
            UnitPreference.METRIC -> if (metric.any(unit::contains)) 8 else 0
            UnitPreference.IMPERIAL -> if (imperial.any(unit::contains)) 8 else 0
        }
        val concentrationBoost = when {
            unit.contains("millimolesperliter") -> when (unitPreference) {
                UnitPreference.IMPERIAL -> 2
                else -> 8
            }
            unit.contains("milligramsperdeciliter") -> when (unitPreference) {
                UnitPreference.METRIC -> 2
                else -> 8
            }
            unit.contains("molesperliter") -> -8
            unit.contains("gramsperliter") -> -3
            else -> 0
        }
        val magnitudePenalty = when {
            value == 0.0 -> 0
            kotlin.math.abs(value) < 0.01 -> -8
            kotlin.math.abs(value) < 0.1 -> -4
            kotlin.math.abs(value) > 1_000_000 -> -4
            else -> 0
        }
        return base + preferenceBoost + concentrationBoost + magnitudePenalty
    }

    private fun fieldScore(rawField: String): Int {
        val field = rawField.lowercase()
        val highSignal = listOf(
            "value", "count", "energy", "distance", "mass",
            "temperature", "rate", "speed", "power", "vo2",
            "systolic", "diastolic", "glucose", "saturation", "cadence", "level"
        )
        val lowSignal = listOf(
            "meal", "relation", "source", "specimen", "type", "status"
        )
        return when {
            highSignal.any(field::contains) -> 4
            lowSignal.any(field::contains) -> -8
            field in setOf("time", "starttime", "endtime", "metadata") -> -10
            else -> 0
        }
    }

    private fun preferredFieldScore(
        rawField: String,
        preference: ReflectiveTypePreference?
    ): Int {
        if (preference == null) return 0
        val field = rawField.lowercase()
        return if (preference.preferredFields.any { token -> field.contains(token.lowercase()) }) 18 else 0
    }

    private fun normalizeUnitLabel(rawUnit: String): String {
        val spaced = rawUnit.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        return spaced.lowercase()
    }

    private fun parseMeasurementFromString(text: String): ParsedMeasurement? {
        val match = Regex("""^\s*([-+]?\d+(?:\.\d+)?)\s*([^\d].+)?\s*$""").find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2)?.trim().orEmpty().ifBlank { null }
        return ParsedMeasurement(value = value, unitLabel = unit)
    }

    private fun isNumericType(type: Class<*>): Boolean {
        return Number::class.java.isAssignableFrom(type) ||
            type == java.lang.Double.TYPE ||
            type == java.lang.Float.TYPE ||
            type == java.lang.Integer.TYPE ||
            type == java.lang.Long.TYPE ||
            type == java.lang.Short.TYPE ||
            type == java.lang.Byte.TYPE
    }

    private fun logMeasurementChoice(
        record: Record,
        unitPreference: UnitPreference,
        best: CandidateMeasurement
    ) {
        val cls = record::class.qualifiedName ?: record::class.simpleName ?: "Record"
        if (!cls.contains("bloodglucose", ignoreCase = true)) return
        Log.d(
            EXTRACTION_LOG_TAG,
            "record=$cls field=${best.sourceField} unit=${best.unitLabel ?: "none"} value=${best.value} pref=$unitPreference score=${best.score}"
        )
    }

    companion object {
        const val SLEEP_STAGE_METRIC_KEY = "sleep_stage"
        const val BLOOD_PRESSURE_DIASTOLIC_METRIC_KEY = "blood_pressure_diastolic"
        const val NUTRITION_ENERGY_FROM_FAT_METRIC_KEY = "nutrition_energy_from_fat"
        const val NUTRITION_PROTEIN_METRIC_KEY = "nutrition_protein"
        const val NUTRITION_TOTAL_CARBOHYDRATE_METRIC_KEY = "nutrition_total_carbohydrate"
        const val NUTRITION_TOTAL_FAT_METRIC_KEY = "nutrition_total_fat"
        const val NUTRITION_SUGAR_METRIC_KEY = "nutrition_sugar"
        const val NUTRITION_DIETARY_FIBER_METRIC_KEY = "nutrition_dietary_fiber"
        private const val EXTRACTION_LOG_TAG = "HealthDisconnectExtract"
    }
}

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
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.monkopedia.healthdisconnect.model.UnitPreference
import java.time.Instant
import java.time.temporal.ChronoUnit
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

enum class NutrientUnit(val label: String, val valueOf: (Mass) -> Double) {
    GRAMS("grams", Mass::inGrams),
    MILLIGRAMS("milligrams", Mass::inMilligrams),
    MICROGRAMS("micrograms", Mass::inMicrograms)
}

/**
 * Single source of truth for the Mass-typed nutrients carried by [NutritionRecord].
 * Drives the chart-metric list, the chart extractor, and the Entry Details popup so the
 * three surfaces never drift. Energy / EnergyFromFat are handled separately (Energy-typed).
 *
 * [metricKey] values are persisted in saved views, so they must remain stable.
 */
data class NutritionNutrient(
    val metricKey: String,
    val sourceField: String,
    val displayName: String,
    val unit: NutrientUnit,
    val select: (NutritionRecord) -> Mass?
)

val NUTRITION_NUTRIENTS: List<NutritionNutrient> = listOf(
    // Macronutrients (grams) — order preserves the original metric list.
    NutritionNutrient("nutrition_protein", "Protein", "Protein", NutrientUnit.GRAMS) { it.protein },
    NutritionNutrient("nutrition_total_carbohydrate", "TotalCarbohydrate", "Total Carbohydrate", NutrientUnit.GRAMS) { it.totalCarbohydrate },
    NutritionNutrient("nutrition_total_fat", "TotalFat", "Total Fat", NutrientUnit.GRAMS) { it.totalFat },
    NutritionNutrient("nutrition_sugar", "Sugar", "Sugar", NutrientUnit.GRAMS) { it.sugar },
    NutritionNutrient("nutrition_dietary_fiber", "DietaryFiber", "Dietary Fiber", NutrientUnit.GRAMS) { it.dietaryFiber },
    NutritionNutrient("nutrition_saturated_fat", "SaturatedFat", "Saturated Fat", NutrientUnit.GRAMS) { it.saturatedFat },
    NutritionNutrient("nutrition_monounsaturated_fat", "MonounsaturatedFat", "Monounsaturated Fat", NutrientUnit.GRAMS) { it.monounsaturatedFat },
    NutritionNutrient("nutrition_polyunsaturated_fat", "PolyunsaturatedFat", "Polyunsaturated Fat", NutrientUnit.GRAMS) { it.polyunsaturatedFat },
    NutritionNutrient("nutrition_trans_fat", "TransFat", "Trans Fat", NutrientUnit.GRAMS) { it.transFat },
    NutritionNutrient("nutrition_unsaturated_fat", "UnsaturatedFat", "Unsaturated Fat", NutrientUnit.GRAMS) { it.unsaturatedFat },
    // Stimulants / sterols / minerals (milligrams).
    NutritionNutrient("nutrition_caffeine", "Caffeine", "Caffeine", NutrientUnit.MILLIGRAMS) { it.caffeine },
    NutritionNutrient("nutrition_cholesterol", "Cholesterol", "Cholesterol", NutrientUnit.MILLIGRAMS) { it.cholesterol },
    NutritionNutrient("nutrition_sodium", "Sodium", "Sodium", NutrientUnit.MILLIGRAMS) { it.sodium },
    NutritionNutrient("nutrition_potassium", "Potassium", "Potassium", NutrientUnit.MILLIGRAMS) { it.potassium },
    NutritionNutrient("nutrition_calcium", "Calcium", "Calcium", NutrientUnit.MILLIGRAMS) { it.calcium },
    NutritionNutrient("nutrition_chloride", "Chloride", "Chloride", NutrientUnit.MILLIGRAMS) { it.chloride },
    NutritionNutrient("nutrition_iron", "Iron", "Iron", NutrientUnit.MILLIGRAMS) { it.iron },
    NutritionNutrient("nutrition_magnesium", "Magnesium", "Magnesium", NutrientUnit.MILLIGRAMS) { it.magnesium },
    NutritionNutrient("nutrition_phosphorus", "Phosphorus", "Phosphorus", NutrientUnit.MILLIGRAMS) { it.phosphorus },
    NutritionNutrient("nutrition_zinc", "Zinc", "Zinc", NutrientUnit.MILLIGRAMS) { it.zinc },
    NutritionNutrient("nutrition_copper", "Copper", "Copper", NutrientUnit.MILLIGRAMS) { it.copper },
    NutritionNutrient("nutrition_manganese", "Manganese", "Manganese", NutrientUnit.MILLIGRAMS) { it.manganese },
    // Water-soluble vitamins commonly reported in milligrams.
    NutritionNutrient("nutrition_vitamin_c", "VitaminC", "Vitamin C", NutrientUnit.MILLIGRAMS) { it.vitaminC },
    NutritionNutrient("nutrition_vitamin_e", "VitaminE", "Vitamin E", NutrientUnit.MILLIGRAMS) { it.vitaminE },
    NutritionNutrient("nutrition_thiamin", "Thiamin", "Thiamin", NutrientUnit.MILLIGRAMS) { it.thiamin },
    NutritionNutrient("nutrition_riboflavin", "Riboflavin", "Riboflavin", NutrientUnit.MILLIGRAMS) { it.riboflavin },
    NutritionNutrient("nutrition_niacin", "Niacin", "Niacin", NutrientUnit.MILLIGRAMS) { it.niacin },
    NutritionNutrient("nutrition_pantothenic_acid", "PantothenicAcid", "Pantothenic Acid", NutrientUnit.MILLIGRAMS) { it.pantothenicAcid },
    NutritionNutrient("nutrition_vitamin_b6", "VitaminB6", "Vitamin B6", NutrientUnit.MILLIGRAMS) { it.vitaminB6 },
    // Trace elements / fat-soluble vitamins reported in micrograms.
    NutritionNutrient("nutrition_selenium", "Selenium", "Selenium", NutrientUnit.MICROGRAMS) { it.selenium },
    NutritionNutrient("nutrition_chromium", "Chromium", "Chromium", NutrientUnit.MICROGRAMS) { it.chromium },
    NutritionNutrient("nutrition_molybdenum", "Molybdenum", "Molybdenum", NutrientUnit.MICROGRAMS) { it.molybdenum },
    NutritionNutrient("nutrition_iodine", "Iodine", "Iodine", NutrientUnit.MICROGRAMS) { it.iodine },
    NutritionNutrient("nutrition_vitamin_a", "VitaminA", "Vitamin A", NutrientUnit.MICROGRAMS) { it.vitaminA },
    NutritionNutrient("nutrition_vitamin_d", "VitaminD", "Vitamin D", NutrientUnit.MICROGRAMS) { it.vitaminD },
    NutritionNutrient("nutrition_vitamin_k", "VitaminK", "Vitamin K", NutrientUnit.MICROGRAMS) { it.vitaminK },
    NutritionNutrient("nutrition_vitamin_b12", "VitaminB12", "Vitamin B12", NutrientUnit.MICROGRAMS) { it.vitaminB12 },
    NutritionNutrient("nutrition_folate", "Folate", "Folate", NutrientUnit.MICROGRAMS) { it.folate },
    NutritionNutrient("nutrition_folic_acid", "FolicAcid", "Folic Acid", NutrientUnit.MICROGRAMS) { it.folicAcid },
    NutritionNutrient("nutrition_biotin", "Biotin", "Biotin", NutrientUnit.MICROGRAMS) { it.biotin }
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
        extractSleepSessionMetric(record, timestamp, metricKey)?.let { return it }
        extractBloodPressureMetric(record, timestamp, metricKey)?.let { return it }
        extractNutritionMetric(record, timestamp, metricKey, unitPreference)?.let { return it }
        return staticExtractors[record::class]?.invoke(record, unitPreference, timestamp)
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
            NutritionRecord::class -> buildList {
                add(ExtractableMetric(key = null, label = "Nutrition Energy"))
                add(
                    ExtractableMetric(
                        key = NUTRITION_ENERGY_FROM_FAT_METRIC_KEY,
                        label = "Nutrition Energy From Fat"
                    )
                )
                NUTRITION_NUTRIENTS.forEach { nutrient ->
                    add(
                        ExtractableMetric(
                            key = nutrient.metricKey,
                            label = "Nutrition ${nutrient.displayName}"
                        )
                    )
                }
            }
            else -> listOf(ExtractableMetric())
        }
    }

    override fun recordTimestamp(record: Record): Instant? {
        return com.monkopedia.healthdisconnect.recordTimestamp(record)
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
            else -> NUTRITION_NUTRIENTS.firstOrNull { it.metricKey == metricKey }?.let { nutrient ->
                nutritionMassMeasurement(
                    timestamp = timestamp,
                    mass = nutrient.select(nutritionRecord),
                    sourceField = nutrient.sourceField,
                    unit = nutrient.unit,
                    unitPreference = unitPreference
                )
            }
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
        unit: NutrientUnit,
        unitPreference: UnitPreference
    ): MetricMeasurement? {
        val value = mass ?: return null
        // Macronutrients (grams) honor the imperial preference (ounces); micronutrients
        // stay in their natural mg/mcg unit — nobody charts caffeine in ounces.
        if (unit == NutrientUnit.GRAMS && unitPreference == UnitPreference.IMPERIAL) {
            return MetricMeasurement(
                timestamp = timestamp,
                value = value.inOunces,
                unitLabel = "ounces",
                sourceField = sourceField
            )
        }
        return MetricMeasurement(
            timestamp = timestamp,
            value = unit.valueOf(value),
            unitLabel = unit.label,
            sourceField = sourceField
        )
    }

    private fun durationMinutes(record: Record, timestamp: Instant): MetricMeasurement? {
        val start = recordTimestamp(record) ?: return null
        val end = when (record) {
            is ExerciseSessionRecord -> record.endTime
            is MenstruationPeriodRecord -> record.endTime
            is SleepSessionRecord -> record.endTime
            else -> return null
        }
        if (end.isBefore(start)) return null
        val minutes = ChronoUnit.SECONDS.between(start, end).toDouble() / 60.0
        return MetricMeasurement(
            timestamp = timestamp,
            value = minutes,
            unitLabel = "minutes",
            sourceField = "Duration"
        )
    }

    @Suppress("UNCHECKED_CAST")
    private val staticExtractors: Map<KClass<out Record>, (Record, UnitPreference, Instant) -> MetricMeasurement?> =
        mapOf(
            // --- Unit-based mappings ---
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
            mappingLookup(
                fieldName = "Energy",
                recordClass = ActiveCaloriesBurnedRecord::class,
                valueField = ActiveCaloriesBurnedRecord::energy,
                imperialValue = { it.inKilocalories },
                imperialUnit = "kilocalories",
                metricValue = { it.inKilojoules },
                metricUnit = "kilojoules",
                autoPreference = UnitPreference.IMPERIAL
            ),
            mappingLookup(
                fieldName = "Temperature",
                recordClass = BasalBodyTemperatureRecord::class,
                valueField = BasalBodyTemperatureRecord::temperature,
                imperialValue = { it.inFahrenheit },
                imperialUnit = "fahrenheit",
                metricValue = { it.inCelsius },
                metricUnit = "celsius"
            ),
            mappingLookup(
                fieldName = "Temperature",
                recordClass = BodyTemperatureRecord::class,
                valueField = BodyTemperatureRecord::temperature,
                imperialValue = { it.inFahrenheit },
                imperialUnit = "fahrenheit",
                metricValue = { it.inCelsius },
                metricUnit = "celsius"
            ),
            mappingLookup(
                fieldName = "BasalMetabolicRate",
                recordClass = BasalMetabolicRateRecord::class,
                valueField = BasalMetabolicRateRecord::basalMetabolicRate,
                imperialValue = { it.inKilocaloriesPerDay },
                imperialUnit = "kilocalories/day",
                metricValue = { it.inWatts },
                metricUnit = "watts"
            ),
            mappingLookup(
                fieldName = "Mass",
                recordClass = BodyWaterMassRecord::class,
                valueField = BodyWaterMassRecord::mass,
                imperialValue = { it.inPounds },
                imperialUnit = "pounds",
                metricValue = { it.inKilograms },
                metricUnit = "kilograms"
            ),
            mappingLookup(
                fieldName = "Mass",
                recordClass = BoneMassRecord::class,
                valueField = BoneMassRecord::mass,
                imperialValue = { it.inPounds },
                imperialUnit = "pounds",
                metricValue = { it.inKilograms },
                metricUnit = "kilograms"
            ),
            mappingLookup(
                fieldName = "Mass",
                recordClass = LeanBodyMassRecord::class,
                valueField = LeanBodyMassRecord::mass,
                imperialValue = { it.inPounds },
                imperialUnit = "pounds",
                metricValue = { it.inKilograms },
                metricUnit = "kilograms"
            ),
            mappingLookup(
                fieldName = "Height",
                recordClass = HeightRecord::class,
                valueField = HeightRecord::height,
                imperialValue = { it.inInches },
                imperialUnit = "inches",
                metricValue = { it.inMeters * 100.0 },
                metricUnit = "centimeters"
            ),
            mappingLookup(
                fieldName = "Elevation",
                recordClass = ElevationGainedRecord::class,
                valueField = ElevationGainedRecord::elevation,
                imperialValue = { it.inFeet },
                imperialUnit = "feet",
                metricValue = { it.inMeters },
                metricUnit = "meters"
            ),
            mappingLookup(
                fieldName = "Volume",
                recordClass = HydrationRecord::class,
                valueField = HydrationRecord::volume,
                imperialValue = { it.inFluidOuncesUs },
                imperialUnit = "fl oz",
                metricValue = { it.inLiters },
                metricUnit = "liters"
            ),
            // --- Scalar mappings ---
            scalarMappingLookup(
                fieldName = "Count",
                recordClass = StepsRecord::class,
                valueField = { it.count.toDouble() },
                unitLabel = "count"
            ),
            scalarMappingLookup(
                fieldName = "Percentage",
                recordClass = BodyFatRecord::class,
                valueField = { it.percentage.value },
                unitLabel = "%"
            ),
            scalarMappingLookup(
                fieldName = "Percentage",
                recordClass = OxygenSaturationRecord::class,
                valueField = { it.percentage.value },
                unitLabel = "%"
            ),
            scalarMappingLookup(
                fieldName = "Floors",
                recordClass = FloorsClimbedRecord::class,
                valueField = { it.floors.toDouble() },
                unitLabel = "floors"
            ),
            scalarMappingLookup(
                fieldName = "BeatsPerMinute",
                recordClass = RestingHeartRateRecord::class,
                valueField = { it.beatsPerMinute.toDouble() },
                unitLabel = "bpm"
            ),
            scalarMappingLookup(
                fieldName = "Rate",
                recordClass = RespiratoryRateRecord::class,
                valueField = { it.rate },
                unitLabel = "breaths/min"
            ),
            scalarMappingLookup(
                fieldName = "Vo2",
                recordClass = Vo2MaxRecord::class,
                valueField = { it.vo2MillilitersPerMinuteKilogram },
                unitLabel = "mL/min/kg"
            ),
            scalarMappingLookup(
                fieldName = "HeartRateVariabilityMillis",
                recordClass = HeartRateVariabilityRmssdRecord::class,
                valueField = { it.heartRateVariabilityMillis },
                unitLabel = "ms"
            ),
            scalarMappingLookup(
                fieldName = "Count",
                recordClass = WheelchairPushesRecord::class,
                valueField = { it.count.toDouble() },
                unitLabel = "count"
            ),
            // --- Sample-based records ---
            HeartRateRecord::class to { record, _, timestamp ->
                val hr = record as HeartRateRecord
                if (hr.samples.isEmpty()) null
                else MetricMeasurement(
                    timestamp = timestamp,
                    value = hr.samples.map { it.beatsPerMinute.toDouble() }.average(),
                    unitLabel = "bpm",
                    sourceField = "Samples.BeatsPerMinute"
                )
            },
            SpeedRecord::class to { record, preference, timestamp ->
                val speed = record as SpeedRecord
                if (speed.samples.isEmpty()) null
                else {
                    val useImperial = preference == UnitPreference.IMPERIAL
                    val avg = speed.samples.map {
                        if (useImperial) it.speed.inMilesPerHour else it.speed.inKilometersPerHour
                    }.average()
                    MetricMeasurement(
                        timestamp = timestamp,
                        value = avg,
                        unitLabel = if (useImperial) "mph" else "km/h",
                        sourceField = "Samples.Speed"
                    )
                }
            },
            PowerRecord::class to { record, _, timestamp ->
                val power = record as PowerRecord
                if (power.samples.isEmpty()) null
                else MetricMeasurement(
                    timestamp = timestamp,
                    value = power.samples.map { it.power.inWatts }.average(),
                    unitLabel = "watts",
                    sourceField = "Samples.Power"
                )
            },
            StepsCadenceRecord::class to { record, _, timestamp ->
                val cadence = record as StepsCadenceRecord
                if (cadence.samples.isEmpty()) null
                else MetricMeasurement(
                    timestamp = timestamp,
                    value = cadence.samples.map { it.rate }.average(),
                    unitLabel = "steps/min",
                    sourceField = "Samples.Rate"
                )
            },
            CyclingPedalingCadenceRecord::class to { record, _, timestamp ->
                val cadence = record as CyclingPedalingCadenceRecord
                if (cadence.samples.isEmpty()) null
                else MetricMeasurement(
                    timestamp = timestamp,
                    value = cadence.samples.map { it.revolutionsPerMinute }.average(),
                    unitLabel = "rpm",
                    sourceField = "Samples.RevolutionsPerMinute"
                )
            },
            // --- Duration-based records ---
            ExerciseSessionRecord::class to { record, _, timestamp ->
                durationMinutes(record, timestamp)
            },
            MenstruationPeriodRecord::class to { record, _, timestamp ->
                durationMinutes(record, timestamp)
            }
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
    ): Pair<KClass<out Record>, (Record, UnitPreference, Instant) -> MetricMeasurement?> {
        val extractor: (Record, UnitPreference, Instant) -> MetricMeasurement = { record, preference, timestamp ->
            @Suppress("UNCHECKED_CAST")
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
    ): Pair<KClass<out Record>, (Record, UnitPreference, Instant) -> MetricMeasurement?> {
        val extractor: (Record, UnitPreference, Instant) -> MetricMeasurement = { record, _, timestamp ->
            @Suppress("UNCHECKED_CAST")
            MetricMeasurement(
                timestamp = timestamp,
                value = valueField(record as T),
                unitLabel = unitLabel,
                sourceField = fieldName
            )
        }
        return recordClass to extractor
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
    }
}

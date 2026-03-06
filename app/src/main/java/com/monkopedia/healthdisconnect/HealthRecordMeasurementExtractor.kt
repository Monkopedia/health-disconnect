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

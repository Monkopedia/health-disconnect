package com.monkopedia.healthdisconnect

/**
 * Recovery table mapping legacy R8-obfuscated Health Connect record-class names to their real
 * fully-qualified names.
 *
 * Historical releases (v1.0–v1.2.0) shipped with minification on and no keep rules, so the app
 * persisted each metric by its *obfuscated* class name (RecordSelection.fqn = KClass.qualifiedName).
 * R8 reassigns those names when the class set changes, so a view saved under one build stopped
 * resolving after an update that reshuffled the mapping (issue #56 — e.g. WeightRecord was "S1.z0"
 * through v1.1.1, then "i5.y1" in v1.2.0). v1.2.1 added a keep rule so the names are now stable and
 * real, but views saved *before* v1.2.1 still hold a stale obfuscated name.
 *
 * This table (generated from the R8 mappings of every past release; 2 distinct obfuscation eras,
 * no ambiguous names) lets [PermissionsViewModel.classForFqn] translate a stale obfuscated name
 * back to the real class, so those views auto-recover instead of showing "metric unavailable".
 * Do not edit by hand — regenerate from the historical release mappings if ever needed.
 */
internal object LegacyFqnRecovery {
    private val OBFUSCATED_TO_REAL: Map<String, String> = mapOf(
    "S1.A0" to "androidx.health.connect.client.records.WheelchairPushesRecord",
    "S1.H" to "androidx.health.connect.client.records.ExercisePerformanceTarget",
    "S1.J" to "androidx.health.connect.client.records.ExerciseRoute",
    "S1.N" to "androidx.health.connect.client.records.ExerciseSegment",
    "S1.O" to "androidx.health.connect.client.records.ExerciseSessionRecord",
    "S1.P" to "androidx.health.connect.client.records.FloorsClimbedRecord",
    "S1.S" to "androidx.health.connect.client.records.HeartRateRecord",
    "S1.T" to "androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord",
    "S1.U" to "androidx.health.connect.client.records.HeightRecord",
    "S1.V" to "androidx.health.connect.client.records.HydrationRecord",
    "S1.W" to "androidx.health.connect.client.records.IntermenstrualBleedingRecord",
    "S1.X" to "androidx.health.connect.client.records.LeanBodyMassRecord",
    "S1.Y" to "androidx.health.connect.client.records.MealType",
    "S1.Z" to "androidx.health.connect.client.records.MenstruationFlowRecord",
    "S1.a" to "androidx.health.connect.client.records.ActiveCaloriesBurnedRecord",
    "S1.a0" to "androidx.health.connect.client.records.MenstruationPeriodRecord",
    "S1.b" to "androidx.health.connect.client.records.BasalBodyTemperatureRecord",
    "S1.b0" to "androidx.health.connect.client.records.MindfulnessSessionRecord",
    "S1.c" to "androidx.health.connect.client.records.BasalMetabolicRateRecord",
    "S1.c0" to "androidx.health.connect.client.records.NutritionRecord",
    "S1.d" to "androidx.health.connect.client.records.BloodGlucoseRecord",
    "S1.d0" to "androidx.health.connect.client.records.OvulationTestRecord",
    "S1.e" to "androidx.health.connect.client.records.BloodPressureRecord",
    "S1.e0" to "androidx.health.connect.client.records.OxygenSaturationRecord",
    "S1.f" to "androidx.health.connect.client.records.BodyFatRecord",
    "S1.f0" to "androidx.health.connect.client.records.PlannedExerciseBlock",
    "S1.g" to "androidx.health.connect.client.records.BodyTemperatureMeasurementLocation",
    "S1.g0" to "androidx.health.connect.client.records.PlannedExerciseSessionRecord",
    "S1.h" to "androidx.health.connect.client.records.BodyTemperatureRecord",
    "S1.h0" to "androidx.health.connect.client.records.PlannedExerciseStep",
    "S1.i" to "androidx.health.connect.client.records.BodyWaterMassRecord",
    "S1.j" to "androidx.health.connect.client.records.BoneMassRecord",
    "S1.j0" to "androidx.health.connect.client.records.PowerRecord",
    "S1.k" to "androidx.health.connect.client.records.CervicalMucusRecord",
    "S1.k0" to "androidx.health.connect.client.records.Record",
    "S1.l0" to "androidx.health.connect.client.records.RespiratoryRateRecord",
    "S1.m" to "androidx.health.connect.client.records.CyclingPedalingCadenceRecord",
    "S1.m0" to "androidx.health.connect.client.records.RestingHeartRateRecord",
    "S1.n" to "androidx.health.connect.client.records.DistanceRecord",
    "S1.n0" to "androidx.health.connect.client.records.SexualActivityRecord",
    "S1.o" to "androidx.health.connect.client.records.ElevationGainedRecord",
    "S1.p0" to "androidx.health.connect.client.records.SkinTemperatureRecord",
    "S1.r0" to "androidx.health.connect.client.records.SleepSessionRecord",
    "S1.t0" to "androidx.health.connect.client.records.SpeedRecord",
    "S1.v0" to "androidx.health.connect.client.records.StepsCadenceRecord",
    "S1.w0" to "androidx.health.connect.client.records.StepsRecord",
    "S1.x0" to "androidx.health.connect.client.records.TotalCaloriesBurnedRecord",
    "S1.y" to "androidx.health.connect.client.records.ExerciseLap",
    "S1.y0" to "androidx.health.connect.client.records.Vo2MaxRecord",
    "S1.z0" to "androidx.health.connect.client.records.WeightRecord",
    "i5.a" to "androidx.health.connect.client.records.ActiveCaloriesBurnedRecord",
    "i5.a1" to "androidx.health.connect.client.records.MindfulnessSessionRecord",
    "i5.b" to "androidx.health.connect.client.records.BasalBodyTemperatureRecord",
    "i5.b1" to "androidx.health.connect.client.records.NutritionRecord",
    "i5.c" to "androidx.health.connect.client.records.BasalMetabolicRateRecord",
    "i5.c1" to "androidx.health.connect.client.records.OvulationTestRecord",
    "i5.d" to "androidx.health.connect.client.records.BloodGlucoseRecord",
    "i5.d1" to "androidx.health.connect.client.records.OxygenSaturationRecord",
    "i5.e" to "androidx.health.connect.client.records.BloodPressureRecord",
    "i5.e1" to "androidx.health.connect.client.records.PlannedExerciseBlock",
    "i5.f" to "androidx.health.connect.client.records.BodyFatRecord",
    "i5.f1" to "androidx.health.connect.client.records.PlannedExerciseSessionRecord",
    "i5.g" to "androidx.health.connect.client.records.BodyTemperatureMeasurementLocation",
    "i5.g1" to "androidx.health.connect.client.records.PlannedExerciseStep",
    "i5.h" to "androidx.health.connect.client.records.BodyTemperatureRecord",
    "i5.h0" to "androidx.health.connect.client.records.ExercisePerformanceTarget",
    "i5.i" to "androidx.health.connect.client.records.BodyWaterMassRecord",
    "i5.i1" to "androidx.health.connect.client.records.PowerRecord",
    "i5.j" to "androidx.health.connect.client.records.BoneMassRecord",
    "i5.j0" to "androidx.health.connect.client.records.ExerciseRoute",
    "i5.j1" to "androidx.health.connect.client.records.Record",
    "i5.k" to "androidx.health.connect.client.records.CervicalMucusRecord",
    "i5.k1" to "androidx.health.connect.client.records.RespiratoryRateRecord",
    "i5.l1" to "androidx.health.connect.client.records.RestingHeartRateRecord",
    "i5.m" to "androidx.health.connect.client.records.CyclingPedalingCadenceRecord",
    "i5.m1" to "androidx.health.connect.client.records.SexualActivityRecord",
    "i5.n" to "androidx.health.connect.client.records.DistanceRecord",
    "i5.n0" to "androidx.health.connect.client.records.ExerciseSegment",
    "i5.o" to "androidx.health.connect.client.records.ElevationGainedRecord",
    "i5.o0" to "androidx.health.connect.client.records.ExerciseSessionRecord",
    "i5.o1" to "androidx.health.connect.client.records.SkinTemperatureRecord",
    "i5.p0" to "androidx.health.connect.client.records.FloorsClimbedRecord",
    "i5.q1" to "androidx.health.connect.client.records.SleepSessionRecord",
    "i5.r0" to "androidx.health.connect.client.records.HeartRateRecord",
    "i5.s0" to "androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord",
    "i5.s1" to "androidx.health.connect.client.records.SpeedRecord",
    "i5.t0" to "androidx.health.connect.client.records.HeightRecord",
    "i5.u0" to "androidx.health.connect.client.records.HydrationRecord",
    "i5.u1" to "androidx.health.connect.client.records.StepsCadenceRecord",
    "i5.v0" to "androidx.health.connect.client.records.IntermenstrualBleedingRecord",
    "i5.v1" to "androidx.health.connect.client.records.StepsRecord",
    "i5.w0" to "androidx.health.connect.client.records.LeanBodyMassRecord",
    "i5.w1" to "androidx.health.connect.client.records.TotalCaloriesBurnedRecord",
    "i5.x0" to "androidx.health.connect.client.records.MealType",
    "i5.x1" to "androidx.health.connect.client.records.Vo2MaxRecord",
    "i5.y" to "androidx.health.connect.client.records.ExerciseLap",
    "i5.y0" to "androidx.health.connect.client.records.MenstruationFlowRecord",
    "i5.y1" to "androidx.health.connect.client.records.WeightRecord",
    "i5.z0" to "androidx.health.connect.client.records.MenstruationPeriodRecord",
    "i5.z1" to "androidx.health.connect.client.records.WheelchairPushesRecord",
    )

    /** The real record-class fqn for a legacy obfuscated [fqn], or null if it isn't a known one. */
    fun realFqn(fqn: String?): String? = if (fqn.isNullOrEmpty()) null else OBFUSCATED_TO_REAL[fqn]

    /** [fqn] recovered to its real name if it's a known legacy obfuscated name, else [fqn] unchanged. */
    fun normalize(fqn: String): String = OBFUSCATED_TO_REAL[fqn] ?: fqn
}

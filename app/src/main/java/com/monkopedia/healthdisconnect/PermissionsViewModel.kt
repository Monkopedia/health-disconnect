package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.CardDefaults
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
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
import androidx.health.connect.client.records.ExerciseLap
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
import androidx.lifecycle.AndroidViewModel

class PermissionsViewModel(context: Application) : AndroidViewModel(context) {

    private val providerPackageName = "com.google.android.apps.healthdata"
    val availabilityStatus: Int
        get() =
            HealthConnectClient.getSdkStatus(getApplication(), providerPackageName)

    fun launchUpdate() {
        val uriString =
            "market://details?id=$providerPackageName&url=healthconnect%3A%2F%2Fonboarding"
        val context = getApplication<Application>()
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.android.vending")
                data = Uri.parse(uriString)
                putExtra("overlay", true)
                putExtra("callerId", context.packageName)
            }
        )
    }


    val healthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }


    companion object {
        val PERMISSIONS =
            setOf(
                HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(BasalBodyTemperatureRecord::class),
                HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
                HealthPermission.getReadPermission(BloodGlucoseRecord::class),
                HealthPermission.getReadPermission(BloodPressureRecord::class),
                HealthPermission.getReadPermission(BodyFatRecord::class),
                HealthPermission.getReadPermission(BodyTemperatureRecord::class),
                HealthPermission.getReadPermission(BodyWaterMassRecord::class),
                HealthPermission.getReadPermission(BoneMassRecord::class),
                HealthPermission.getReadPermission(CervicalMucusRecord::class),
                HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class),
                HealthPermission.getReadPermission(DistanceRecord::class),
                HealthPermission.getReadPermission(ElevationGainedRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getReadPermission(FloorsClimbedRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
                HealthPermission.getReadPermission(HeightRecord::class),
                HealthPermission.getReadPermission(HydrationRecord::class),
                HealthPermission.getReadPermission(IntermenstrualBleedingRecord::class),
                HealthPermission.getReadPermission(LeanBodyMassRecord::class),
                HealthPermission.getReadPermission(MenstruationFlowRecord::class),
                HealthPermission.getReadPermission(MenstruationPeriodRecord::class),
                HealthPermission.getReadPermission(NutritionRecord::class),
                HealthPermission.getReadPermission(OvulationTestRecord::class),
                HealthPermission.getReadPermission(OxygenSaturationRecord::class),
                HealthPermission.getReadPermission(PowerRecord::class),
                HealthPermission.getReadPermission(RespiratoryRateRecord::class),
                HealthPermission.getReadPermission(RestingHeartRateRecord::class),
                HealthPermission.getReadPermission(SexualActivityRecord::class),
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(SpeedRecord::class),
                HealthPermission.getReadPermission(StepsCadenceRecord::class),
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(StepsCadenceRecord::class),
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(Vo2MaxRecord::class),
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getReadPermission(WheelchairPushesRecord::class),
            )
    }
}
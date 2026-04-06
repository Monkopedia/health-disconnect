@file:OptIn(ExperimentalCoroutinesApi::class)

package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
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
import androidx.lifecycle.viewModelScope
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class PermissionsViewModel(
    context: Application,
    val healthConnectClient: HealthConnectClient?
) : AndroidViewModel(context) {

    private val providerPackageName = "com.google.android.apps.healthdata"
    val availabilityStatus: Int
        get() = HealthConnectClient.getSdkStatus(getApplication(), providerPackageName)
    val requestPermissionActivityContract =
        PermissionController.createRequestPermissionResultContract()

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

    private var ignoredPermissions = MutableStateFlow(false)
    private var checkTrigger = MutableSharedFlow<Unit>()
    val grantedPermissions: Flow<Set<String>>
        get() = if (availabilityStatus == HealthConnectClient.SDK_AVAILABLE) {
            checkTrigger.onStart { emit(Unit) }.flatMapLatest {
                flow {
                    while (true) {
                        emit(healthConnectClient!!.permissionController.getGrantedPermissions())
                        delay(CHECK_RATE)
                    }
                }
            }
        } else {
            flowOf(emptySet())
        }
    val needsPermissions: Flow<Boolean>
        get() = if (availabilityStatus == HealthConnectClient.SDK_AVAILABLE) {
            combine(
                grantedPermissions.map { granted -> granted.containsAll(PERMISSIONS) },
                ignoredPermissions
            ) { grantedPermissions, ignoredPermissions ->
                !grantedPermissions && !ignoredPermissions
            }
        } else {
            flowOf(false)
        }

    fun ignorePermissions() {
        ignoredPermissions.value = true
    }

    fun onResult(granted: Set<String>) {
        viewModelScope.launch {
            checkTrigger.emit(Unit)
        }
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            try {
                HealthDataWidgetScheduler.scheduleAll(appContext)
                HealthDataWidgetUpdater.updateAllWidgets(appContext)
                logWidgetFlow("PermissionsViewModel.onResult refreshedWidgets")
            } catch (exception: Exception) {
                logWidgetFlowError("PermissionsViewModel.onResult widgetRefreshFailed", exception)
            }
        }
    }

    companion object {
        private val CHECK_RATE = 30.seconds
        const val HISTORY_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        const val BACKGROUND_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        val CLASSES = setOf(
            ActiveCaloriesBurnedRecord::class,
            BasalBodyTemperatureRecord::class,
            BasalMetabolicRateRecord::class,
            BloodGlucoseRecord::class,
            BloodPressureRecord::class,
            BodyFatRecord::class,
            BodyTemperatureRecord::class,
            BodyWaterMassRecord::class,
            BoneMassRecord::class,
            CervicalMucusRecord::class,
            CyclingPedalingCadenceRecord::class,
            DistanceRecord::class,
            ElevationGainedRecord::class,
            ExerciseSessionRecord::class,
            FloorsClimbedRecord::class,
            HeartRateRecord::class,
            HeartRateVariabilityRmssdRecord::class,
            HeightRecord::class,
            HydrationRecord::class,
            IntermenstrualBleedingRecord::class,
            LeanBodyMassRecord::class,
            MenstruationFlowRecord::class,
            MenstruationPeriodRecord::class,
            NutritionRecord::class,
            OvulationTestRecord::class,
            OxygenSaturationRecord::class,
            PowerRecord::class,
            RespiratoryRateRecord::class,
            RestingHeartRateRecord::class,
            SexualActivityRecord::class,
            SleepSessionRecord::class,
            SpeedRecord::class,
            StepsCadenceRecord::class,
            StepsRecord::class,
            TotalCaloriesBurnedRecord::class,
            Vo2MaxRecord::class,
            WeightRecord::class,
            WheelchairPushesRecord::class
        )
        val READ_PERMISSIONS_BY_CLASS = CLASSES.associateWith { HealthPermission.getReadPermission(it) }
        val PERMISSIONS = READ_PERMISSIONS_BY_CLASS.values.toSet() + BACKGROUND_PERMISSION
        val RECORD_NAMES = mapOf(
            ActiveCaloriesBurnedRecord::class to "Active calories burned",
            BasalBodyTemperatureRecord::class to "Basal body temperature",
            BasalMetabolicRateRecord::class to "Basal metabolic rate",
            BloodGlucoseRecord::class to "Blood glucose",
            BloodPressureRecord::class to "Blood pressure",
            BodyFatRecord::class to "Body fat",
            BodyTemperatureRecord::class to "Body temperature",
            BodyWaterMassRecord::class to "Body water",
            BoneMassRecord::class to "Bone mass",
            CervicalMucusRecord::class to "Cervical mucus",
            CyclingPedalingCadenceRecord::class to "Cycling / Pedaling cadence",
            DistanceRecord::class to "Distance",
            ElevationGainedRecord::class to "Elevation gained",
            ExerciseSessionRecord::class to "Exercise session",
            FloorsClimbedRecord::class to "Floors climbed",
            HeartRateRecord::class to "Heart rate",
            HeartRateVariabilityRmssdRecord::class to "Heart rate variability",
            HeightRecord::class to "Height",
            HydrationRecord::class to "Hydration",
            IntermenstrualBleedingRecord::class to "Intermenstrual bleeding",
            LeanBodyMassRecord::class to "Lean body mass",
            MenstruationFlowRecord::class to "Menstruation flow",
            MenstruationPeriodRecord::class to "Menstruation period",
            NutritionRecord::class to "Nutrition",
            OvulationTestRecord::class to "Ovulation",
            OxygenSaturationRecord::class to "Oxygen saturation",
            PowerRecord::class to "Power",
            RespiratoryRateRecord::class to "Respiratory rate",
            RestingHeartRateRecord::class to "Resting heart rate",
            SexualActivityRecord::class to "Sexual activity",
            SleepSessionRecord::class to "Sleep session",
            SpeedRecord::class to "Speed",
            StepsCadenceRecord::class to "Steps cadence",
            StepsRecord::class to "Steps",
            TotalCaloriesBurnedRecord::class to "Total calories burned",
            Vo2MaxRecord::class to "VO2 max",
            WeightRecord::class to "Weight",
            WheelchairPushesRecord::class to "Wheelchair pushes"
        )
    }
}

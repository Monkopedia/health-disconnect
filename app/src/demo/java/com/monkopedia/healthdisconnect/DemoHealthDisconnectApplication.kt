package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.SavedStateHandle
import com.monkopedia.healthdisconnect.room.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

private val demoModule = module {
    single { HealthConnectClient.getOrCreate(androidContext()) }
    single<HealthConnectGateway> { DemoHealthConnectGateway() }
    single<HealthRecordMeasurementExtractor> { DefaultHealthRecordMeasurementExtractor() }
    single<HealthDataAggregationEngine> { DefaultHealthDataAggregationEngine(get()) }
    single<HealthDataRecordCachePolicy> { DefaultHealthDataRecordCachePolicy() }
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().dataViewDao() }
    single { get<AppDatabase>().dataViewInfoDao() }
    single<CoroutineDispatcherProvider> { DefaultDispatcherProvider() }
    single<TimeProvider> { SystemTimeProvider() }

    viewModel { (savedStateHandle: SavedStateHandle) ->
        LazyNavigationModel(
            app = get(),
            savedStateHandle = savedStateHandle
        )
    }
    viewModel { AppThemeViewModel(get()) }
    viewModel { (savedStateHandle: SavedStateHandle) ->
        DataViewAdapterViewModel(
            app = get(),
            savedStateHandle = savedStateHandle,
            dataViewDao = get(),
            dataViewInfoDao = get()
        )
    }
    viewModel { PermissionsViewModel(get(), get()) }
    viewModel {
        HealthDataModel(
            app = get(),
            autoRefreshMetrics = true,
            healthConnectGateway = get(),
            measurementExtractor = get(),
            aggregationEngine = get(),
            cachePolicy = get(),
            dispatchers = get(),
            timeProvider = get()
        )
    }
}

class DemoHealthDisconnectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (GlobalContext.getOrNull() != null) {
            return
        }
        startKoin {
            androidContext(this@DemoHealthDisconnectApplication)
            modules(demoModule)
        }
        CoroutineScope(Dispatchers.IO).launch {
            DemoDataSeeder.seedIfNeeded(this@DemoHealthDisconnectApplication)
        }
    }
}

package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import com.monkopedia.healthdisconnect.room.AppDatabase
import androidx.lifecycle.SavedStateHandle
import org.koin.core.context.GlobalContext
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

val appModule = module {
    single { HealthConnectClient.getOrCreate(androidContext()) }
    single<HealthConnectGateway> { DefaultHealthConnectGateway(get()) }
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

class HealthDisconnectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (GlobalContext.getOrNull() != null) {
            return
        }
        startKoin {
            androidContext(this@HealthDisconnectApplication)
            modules(appModule)
        }
    }
}

package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun LazyNavigation(viewModel: LazyNavigationModel = viewModel()) {
    val showingSettings by viewModel.isShowingSettings.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isShowingIntro by viewModel.isShowingIntro.collectAsStateWithLifecycle()
    if (isLoading) {
        LoadingScreen()
    } else if (isShowingIntro) {
        HealthDisconnectIntro()
    } else {
        PermissionsRoot {
            if (showingSettings) {
                SettingsScreen()
            } else {
                Greeting(
                    name = "Android"
                )
            }
        }
    }
}

class LazyNavigationModel(app: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(app) {

    val isShowingSettings = savedStateHandle.getStateFlow("is_showing_settings", false)
    val isLoading = savedStateHandle.getStateFlow("is_loading", true)
    val isShowingIntro = savedStateHandle.getStateFlow("is_showing_intro", false)

    init {
        viewModelScope.launch {
            savedStateHandle["is_showing_intro"] =
                app.navigationDataStore.data.first()[hasDismissedIntro] != true
            savedStateHandle["is_loading"] = false
        }
    }

    companion object {
        private val hasDismissedIntro = booleanPreferencesKey("dismissed")
        val Context.navigationDataStore by preferencesDataStore("navigation")
    }
}

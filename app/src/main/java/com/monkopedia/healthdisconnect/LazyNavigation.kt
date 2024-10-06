package com.monkopedia.healthdisconnect

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.ui.HealthDisconnectIntro
import com.monkopedia.healthdisconnect.ui.LoadingScreen
import com.monkopedia.healthdisconnect.ui.SettingsScreen
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
        HealthDisconnectIntro {
            viewModel.dismissIntro()
        }
    } else {
        PermissionsRoot {
            if (showingSettings) {
                SettingsScreen()
            } else {
                DataViewAdapter {
                    viewModel.setShowingSettings(true)
                }
            }
        }
    }
}

class LazyNavigationModel(app: Application, private val savedStateHandle: SavedStateHandle) :
    AndroidViewModel(app) {
    private val dataStore by lazy { context.navigationDataStore }
    val isShowingSettings = savedStateHandle.getStateFlow("is_showing_settings", false)
    val isLoading = savedStateHandle.getStateFlow("is_loading", true)
    val isShowingIntro = savedStateHandle.getStateFlow("is_showing_intro", false)

    init {
        viewModelScope.launch {
            savedStateHandle["is_showing_intro"] = dataStore.data.first()[hasDismissedIntro] != true
            savedStateHandle["is_loading"] = false
        }
    }

    fun setShowingSettings(showing: Boolean) {
        savedStateHandle["is_showing_settings"] = showing
    }

    fun dismissIntro() {
        viewModelScope.launch {
            dataStore.edit {
                it[hasDismissedIntro] = true
            }
            savedStateHandle.set("is_showing_intro", false)
        }
    }

    companion object {
        private val hasDismissedIntro = booleanPreferencesKey("dismissed")
        val Context.navigationDataStore by preferencesDataStore("navigation")
    }
}

internal val AndroidViewModel.context: Context
    get() = getApplication()

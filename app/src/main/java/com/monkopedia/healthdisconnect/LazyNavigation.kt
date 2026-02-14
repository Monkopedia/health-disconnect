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
import com.monkopedia.healthdisconnect.ui.EntriesRouteScreen
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private object Routes {
    const val Views = "views"
    const val Settings = "settings"
    const val EntriesPattern = "entries/{viewId}"
    fun entries(viewId: Int) = "entries/$viewId"
}

@Composable
fun LazyNavigation(viewModel: LazyNavigationModel = viewModel()) {
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isShowingIntro by viewModel.isShowingIntro.collectAsStateWithLifecycle()
    if (isLoading) {
        LoadingScreen()
    } else if (isShowingIntro) {
        HealthDisconnectIntro {
            viewModel.dismissIntro()
        }
    } else {
        val navController = rememberNavController()
        PermissionsRoot {
            NavHost(navController = navController, startDestination = Routes.Views) {
                composable(Routes.Views) {
                    DataViewAdapter(
                        showSettings = { navController.navigate(Routes.Settings) },
                        onOpenEntries = { viewId -> navController.navigate(Routes.entries(viewId)) }
                    )
                }
                composable(Routes.Settings) {
                    SettingsScreen()
                }
                composable(
                    route = Routes.EntriesPattern,
                    arguments = listOf(navArgument("viewId") { type = NavType.IntType })
                ) { backStackEntry ->
                    val viewId = backStackEntry.arguments?.getInt("viewId") ?: return@composable
                    EntriesRouteScreen(
                        viewId = viewId,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

class LazyNavigationModel(app: Application, private val savedStateHandle: SavedStateHandle) :
    AndroidViewModel(app) {
    private val dataStore by lazy { context.navigationDataStore }
    val isLoading = savedStateHandle.getStateFlow("is_loading", true)
    val isShowingIntro = savedStateHandle.getStateFlow("is_showing_intro", false)

    init {
        viewModelScope.launch {
            savedStateHandle["is_showing_intro"] = dataStore.data.first()[hasDismissedIntro] != true
            savedStateHandle["is_loading"] = false
        }
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

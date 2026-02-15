package com.monkopedia.healthdisconnect

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavController
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.monkopedia.healthdisconnect.room.AppDatabase
import com.monkopedia.healthdisconnect.room.DataViewEntity
import com.monkopedia.healthdisconnect.room.DataViewInfoEntity
import com.monkopedia.healthdisconnect.ui.EntriesRouteScreen
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import com.monkopedia.healthdisconnect.ui.SettingsScreen
import com.monkopedia.healthdisconnect.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

private const val TEST_VIEW_NAME = "Steps Test"
private const val TEST_VIEW_ID = 99

@RunWith(AndroidJUnit4::class)
class NavigationIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun navigateViewsToSettingsBack() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        seedSingleView(app)
        val adapterViewModel = DataViewAdapterViewModel(app, SavedStateHandle())
        val healthDataModel = HealthDataModel(app, false)

        composeRule.setContent {
            NavigationHarness(
                viewModel = adapterViewModel,
                healthDataModel = healthDataModel
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("current_route").assertTextContains("views")
        composeRule.onNodeWithTag("data_view_settings_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("current_route").assertTextContains("settings")
        composeRule.onNodeWithText(app.getString(R.string.settings_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("settings_back_button").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_back_button").performClick()
        composeRule.onNodeWithTag("current_route").assertTextContains("views")
    }

    @Test
    fun navigateViewsToEntriesBack() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        seedSingleView(app)
        val adapterViewModel = DataViewAdapterViewModel(app, SavedStateHandle())
        val healthDataModel = HealthDataModel(app, false)

        composeRule.setContent {
            NavigationHarness(
                viewModel = adapterViewModel,
                healthDataModel = healthDataModel
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("entries_row").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("current_route").assertTextContains("entries/$TEST_VIEW_ID")
        composeRule.onNodeWithText("Entries - $TEST_VIEW_NAME").assertIsDisplayed()
        composeRule.onNodeWithTag("entries_back_button").assertIsDisplayed()
        composeRule.onNodeWithTag("entries_back_button").performClick()
        composeRule.onNodeWithTag("current_route").assertTextContains("views")
    }

    @Composable
    private fun NavigationHarness(
        viewModel: DataViewAdapterViewModel,
        healthDataModel: HealthDataModel
    ) {
        HealthDisconnectTheme(dynamicColor = false) {
            val navController = rememberNavController()
            var currentRoute by remember { mutableStateOf("views") }
            DisposableEffect(navController) {
                val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                    currentRoute = destination.route.orEmpty()
                }
                navController.addOnDestinationChangedListener(listener)
                onDispose {
                    navController.removeOnDestinationChangedListener(listener)
                }
            }

            Box(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = "views"
                ) {
                    composable("views") {
                        DataViewAdapter(
                            viewModel = viewModel,
                            healthDataModel = healthDataModel,
                            showSettings = { navController.navigate("settings") },
                            onOpenEntries = { navController.navigate("entries/$it") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        route = "entries/{viewId}",
                        arguments = listOf(navArgument("viewId") {
                            type = NavType.IntType
                        })
                    ) { backStackEntry ->
                        val viewId = backStackEntry.arguments?.getInt("viewId") ?: TEST_VIEW_ID
                        EntriesRouteScreen(
                            viewId = viewId,
                            onBack = { navController.popBackStack() },
                            viewModel = viewModel,
                            healthDataModel = healthDataModel
                        )
                    }
                }
                Text(
                    text = currentRoute,
                    modifier = Modifier.testTag("current_route")
                )
            }
        }
    }

    private fun seedSingleView(app: Application) {
        runBlocking {
            val db = AppDatabase.getInstance(app)
            db.clearAllTables()
            val infoDao = db.dataViewInfoDao()
            val viewDao = db.dataViewDao()
            infoDao.insert(
                DataViewInfoEntity(
                    id = TEST_VIEW_ID,
                    name = TEST_VIEW_NAME,
                    ordering = 1
                )
            )
            viewDao.insert(
                DataViewEntity(
                    id = TEST_VIEW_ID,
                    type = com.monkopedia.healthdisconnect.model.ViewType.CHART.name,
                    recordsJson = "[]",
                    settingsJson = "{}"
                )
            )
        }
    }
}

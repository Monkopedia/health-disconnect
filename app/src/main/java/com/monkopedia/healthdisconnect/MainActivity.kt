package com.monkopedia.healthdisconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import com.monkopedia.healthdisconnect.ui.theme.resolveDarkTheme
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    private var linkedWidgetViewId: Int? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linkedWidgetViewId = intent.extractWidgetViewId()
        enableEdgeToEdge()
        setContent {
            val appThemeViewModel: AppThemeViewModel = koinViewModel()
            val themeMode by appThemeViewModel.themeMode.collectAsStateWithLifecycle(
                initialValue = AppThemeMode.SYSTEM
            )
            val darkTheme = resolveDarkTheme(themeMode)
            HealthDisconnectTheme(darkTheme = darkTheme, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyNavigation(initialViewId = linkedWidgetViewId)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkedWidgetViewId = null
        linkedWidgetViewId = intent.extractWidgetViewId()
    }
}

private fun android.content.Intent?.extractWidgetViewId(): Int? {
    val id = this?.getIntExtra(HealthDataWidgetContract.EXTRA_WIDGET_VIEW_ID, -1) ?: -1
    return id.takeIf { it >= 0 }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    HealthDisconnectTheme {
        Greeting("Android")
    }
}

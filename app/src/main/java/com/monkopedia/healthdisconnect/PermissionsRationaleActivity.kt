package com.monkopedia.healthdisconnect;

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import com.monkopedia.healthdisconnect.ui.theme.resolveDarkTheme
import org.koin.androidx.compose.koinViewModel

public class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appThemeViewModel: AppThemeViewModel = koinViewModel()
            val themeMode by appThemeViewModel.themeMode.collectAsStateWithLifecycle(
                initialValue = AppThemeMode.SYSTEM
            )
            val darkTheme = resolveDarkTheme(themeMode)
            HealthDisconnectTheme(darkTheme = darkTheme, dynamicColor = false) {
                val systemBarColor = MaterialTheme.colorScheme.surface.toArgb()
                SideEffect {
                    enableEdgeToEdge(
                        statusBarStyle = if (darkTheme) {
                            SystemBarStyle.dark(systemBarColor)
                        } else {
                            SystemBarStyle.light(systemBarColor, systemBarColor)
                        },
                        navigationBarStyle = if (darkTheme) {
                            SystemBarStyle.dark(systemBarColor)
                        } else {
                            SystemBarStyle.light(systemBarColor, systemBarColor)
                        }
                    )
                }
                PermissionsRationaleScreen()
            }
        }
    }
}

@Composable
fun PermissionsRationaleScreen() {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            PermissionRationale()
        }
    }
}

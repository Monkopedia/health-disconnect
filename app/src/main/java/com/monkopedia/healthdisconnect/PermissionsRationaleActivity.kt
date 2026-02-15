package com.monkopedia.healthdisconnect;

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme

public class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appThemeViewModel: AppThemeViewModel = viewModel()
            val themeMode by appThemeViewModel.themeMode.collectAsStateWithLifecycle(
                initialValue = AppThemeMode.SYSTEM
            )
            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            HealthDisconnectTheme(darkTheme = darkTheme, dynamicColor = false) {
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

package com.monkopedia.healthdisconnect;

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme

public class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthDisconnectTheme {
                PermissionRationale()
            }
        }
    }
}

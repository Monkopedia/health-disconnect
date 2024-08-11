package com.monkopedia.healthdisconnect

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme

@Composable
fun SettingsScreen() {
    Column {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
        )

        Text(
            text = "This is where the settings would go"
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    HealthDisconnectTheme {
        SettingsScreen()
    }
}

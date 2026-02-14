package com.monkopedia.healthdisconnect.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme

@Composable
fun SettingsScreen() {
    Column {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.align(alignment = Alignment.CenterHorizontally)
        )

        Text(
            text = stringResource(R.string.settings_placeholder)
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

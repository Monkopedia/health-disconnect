package com.monkopedia.healthdisconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextAlign.Companion
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.ui.theme.Typography

@Composable
fun PermissionsRoot(permissionsViewModel: PermissionsViewModel = viewModel()) {
    val availabilityStatus = permissionsViewModel.availabilityStatus

    if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
        NoSdkAvailable()
    } else if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
        UpdateRequired {
            permissionsViewModel.launchUpdate()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NoSdkAvailable() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No Health Connect Found!",
            style = Typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = "It appears this device is not capable of storing Health Connect data. Without this, Health Disconnect is really of no use to you.",
            modifier = Modifier.padding(top = 32.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
fun UpdateRequired(onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Health Connect Requires an Update",
            style = Typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Button(onClick = onClick, modifier = Modifier.padding(top = 32.dp)) {
            Text("Launch Update")
        }
    }
}

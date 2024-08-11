package com.monkopedia.healthdisconnect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.monkopedia.healthdisconnect.ui.theme.LightGreen
import com.monkopedia.healthdisconnect.ui.theme.DarkOrange
import com.monkopedia.healthdisconnect.ui.theme.Typography

@Composable
fun PermissionsRoot(
    permissionsViewModel: PermissionsViewModel = viewModel(),
    permittedContent: @Composable () -> Unit
) {
    val availabilityStatus = permissionsViewModel.availabilityStatus
    val needsPermissions by permissionsViewModel.needsPermissions.collectAsStateWithLifecycle(false)
    val permissionsRequest = rememberLauncherForActivityResult(
        contract = permissionsViewModel.requestPermissionActivityContract
    ) {
        println("Result: $it")
        permissionsViewModel.onResult(it)
    }

    if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
        NoSdkAvailable()
    } else if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
        UpdateRequired {
            permissionsViewModel.launchUpdate()
        }
    } else if (needsPermissions) {
        RequestPermissions(onIgnore = { permissionsViewModel.ignorePermissions() }) {
            println("Requesting ${PermissionsViewModel.PERMISSIONS}")
            permissionsRequest.launch(PermissionsViewModel.PERMISSIONS)
        }
    } else {
        permittedContent()
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

@Preview(showBackground = true)
@Composable
fun RequestPermissions(onIgnore: () -> Unit = {}, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PermissionRationale()
        Button(onClick = onClick, modifier = Modifier.padding(top = 64.dp)) {
            Text("Grant Permissions")
        }
        TextButton(onClick = onIgnore, modifier = Modifier.padding(top = 8.dp)) {
            Text("Ignore")
        }
    }
}

@Composable
fun PermissionRationale() {
    Text(
        text = buildAnnotatedString {
            append("Health Disconnect needs access to your ")
            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = DarkOrange)) {
                append("Health data")
            }
            append(" in order to display your ")
            withStyle(
                style = SpanStyle(
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    color = LightGreen
                )
            ) {
                append("Health data")
            }
        },
        style = Typography.headlineMedium,
        textAlign = TextAlign.Center
    )
}

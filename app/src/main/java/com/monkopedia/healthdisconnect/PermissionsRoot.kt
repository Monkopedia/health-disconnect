package com.monkopedia.healthdisconnect

import androidx.activity.compose.rememberLauncherForActivityResult
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.ui.theme.LightGreen
import com.monkopedia.healthdisconnect.ui.theme.DarkOrange
import com.monkopedia.healthdisconnect.ui.theme.Typography
import org.koin.androidx.compose.koinViewModel

private const val PERMISSIONS_TAG = "HealthDisconnectPermissions"

@Composable
fun PermissionsRoot(
    permissionsViewModel: PermissionsViewModel = koinViewModel(),
    permittedContent: @Composable () -> Unit
) {
    val availabilityStatus = permissionsViewModel.availabilityStatus
    val needsPermissions by permissionsViewModel.needsPermissions.collectAsStateWithLifecycle(false)
    val permissionsRequest = rememberLauncherForActivityResult(
        contract = permissionsViewModel.requestPermissionActivityContract
    ) {
        Log.d(PERMISSIONS_TAG, "permission_request_result=$it")
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
            Log.d(PERMISSIONS_TAG, "request_permissions_start")
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
            .safeDrawingPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.permissions_no_sdk_title),
            style = Typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.permissions_no_sdk_body),
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
            .safeDrawingPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.permissions_update_required_title),
            style = Typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Button(onClick = onClick, modifier = Modifier.padding(top = 32.dp)) {
            Text(stringResource(R.string.permissions_launch_update))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RequestPermissions(onIgnore: () -> Unit = {}, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PermissionRationale()
        Button(onClick = onClick, modifier = Modifier.padding(top = 64.dp)) {
            Text(stringResource(R.string.permissions_grant))
        }
        TextButton(onClick = onIgnore, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.permissions_ignore))
        }
    }
}

@Composable
fun PermissionRationale() {
    val emphasis = stringResource(R.string.permissions_rationale_emphasis)
    val body = stringResource(R.string.permissions_rationale_body, emphasis, emphasis)
    Text(
        text = buildAnnotatedString {
            append(body)
            val firstIndex = body.indexOf(emphasis)
            if (firstIndex >= 0) {
                addStyle(
                    style = SpanStyle(fontWeight = FontWeight.Bold, color = DarkOrange),
                    start = firstIndex,
                    end = firstIndex + emphasis.length
                )
            }
            val secondIndex = body.indexOf(emphasis, startIndex = (firstIndex + emphasis.length).coerceAtLeast(0))
            if (secondIndex >= 0) {
                addStyle(
                    style = SpanStyle(
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold,
                        color = LightGreen
                    ),
                    start = secondIndex,
                    end = secondIndex + emphasis.length
                )
            }
        },
        style = Typography.headlineMedium,
        textAlign = TextAlign.Center
    )
}

package com.monkopedia.healthdisconnect.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monkopedia.healthdisconnect.AppThemeMode
import com.monkopedia.healthdisconnect.AppThemeViewModel
import com.monkopedia.healthdisconnect.PermissionsViewModel
import com.monkopedia.healthdisconnect.R
import com.monkopedia.healthdisconnect.ui.theme.HealthDisconnectTheme
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MetricDebugRange(
    val label: String,
    val permissionGranted: Boolean,
    val oldest: Instant?,
    val newest: Instant?
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    onBack: () -> Unit = {},
    permissionsViewModel: PermissionsViewModel = koinViewModel(),
    appThemeViewModel: AppThemeViewModel = koinViewModel(),
    initialDebugExpanded: Boolean = false,
    initialThemeDropdownExpanded: Boolean = false,
    previewDebugRows: List<String>? = null
) {
    val availabilityStatus = permissionsViewModel.availabilityStatus
    val grantedPermissions by permissionsViewModel.grantedPermissions.collectAsStateWithLifecycle(initialValue = emptySet())
    val baseGranted = grantedPermissions.containsAll(PermissionsViewModel.PERMISSIONS)
    val historyGranted = grantedPermissions.contains(PermissionsViewModel.HISTORY_PERMISSION)
    val themeMode by appThemeViewModel.themeMode.collectAsStateWithLifecycle(
        initialValue = AppThemeMode.SYSTEM
    )
    var themeDropdownExpanded by remember { mutableStateOf(initialThemeDropdownExpanded) }
    var debugExpanded by remember { mutableStateOf(initialDebugExpanded) }
    var debugLoading by remember { mutableStateOf(false) }
    var debugRows by remember { mutableStateOf(emptyList<MetricDebugRange>()) }
    val scope = rememberCoroutineScope()
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    val requestLauncher = rememberLauncherForActivityResult(
        contract = permissionsViewModel.requestPermissionActivityContract
    ) { result ->
        permissionsViewModel.onResult(result)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        CenterAlignedTopAppBar(
            modifier = Modifier.fillMaxWidth(),
            title = {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("settings_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.data_view_back)
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (availabilityStatus == HealthConnectClient.SDK_AVAILABLE) {
                    R.string.settings_hc_available
                } else {
                    R.string.settings_hc_unavailable
                }
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        val selectedThemeLabel = when (themeMode) {
            AppThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
            AppThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
            AppThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_theme_row")
                .clickable { themeDropdownExpanded = true }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_theme_label),
                modifier = Modifier.testTag("settings_theme_label"),
                style = MaterialTheme.typography.bodyMedium
            )
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedThemeLabel,
                        modifier = Modifier.testTag("settings_theme_value"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        modifier = Modifier.testTag("settings_theme_icon"),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = themeDropdownExpanded,
                    onDismissRequest = { themeDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_theme_system)) },
                        onClick = {
                            appThemeViewModel.setThemeMode(AppThemeMode.SYSTEM)
                            themeDropdownExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_theme_dark)) },
                        onClick = {
                            appThemeViewModel.setThemeMode(AppThemeMode.DARK)
                            themeDropdownExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_theme_light)) },
                        onClick = {
                            appThemeViewModel.setThemeMode(AppThemeMode.LIGHT)
                            themeDropdownExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (baseGranted) R.string.settings_permissions_granted
                else R.string.settings_permissions_not_granted
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (historyGranted) R.string.settings_history_granted
                else R.string.settings_history_not_granted
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { requestLauncher.launch(PermissionsViewModel.PERMISSIONS) },
            enabled = availabilityStatus == HealthConnectClient.SDK_AVAILABLE && !baseGranted
        ) {
            Text(stringResource(R.string.settings_request_base_permissions))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                requestLauncher.launch(
                    PermissionsViewModel.PERMISSIONS + PermissionsViewModel.HISTORY_PERMISSION
                )
            },
            enabled = availabilityStatus == HealthConnectClient.SDK_AVAILABLE && !historyGranted
        ) {
            Text(stringResource(R.string.settings_request_history_permission))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.settings_permissions_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.settings_advanced_debug),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            IconButton(onClick = { debugExpanded = !debugExpanded }) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(
                            if (debugExpanded) Modifier else Modifier
                        )
                )
            }
        }
        AnimatedVisibility(visible = debugExpanded) {
            Column {
                Button(
                    onClick = {
                        scope.launch {
                            debugLoading = true
                            debugRows = withContext(Dispatchers.IO) {
                                loadMetricDebugRanges(permissionsViewModel, grantedPermissions)
                            }
                            debugLoading = false
                        }
                    },
                    enabled = availabilityStatus == HealthConnectClient.SDK_AVAILABLE && !debugLoading
                ) {
                    Text(stringResource(R.string.settings_refresh_debug_ranges))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (historyGranted) R.string.settings_history_true else R.string.settings_history_false
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (debugLoading) {
                    Text(
                        text = stringResource(R.string.loading_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (previewDebugRows != null) {
                        previewDebugRows.forEach { rowText ->
                            Text(
                                text = rowText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    } else {
                        debugRows.forEach { row ->
                            val oldestText = row.oldest?.atZone(ZoneId.systemDefault())?.format(dateFormatter)
                                ?: "n/a"
                            val newestText = row.newest?.atZone(ZoneId.systemDefault())?.format(dateFormatter)
                                ?: "n/a"
                            Text(
                                text = "${row.label}: perm=${if (row.permissionGranted) "yes" else "no"}, oldest=$oldestText, newest=$newestText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    HealthDisconnectTheme {
        SettingsScreen()
    }
}

private suspend fun loadMetricDebugRanges(
    permissionsViewModel: PermissionsViewModel,
    grantedPermissions: Set<String>
): List<MetricDebugRange> {
    val now = Instant.now()
    return PermissionsViewModel.CLASSES
        .sortedBy { PermissionsViewModel.RECORD_NAMES[it] ?: (it.simpleName ?: "") }
        .map { cls ->
            val label = PermissionsViewModel.RECORD_NAMES[cls] ?: (cls.simpleName ?: cls.qualifiedName ?: "Record")
            val readPermission = PermissionsViewModel.READ_PERMISSIONS_BY_CLASS[cls]
            val hasPermission = readPermission != null && grantedPermissions.contains(readPermission)
            if (!hasPermission) {
                return@map MetricDebugRange(
                    label = label,
                    permissionGranted = false,
                    oldest = null,
                    newest = null
                )
            }
            val range = runCatching {
                readOldestNewest(permissionsViewModel, cls, now)
            }.getOrNull()
            MetricDebugRange(
                label = label,
                permissionGranted = true,
                oldest = range?.first,
                newest = range?.second
            )
        }
}

private suspend fun readOldestNewest(
    permissionsViewModel: PermissionsViewModel,
    cls: KClass<out Record>,
    now: Instant
): Pair<Instant?, Instant?> {
    var token: String? = null
    var oldest: Instant? = null
    var newest: Instant? = null
    do {
        val response = permissionsViewModel.healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = cls,
                timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, now),
                pageToken = token,
                pageSize = 500
            )
        )
        response.records.forEach { record ->
            val timestamp = recordTimestamp(record) ?: return@forEach
            if (newest == null || timestamp.isAfter(newest)) newest = timestamp
            if (oldest == null || timestamp.isBefore(oldest)) oldest = timestamp
        }
        token = response.pageToken
    } while (token != null)
    return oldest to newest
}

private fun recordTimestamp(record: Record): Instant? {
    val candidates = listOf(
        "getTime",
        "getStartTime",
        "getEndTime"
    )
    candidates.forEach { name ->
        val instant = runCatching {
            record.javaClass.getMethod(name).invoke(record) as? Instant
        }.getOrNull()
        if (instant != null) return instant
    }
    return null
}

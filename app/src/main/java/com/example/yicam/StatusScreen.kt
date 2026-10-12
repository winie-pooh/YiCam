/**
 * Responsible for: the localized status screen: connection state with a reason, battery, SD card, camera mode, the read-only list of camera settings, Connect/Disconnect/Refresh buttons and the menu entry to the probe screen.
 * Not responsible for: any link or protocol logic (CameraSession.kt), the dev probe screen (ProbeScreen.kt) or the texts themselves (the strings.xml files of values, values-es and values-uk).
 */
package com.example.yicam

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatusScreen(
    session: CameraSession,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProbe: () -> Unit,
) {
    val state by session.state.collectAsState()
    val status by session.status.collectAsState()
    val snapshot by session.settings.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val canConnect = state is SessionState.Disconnected ||
        state is SessionState.Error ||
        state is SessionState.PermissionRequired

    Column(
        modifier = Modifier.safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.title_status),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("\u22EE") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_probe)) },
                        onClick = {
                            menuOpen = false
                            onOpenProbe()
                        },
                    )
                }
            }
        }

        ConnectionCard(state)

        if (state is SessionState.PermissionRequired) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.button_open_settings)) }
        }

        StatusCard(status)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (canConnect) {
                Button(onClick = onConnect, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.button_connect))
                }
            } else {
                OutlinedButton(onClick = session::disconnect, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.button_disconnect))
                }
            }
            OutlinedButton(
                onClick = session::refresh,
                enabled = state is SessionState.Connected,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.button_refresh)) }
        }

        Text(stringResource(R.string.title_settings_snapshot), style = MaterialTheme.typography.titleSmall)
        val entries = remember(snapshot) {
            snapshot?.values?.toSortedMap()?.entries?.toList() ?: emptyList()
        }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.no_data_yet))
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(entries) { entry ->
                    Text(
                        text = "${entry.key} = ${entry.value}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: SessionState) {
    val colors = MaterialTheme.colorScheme
    val container = when (state) {
        is SessionState.Connected -> colors.primaryContainer
        is SessionState.Error, SessionState.CameraOff, SessionState.PermissionRequired -> colors.errorContainer
        SessionState.Disconnected -> colors.surfaceVariant
        else -> colors.secondaryContainer
    }
    Surface(color = container, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stateTitle(state), style = MaterialTheme.typography.titleMedium)
            val detail = stateDetail(state)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatusCard(status: CameraStatus) {
    val context = LocalContext.current
    val dash = "\u2014"
    val battery = status.batteryPercent?.let { stringResource(R.string.battery_value, it) } ?: dash
    val charging = if (status.charging == true) " \u00B7 " + stringResource(R.string.label_charging) else ""
    val total = status.sdTotalKb
    val free = status.sdFreeKb
    val unusable = status.sdUnusableReportedKb
    val sd = if (unusable != null) {
        stringResource(R.string.sd_not_usable, Formatter.formatFileSize(context, unusable * 1024L))
    } else if (total != null && free != null) {
        stringResource(
            R.string.sd_free_of_total,
            Formatter.formatFileSize(context, free * 1024L),
            Formatter.formatFileSize(context, total * 1024L),
        )
    } else {
        dash
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ValueRow(stringResource(R.string.label_battery), battery + charging)
            ValueRow(stringResource(R.string.label_sd), sd)
            ValueRow(stringResource(R.string.label_mode), status.appStatus ?: dash)
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun stateTitle(state: SessionState): String = when (state) {
    SessionState.Disconnected -> stringResource(R.string.state_disconnected)
    SessionState.SearchingWifi -> stringResource(R.string.state_searching_wifi)
    SessionState.PermissionRequired -> stringResource(R.string.state_permission_required)
    SessionState.Connecting -> stringResource(R.string.state_connecting)
    is SessionState.Connected -> stringResource(R.string.state_connected)
    is SessionState.Reconnecting -> stringResource(
        R.string.state_reconnecting,
        state.attempt,
        (state.nextDelayMs + 999L) / 1000L,
    )
    SessionState.CameraOff -> stringResource(R.string.state_camera_off)
    is SessionState.Error -> stringResource(R.string.state_error)
}

@Composable
private fun stateDetail(state: SessionState): String? = when (state) {
    is SessionState.Reconnecting -> reasonText(state.reason)
    is SessionState.Error -> reasonText(state.reason)
    SessionState.PermissionRequired -> reasonText(FailReason.PERMISSION_DENIED)
    else -> null
}

@Composable
private fun reasonText(reason: FailReason): String = stringResource(
    when (reason) {
        FailReason.NO_WIFI -> R.string.reason_no_wifi
        FailReason.NOT_CAMERA_NETWORK -> R.string.reason_not_camera_network
        FailReason.PERMISSION_DENIED -> R.string.reason_permission_denied
        FailReason.TIMEOUT -> R.string.reason_timeout
        FailReason.CONNECTION_LOST -> R.string.reason_connection_lost
        FailReason.PROTOCOL -> R.string.reason_protocol
    },
)

/**
 * Responsible for: the probe screen (dev tool, English text only): connection buttons, canned and raw commands, the RTSP trace button, log view, Share log, a way back to the status screen.
 * Not responsible for: any network or protocol logic (ProbeController.kt, CameraSession.kt) or the localized status screen (StatusScreen.kt).
 */
package com.example.yicam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProbeScreen(
    controller: ProbeController,
    onConnect: () -> Unit,
    onShareLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val lines by controller.log.lines.collectAsState()
    var raw by rememberSaveable { mutableStateOf("""{"msg_id":13}""") }
    val connected = state is SessionState.Connected
    val tight = PaddingValues(horizontal = 4.dp)

    Column(
        modifier = Modifier.safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack, contentPadding = tight) { Text("< Status") }
            Text(statusText(state), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }

        if (state is SessionState.PermissionRequired) {
            Text("Local network permission was denied. Allow it in the app settings, then press Connect.")
            Button(onClick = onOpenSettings) { Text("Open settings") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onConnect, modifier = Modifier.weight(1f), contentPadding = tight) { Text("Connect") }
            OutlinedButton(
                onClick = controller::disconnect,
                enabled = state !is SessionState.Disconnected,
                modifier = Modifier.weight(1f),
                contentPadding = tight,
            ) { Text("Disconnect") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = controller::settings, enabled = connected, modifier = Modifier.weight(1f), contentPadding = tight) { Text("Settings") }
            Button(onClick = controller::battery, enabled = connected, modifier = Modifier.weight(1f), contentPadding = tight) { Text("Battery") }
            Button(onClick = controller::startPreview, enabled = connected, modifier = Modifier.weight(1f), contentPadding = tight) { Text("Preview") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = controller::changeToDcim, enabled = connected, modifier = Modifier.weight(1f), contentPadding = tight) { Text("cd DCIM") }
            Button(onClick = controller::listFiles, enabled = connected, modifier = Modifier.weight(1f), contentPadding = tight) { Text("ls") }
            Button(onClick = controller::rtspTrace, enabled = connected, modifier = Modifier.weight(1f), contentPadding = tight) { Text("RTSP trace") }
            Button(onClick = controller::dumpOptions, enabled = connected, modifier = Modifier.weight(1f), contentPadding = tight) { Text("Options") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it },
                label = { Text("Raw command (JSON, %TOKEN% is replaced)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { controller.sendRaw(raw) }, enabled = connected) { Text("Send") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onShareLog, modifier = Modifier.weight(1f), contentPadding = tight) { Text("Share log") }
            OutlinedButton(onClick = controller::clearLog, modifier = Modifier.weight(1f), contentPadding = tight) { Text("Clear log") }
        }

        LogView(lines = lines, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun LogView(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LazyColumn(state = listState, modifier = modifier) {
        items(lines) { line ->
            Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

private fun statusText(state: SessionState): String = when (state) {
    SessionState.Disconnected -> "Disconnected"
    SessionState.SearchingWifi -> "Looking for the camera Wi-Fi..."
    SessionState.PermissionRequired -> "Local network permission needed"
    SessionState.Connecting -> "Connecting..."
    is SessionState.Connected -> "Connected, token ${state.token}"
    is SessionState.Reconnecting -> "Reconnecting, attempt ${state.attempt}, next in ${state.nextDelayMs / 1000} s (${state.reason})"
    SessionState.CameraOff -> "Camera is off or out of range"
    is SessionState.Error -> "Error ${state.reason}: ${state.detail}"
}

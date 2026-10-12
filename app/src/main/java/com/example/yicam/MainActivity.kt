/**
 * Responsible for: the probe screen (dev tool, English text only): buttons, raw command field, log view, local-network permission request, Share log.
 * Not responsible for: any network or protocol logic (ProbeController.kt, CameraApi.kt).
 */
package com.example.yicam

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

class MainActivity : ComponentActivity() {
    private lateinit var controller: ProbeController

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) controller.connect() else controller.onLocalNetworkDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = ProbeController.get(this)
        setContent {
            ProbeScreen(
                controller = controller,
                onConnect = ::connectClicked,
                onShareLog = ::shareLog,
                onOpenSettings = ::openAppSettings,
            )
        }
    }

    /** On Android 17+ the app needs the local network permission before it may talk to 192.168.42.1. */
    private fun connectClicked() {
        val needsPermission = Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
        } else {
            controller.connect()
        }
    }

    private fun shareLog() {
        // Keep the text well below the Android limit for data passed between apps.
        val text = controller.log.asText().takeLast(MAX_SHARED_CHARS)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "YiCam log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share log"))
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val MAX_SHARED_CHARS = 150_000
    }
}

@Composable
private fun ProbeScreen(
    controller: ProbeController,
    onConnect: () -> Unit,
    onShareLog: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val lines by controller.log.lines.collectAsState()
    val denied by controller.localNetworkDenied.collectAsState()
    var raw by rememberSaveable { mutableStateOf("""{"msg_id":13}""") }
    val connected = state is ConnectionState.Connected
    val tight = PaddingValues(horizontal = 4.dp)

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeDrawingPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(statusText(state), style = MaterialTheme.typography.titleMedium)

                if (denied) {
                    Text("Local network permission was denied. Allow it in the app settings, then press Connect.")
                    Button(onClick = onOpenSettings) { Text("Open settings") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onConnect, modifier = Modifier.weight(1f), contentPadding = tight) { Text("Connect") }
                    OutlinedButton(
                        onClick = controller::disconnect,
                        enabled = state !is ConnectionState.Disconnected,
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

private fun statusText(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Connecting..."
    is ConnectionState.Connected -> "Connected, token ${state.token}"
    is ConnectionState.Failed -> "Failed: ${state.reason}"
}

package com.example.yicam

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v0.0.1: protocol probe screen. No camera features yet, only connect + raw commands + log. */
class MainActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val logLines = mutableStateListOf<String>()
    private lateinit var conn: CameraConnection
    private lateinit var wifi: WifiBinder

    // Android 17+ (targetSdk 37+) blocks local network access until the user allows it.
    private val localNetPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log(if (granted) "Local network permission: GRANTED" else "Local network permission: DENIED")
            if (granted) startConnect()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        conn = CameraConnection { line -> log(line) }
        wifi = WifiBinder(this)
        log("App started. Android SDK ${Build.VERSION.SDK_INT}")
        setContent { MaterialTheme { Screen() } }
    }

    override fun onDestroy() {
        conn.close()
        wifi.release()
        wifi.unbindProcess()
        super.onDestroy()
    }

    private fun log(text: String) {
        val line = "[" + SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + "] " + text
        main.post {
            logLines.add(line)
            if (logLines.size > 3000) logLines.removeAt(0)
        }
    }

    private fun connect() {
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(LOCAL_NET) != PackageManager.PERMISSION_GRANTED
        ) {
            log("Requesting local network permission...")
            localNetPermission.launch(LOCAL_NET)
            return
        }
        startConnect()
    }

    private fun startConnect() {
        log("Looking for the camera Wi-Fi (phone must already be connected to it)...")
        wifi.bind { result ->
            log(wifi.lastInfo)
            result.onSuccess { net ->
                Thread {
                    try {
                        conn.connect(net, CAM_HOST, CAM_PORT)
                        log("TCP connected to $CAM_HOST:$CAM_PORT")
                        conn.send("""{"msg_id":257,"token":0}""")
                    } catch (e: Exception) {
                        log("CONNECT FAILED: $e")
                    }
                }.start()
            }.onFailure { log("NO CAMERA WI-FI: ${it.message}") }
        }
    }

    private fun shareLog() {
        val text = logLines.joinToString("\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share log"))
    }

    @Composable
    private fun Screen() {
        var command by remember { mutableStateOf("""{"msg_id":3,"token":%TOKEN%}""") }
        val listState = rememberLazyListState()
        LaunchedEffect(logLines.size) {
            if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Yi Cam probe v0.0.1", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { connect() }, modifier = Modifier.weight(1f)) { Text("Connect") }
                Button(
                    onClick = { conn.send("""{"msg_id":3,"token":%TOKEN%}""") },
                    modifier = Modifier.weight(1f)
                ) { Text("Settings") }
                Button(
                    onClick = { conn.send("""{"msg_id":13,"token":%TOKEN%}""") },
                    modifier = Modifier.weight(1f)
                ) { Text("Battery") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { conn.send("""{"msg_id":259,"token":%TOKEN%,"param":"none_force"}""") },
                    modifier = Modifier.weight(1f)
                ) { Text("Preview") }
                Button(
                    onClick = { conn.send("""{"msg_id":1283,"token":%TOKEN%,"param":"/tmp/fuse_d/DCIM"}""") },
                    modifier = Modifier.weight(1f)
                ) { Text("cd DCIM") }
                Button(
                    onClick = { conn.send("""{"msg_id":1282,"token":%TOKEN%,"param":"-D -S"}""") },
                    modifier = Modifier.weight(1f)
                ) { Text("ls") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { shareLog() }, modifier = Modifier.weight(1f)) { Text("Share log") }
                Button(onClick = { logLines.clear() }, modifier = Modifier.weight(1f)) { Text("Clear") }
                Button(
                    onClick = { conn.close(); log("Disconnected by user") },
                    modifier = Modifier.weight(1f)
                ) { Text("Disconnect") }
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Raw command (%TOKEN% is replaced automatically)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { conn.send(command) }, modifier = Modifier.fillMaxWidth()) { Text("Send") }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(state = listState) {
                    items(logLines) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    companion object {
        const val CAM_HOST = "192.168.42.1"
        const val CAM_PORT = 7878
        const val LOCAL_NET = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

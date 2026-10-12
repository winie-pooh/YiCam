/**
 * Responsible for: the single activity: choosing between the status screen and the probe screen, asking for the local network permission, telling the session when the app goes to the background, Share log.
 * Not responsible for: drawing the screens (StatusScreen.kt, ProbeScreen.kt) or any link/protocol logic (CameraSession.kt).
 */
package com.example.yicam

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private lateinit var session: CameraSession
    private lateinit var probe: ProbeController

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) session.connect() else session.onPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        session = SessionProvider.get(this)
        probe = ProbeController.get(this)
        setContent {
            AppRoot(
                session = session,
                probe = probe,
                onConnect = ::connectClicked,
                onShareLog = ::shareLog,
                onOpenSettings = ::openAppSettings,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        session.setForeground(true)
    }

    override fun onStop() {
        session.setForeground(false)
        super.onStop()
    }

    /** On Android 17+ the app needs the local network permission before it may talk to 192.168.42.1. */
    private fun connectClicked() {
        val needsPermission = Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
        } else {
            session.connect()
        }
    }

    private fun shareLog() {
        // Keep the text well below the Android limit for data passed between apps.
        val text = session.log.asText().takeLast(MAX_SHARED_CHARS)
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
private fun AppRoot(
    session: CameraSession,
    probe: ProbeController,
    onConnect: () -> Unit,
    onShareLog: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showProbe by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showProbe) { showProbe = false }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (showProbe) {
                ProbeScreen(
                    controller = probe,
                    onConnect = onConnect,
                    onShareLog = onShareLog,
                    onOpenSettings = onOpenSettings,
                    onBack = { showProbe = false },
                )
            } else {
                StatusScreen(
                    session = session,
                    onConnect = onConnect,
                    onOpenSettings = onOpenSettings,
                    onOpenProbe = { showProbe = true },
                )
            }
        }
    }
}

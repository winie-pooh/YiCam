/**
 * Responsible for: the single activity: choosing between the status, live view, camera settings and probe screens, asking for the local network permission, opening the system Wi-Fi list before connecting (the Connect button of the status screen), telling the session when the app goes to the background and stopping the live view then, Share log.
 * Not responsible for: drawing the screens (StatusScreen.kt, LiveScreen.kt, SettingsScreen.kt, ProbeScreen.kt) or any link, protocol or video logic (CameraSession.kt, LiveViewController.kt).
 */
package com.example.yicam

import android.content.ActivityNotFoundException
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
    private lateinit var livePlayer: ExoRtspPlayer
    private lateinit var live: LiveViewController
    private lateinit var controls: CameraController
    private lateinit var cameraSettings: CameraSettings

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) session.connect() else session.onPermissionDenied()
        }

    /** Opens the system Wi-Fi list; when the user comes back, the app connects. */
    private val wifiLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { connectClicked() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        session = SessionProvider.get(this)
        probe = ProbeController.get(this)
        val liveParts = LiveProvider.get(applicationContext)
        livePlayer = liveParts.player
        live = liveParts.controller
        controls = ControlProvider.get(this)
        cameraSettings = ControlProvider.settings(this)
        setContent {
            AppRoot(
                session = session,
                probe = probe,
                live = live,
                controls = controls,
                cameraSettings = cameraSettings,
                livePlayer = livePlayer,
                onConnect = ::connectClicked,
                onChooseWifi = ::chooseWifi,
                onShareLog = ::shareLog,
                onOpenSettings = ::openAppSettings,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        session.setForeground(true)
        live.resumeAfterBackground()
    }

    override fun onStop() {
        // A rotation also stops the activity: the live view must go on then.
        if (!isChangingConfigurations) live.pauseForBackground()
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

    /** The Wi-Fi panel (Android 10+) lets the user pick the camera network without leaving the app; older versions open the Wi-Fi settings. */
    private fun chooseWifi() {
        val panel = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        try {
            wifiLauncher.launch(panel)
        } catch (_: ActivityNotFoundException) {
            wifiLauncher.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
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
    live: LiveViewController,
    controls: CameraController,
    cameraSettings: CameraSettings,
    livePlayer: ExoRtspPlayer,
    onConnect: () -> Unit,
    onChooseWifi: () -> Unit,
    onShareLog: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // "status", "live", "settings" or "probe"
    var screen by rememberSaveable { mutableStateOf("status") }
    BackHandler(enabled = screen != "status") { screen = "status" }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                "probe" -> ProbeScreen(
                    controller = probe,
                    onConnect = onConnect,
                    onShareLog = onShareLog,
                    onOpenSettings = onOpenSettings,
                    onBack = { screen = "status" },
                )
                "live" -> LiveScreen(
                    controller = live,
                    player = livePlayer,
                    controls = controls,
                    onBack = { screen = "status" },
                )
                "settings" -> SettingsScreen(
                    settings = cameraSettings,
                    controls = controls,
                    onBack = { screen = "status" },
                )
                else -> StatusScreen(
                    session = session,
                    onConnect = onChooseWifi,
                    onOpenSettings = onOpenSettings,
                    onOpenProbe = { screen = "probe" },
                    onOpenLive = { screen = "live" },
                    onOpenCameraSettings = { screen = "settings" },
                )
            }
        }
    }
}

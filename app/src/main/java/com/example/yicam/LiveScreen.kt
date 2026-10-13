/**
 * Responsible for: the localized live view screen: the video surface (kept in the picture's aspect ratio), a status line, the technical detail of a problem, Start and Stop buttons.
 * Not responsible for: the live view logic (LiveViewController.kt), the player (ExoRtspPlayer.kt) or the texts themselves (the strings.xml files of values, values-es and values-uk).
 */
package com.example.yicam

import android.app.Activity
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun LiveScreen(
    controller: LiveViewController,
    player: ExoRtspPlayer,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val aspect by player.videoAspect.collectAsState()

    val activity = LocalContext.current as? Activity

    // Not on every entry: after a rotation the stream is already running and must not be restarted.
    LaunchedEffect(Unit) {
        val current = controller.state.value
        if (current is LiveState.Idle || current is LiveState.Problem) controller.start()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true) controller.stop()
            player.attachSurface(null)
        }
    }

    Column(
        modifier = Modifier.safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("<") }
            Text(
                text = stringResource(R.string.title_live),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context -> SurfaceView(context).also { player.attachSurface(it) } },
                modifier = Modifier.aspectRatio(aspect),
            )
            val message = liveMessage(state)
            if (state !is LiveState.Playing) {
                Text(
                    text = message,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            }
        }

        if (state is LiveState.Playing) {
            Text(liveMessage(state), style = MaterialTheme.typography.bodyMedium)
        }
        val detail = (state as? LiveState.Problem)?.detail.orEmpty()
        if (detail.isNotEmpty()) {
            Text(detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = controller::start, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.button_live_start))
            }
            OutlinedButton(onClick = controller::stop, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.button_live_stop))
            }
        }
    }
}

@Composable
private fun liveMessage(state: LiveState): String = when (state) {
    LiveState.Idle -> stringResource(R.string.live_idle)
    LiveState.StartingPreview -> stringResource(R.string.live_starting_preview)
    LiveState.ConnectingStream -> stringResource(R.string.live_connecting_stream)
    is LiveState.Playing -> stringResource(R.string.live_playing, state.startMs)
    is LiveState.Problem -> stringResource(
        when (state.problem) {
            LiveProblem.NOT_CONNECTED -> R.string.live_problem_not_connected
            LiveProblem.RECORDING -> R.string.live_problem_recording
            LiveProblem.PREVIEW_DISABLED -> R.string.live_problem_preview_disabled
            LiveProblem.PREVIEW_COMMAND_FAILED -> R.string.live_problem_preview_failed
            LiveProblem.STREAM_FAILED -> R.string.live_problem_stream_failed
            LiveProblem.STREAM_TIMEOUT -> R.string.live_problem_stream_timeout
        },
    )
}

/**
 * Responsible for: the localized live view screen: the video (a TextureView, turned to a vertical or horizontal picture and kept in the picture's proportions so it takes most of the screen), the grid lines over it, small buttons over the picture (back, orientation, grid, start/stop), the status text, the technical detail of a problem, and the control panel (ControlPanel.kt) below.
 * Not responsible for: the live view logic (LiveViewController.kt), the player (ExoRtspPlayer.kt), the sizes (LiveOrientation.kt), the grid lines (GridGeometry.kt) or the texts themselves (the strings.xml files of values, values-es and values-uk).
 */
package com.example.yicam

import android.app.Activity
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
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
    controls: CameraController,
    prefs: AppPrefs,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val aspect by player.videoAspect.collectAsState()
    var orientation by remember { mutableStateOf(prefs.liveOrientation) }
    var grid by remember { mutableStateOf(prefs.gridMode) }

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

    val tight = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    val running = state !is LiveState.Idle && state !is LiveState.Problem

    Column(
        modifier = Modifier.safeDrawingPadding().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val sizes = LiveLayout.fit(maxWidth.value, maxHeight.value, aspect, orientation)
                Box(modifier = Modifier.size(sizes.shownWidth.dp, sizes.shownHeight.dp)) {
                    AndroidView(
                        factory = { context -> TextureView(context).also { player.attachSurface(it) } },
                        modifier = Modifier.requiredSize(sizes.viewWidth.dp, sizes.viewHeight.dp).rotate(orientation.degrees),
                    )
                    GridOverlay(grid, Modifier.fillMaxSize())
                }
            }
            if (state !is LiveState.Playing) {
                Text(
                    text = liveMessage(state),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalButton(onClick = onBack, contentPadding = tight) { Text("<") }
                FilledTonalButton(
                    onClick = {
                        orientation = orientation.next()
                        prefs.liveOrientation = orientation
                    },
                    contentPadding = tight,
                ) {
                    Text(
                        stringResource(
                            when (orientation) {
                                LiveOrientation.VERTICAL_LEFT -> R.string.live_orient_left
                                LiveOrientation.VERTICAL_RIGHT -> R.string.live_orient_right
                                LiveOrientation.HORIZONTAL -> R.string.live_orient_none
                            },
                        ),
                    )
                }
                FilledTonalButton(
                    onClick = {
                        grid = grid.next()
                        prefs.gridMode = grid
                    },
                    contentPadding = tight,
                ) {
                    Text(
                        stringResource(
                            when (grid) {
                                GridMode.OFF -> R.string.grid_off
                                GridMode.GRID_3X3 -> R.string.grid_3x3
                                GridMode.GRID_4X4 -> R.string.grid_4x4
                            },
                        ),
                    )
                }
                FilledTonalButton(
                    onClick = if (running) controller::stop else controller::start,
                    contentPadding = tight,
                ) {
                    Text(stringResource(if (running) R.string.button_live_stop else R.string.button_live_start))
                }
            }
        }

        val detail = (state as? LiveState.Problem)?.detail.orEmpty()
        if (detail.isNotEmpty()) {
            Text(detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }

        ControlPanel(controls)
    }
}

@Composable
private fun GridOverlay(mode: GridMode, modifier: Modifier = Modifier) {
    if (mode == GridMode.OFF) return
    Canvas(modifier = modifier) {
        GridGeometry.lines(size.width, size.height, mode).forEach { line ->
            drawLine(
                color = Color.White.copy(alpha = 0.7f),
                start = Offset(line.x1, line.y1),
                end = Offset(line.x2, line.y2),
                strokeWidth = 2f,
            )
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

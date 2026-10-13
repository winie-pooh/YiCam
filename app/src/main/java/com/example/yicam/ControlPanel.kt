/**
 * Responsible for: the localized control panel of the live screen: Video/Photo switch, record or photo button, capture mode buttons, zoom slider and the last result note.
 * Not responsible for: sending commands or deriving the state (CameraController.kt) or the texts themselves (the strings.xml files).
 */
package com.example.yicam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun ControlPanel(controller: CameraController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val enabled = state.available && !state.busy
    val tight = PaddingValues(horizontal = 8.dp, vertical = 6.dp)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ModeButton(R.string.mode_video, state.mode == CameraMode.VIDEO, enabled && !state.recording, tight, Modifier.weight(1f)) {
                controller.setMode(CameraMode.VIDEO)
            }
            ModeButton(R.string.mode_photo, state.mode == CameraMode.PHOTO, enabled && !state.recording, tight, Modifier.weight(1f)) {
                controller.setMode(CameraMode.PHOTO)
            }
        }

        if (state.mode == CameraMode.PHOTO) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ModeButton(R.string.capture_single, state.captureMode == CaptureMode.SINGLE, enabled, tight, Modifier.weight(1f)) {
                    controller.setCaptureMode(CaptureMode.SINGLE)
                }
                ModeButton(R.string.capture_timelapse, state.captureMode == CaptureMode.TIMELAPSE, enabled, tight, Modifier.weight(1f)) {
                    controller.setCaptureMode(CaptureMode.TIMELAPSE)
                }
                ModeButton(R.string.capture_burst, state.captureMode == CaptureMode.BURST, enabled, tight, Modifier.weight(1f)) {
                    controller.setCaptureMode(CaptureMode.BURST)
                }
                ModeButton(R.string.capture_delayed, state.captureMode == CaptureMode.DELAYED, enabled, tight, Modifier.weight(1f)) {
                    controller.setCaptureMode(CaptureMode.DELAYED)
                }
            }
        }

        // The main action depends on the mode and on what the camera is doing now.
        val actionLabel: Int
        val action: () -> Unit
        when {
            state.recording -> { actionLabel = R.string.button_record_stop; action = controller::stopRecording }
            state.timelapseRunning -> { actionLabel = R.string.button_timelapse_stop; action = controller::stopTimelapse }
            state.mode == CameraMode.PHOTO -> { actionLabel = R.string.button_photo; action = controller::takePhoto }
            else -> { actionLabel = R.string.button_record_start; action = controller::startRecording }
        }
        // While recording, only "stop" must stay pressable.
        Button(
            onClick = action,
            enabled = state.available && !state.busy && state.mode != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(actionLabel)) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.label_zoom, state.zoom ?: 0), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = (state.zoom ?: 0).toFloat(),
                onValueChange = { controller.setZoom(it.toInt()) },
                valueRange = 0f..100f,
                enabled = state.available,
                modifier = Modifier.weight(1f),
            )
        }

        state.note?.let { note ->
            val text = noteText(note)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = controller::dismissNote, contentPadding = tight) { Text("×") }
            }
        }
    }
}

@Composable
private fun ModeButton(
    label: Int,
    selected: Boolean,
    enabled: Boolean,
    padding: PaddingValues,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = padding) {
            Text(stringResource(label))
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = padding) {
            Text(stringResource(label))
        }
    }
}

@Composable
private fun noteText(note: ControlMessage): String = when (note.kind) {
    ControlNote.PHOTO_REQUESTED -> stringResource(R.string.note_photo)
    ControlNote.RECORDING_STARTED -> stringResource(R.string.note_recording_started)
    ControlNote.RECORDING_STOPPED -> stringResource(R.string.note_recording_stopped)
    ControlNote.TIMELAPSE_STOPPED -> stringResource(R.string.note_timelapse_stopped)
    ControlNote.TIMELAPSE_DONE -> stringResource(R.string.note_timelapse_done)
    ControlNote.SAVED -> stringResource(R.string.note_saved, note.detail)
    ControlNote.MODE_CHANGED -> stringResource(R.string.note_mode_changed)
    ControlNote.FAILED -> stringResource(R.string.note_failed, note.detail)
    ControlNote.NOT_CONNECTED -> stringResource(R.string.note_not_connected)
}

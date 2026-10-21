/**
 * Responsible for: the localized camera settings screen: opening it ready to use (no preview, no locked rows), the recording message with a Stop recording button, the list of settings in groups (switch for on/off, a choice dialog for the rest, greyed out when the camera locks a setting, with an Unlock button), explanations and translated values, the Sync time button, Apply and Discard, the result note.
 * Not responsible for: reading or sending settings (CameraSettings.kt), which keys are shown (SettingsCatalog.kt) or the texts themselves (the strings.xml files of values, values-es and values-uk).
 */
package com.example.yicam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(settings: CameraSettings, controls: CameraController, onBack: () -> Unit) {
    val state by settings.state.collectAsState()
    var dialogFor by remember { mutableStateOf<String?>(null) }

    // On entry: leave the preview state by itself and read the list.
    LaunchedEffect(Unit) { settings.screenOpened() }
    // Again when the link comes back (after the automatic new session) or the camera stops recording.
    LaunchedEffect(state.available, state.cameraBusy) {
        if (state.available && !state.cameraBusy) settings.open()
    }

    Column(
        modifier = Modifier.safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("<") }
            Text(
                text = stringResource(R.string.title_camera_settings),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            OutlinedButton(onClick = settings::load, enabled = state.available && !state.loading && !state.applying) {
                Text(stringResource(R.string.button_reload))
            }
        }

        if (state.cameraBusy) {
            Text(
                stringResource(if (state.recording) R.string.settings_recording else R.string.settings_busy),
                color = MaterialTheme.colorScheme.error,
            )
            if (state.recording) {
                Button(onClick = { controls.stopRecording() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_stop_recording))
                }
            } else {
                OutlinedButton(onClick = { controls.stopTimelapse() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.button_timelapse_stop))
                }
            }
        }
        if (state.rows.any { it.locked }) {
            Text(stringResource(R.string.settings_locked_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = settings::unlock,
                enabled = state.available && !state.loading && !state.applying,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.button_unlock)) }
        }
        if (state.loading) Text(stringResource(R.string.settings_loading, state.rows.size))

        OutlinedButton(
            onClick = settings::syncClock,
            enabled = state.available && !state.loading && !state.applying,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.button_sync_clock)) }

        state.note?.let { note ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(settingsNoteText(note), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = settings::dismissNote) { Text("\u00D7") }
            }
        }

        if (!state.available) {
            Text(stringResource(R.string.settings_not_connected))
        } else if (state.rows.isEmpty() && !state.loading) {
            Text(stringResource(R.string.settings_none))
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            var lastGroup: SettingGroup? = null
            for (row in state.rows) {
                if (row.group != lastGroup) {
                    lastGroup = row.group
                    val group = row.group
                    item(key = "group-$group") {
                        Text(
                            stringResource(groupTitle(group)),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                item(key = row.key) {
                    SettingRowView(row, enabled = !row.locked && !state.applying && !state.cameraBusy, onSwitch = { settings.edit(row.key, if (it) "on" else "off") }, onPick = { dialogFor = row.key })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = settings::discard,
                enabled = state.changedCount > 0 && !state.applying,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.button_discard)) }
            Button(
                onClick = settings::apply,
                enabled = state.changedCount > 0 && !state.applying && !state.loading && !state.cameraBusy && state.available,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.button_apply, state.changedCount)) }
        }
    }

    val dialogRow = state.rows.firstOrNull { it.key == dialogFor }
    if (dialogRow != null) {
        AlertDialog(
            onDismissRequest = { dialogFor = null },
            title = { Text(settingLabel(dialogRow.key)) },
            text = {
                LazyColumn {
                    items(dialogRow.options) { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                settings.edit(dialogRow.key, option)
                                dialogFor = null
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == dialogRow.value, onClick = null)
                            Text(optionLabel(dialogRow.key, option), modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialogFor = null }) { Text(stringResource(R.string.button_cancel)) } },
        )
    }
}

@Composable
private fun SettingRowView(row: SettingRow, enabled: Boolean, onSwitch: (Boolean) -> Unit, onPick: () -> Unit) {
    val changedMark = if (row.edited != null) " \u2022" else ""
    val labelColor = if (row.locked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else Color.Unspecified
    if (row.isSwitch) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(settingLabel(row.key) + changedMark, modifier = Modifier.weight(1f), color = labelColor)
            Switch(checked = row.value == "on", onCheckedChange = onSwitch, enabled = enabled)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onPick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(settingLabel(row.key) + changedMark, modifier = Modifier.weight(1f), color = labelColor)
            Text(optionLabel(row.key, row.value), style = MaterialTheme.typography.titleSmall, color = labelColor)
        }
    }
    val hint = settingHint(row.key)
    if (hint != null) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** The translated name of a key (string setting_<key>); an unknown key is shown as it is. */
@Composable
private fun settingLabel(key: String): String {
    val context = LocalContext.current
    val id = context.resources.getIdentifier("setting_$key", "string", context.packageName)
    return if (id != 0) stringResource(id) else key
}

/** An explanation under a setting (string setting_hint_<key>), or null if there is none. */
@Composable
private fun settingHint(key: String): String? {
    val context = LocalContext.current
    val id = context.resources.getIdentifier("setting_hint_$key", "string", context.packageName)
    return if (id != 0) stringResource(id) else null
}

/**
 * The translated text of a value: string opt_<key>_<value> (value written in lower case with "_" between words),
 * then opt_<value>, then seconds and minutes with a unit; any other value (resolutions, NTSC, S.Fine, ...) is shown as the camera writes it.
 */
@Composable
private fun optionLabel(key: String, value: String): String {
    val context = LocalContext.current
    val normal = SettingsCatalog.normalizeValue(value)
    for (name in listOf("opt_${key}_$normal", "opt_$normal")) {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        if (id != 0) return stringResource(id)
    }
    SettingsCatalog.secondsOf(value)?.let { return stringResource(R.string.unit_sec, it) }
    SettingsCatalog.minutesOf(value)?.let { return stringResource(R.string.unit_minutes, it) }
    return value
}

private fun groupTitle(group: SettingGroup): Int = when (group) {
    SettingGroup.VIDEO -> R.string.group_video
    SettingGroup.PHOTO -> R.string.group_photo
    SettingGroup.SOUND_LED -> R.string.group_sound_led
    SettingGroup.SYSTEM -> R.string.group_system
    SettingGroup.OTHER -> R.string.group_other
}

@Composable
private fun settingsNoteText(note: SettingsMessage): String = when (note.kind) {
    SettingsNote.APPLIED -> stringResource(R.string.note_settings_applied)
    SettingsNote.PARTIAL -> stringResource(R.string.note_settings_partial, note.detail)
    SettingsNote.FAILED -> stringResource(R.string.note_failed, note.detail)
    SettingsNote.CLOCK_SYNCED -> stringResource(R.string.note_clock_synced)
    SettingsNote.CAMERA_BUSY -> stringResource(R.string.settings_busy)
    SettingsNote.NOT_CONNECTED -> stringResource(R.string.note_not_connected)
}

/**
 * Responsible for: the localized files screen: folder list then file list (name, size, date, kind), refresh, empty and error states.
 * Not responsible for: reading listings (CameraFiles.kt), parsing (ListingParser.kt) or the texts themselves (the strings.xml files of values, values-es and values-uk).
 */
package com.example.yicam

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun FilesScreen(files: CameraFiles, onBack: () -> Unit) {
    val state by files.state.collectAsState()
    LaunchedEffect(Unit) { files.screenOpened() }
    BackHandler(enabled = state.folder != null) { files.backToFolders() }

    Column(
        modifier = Modifier.safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                if (state.folder != null) files.backToFolders() else onBack()
            }) { Text("<") }
            Text(
                text = state.folder ?: stringResource(R.string.title_files),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            OutlinedButton(
                onClick = files::refresh,
                enabled = state.available && !state.loading,
            ) { Text(stringResource(R.string.button_refresh_files)) }
        }

        if (state.recording) {
            Text(stringResource(R.string.files_recording_note), color = MaterialTheme.colorScheme.secondary)
        }
        if (state.loading) {
            Text(stringResource(R.string.files_loading))
        }
        val problem = state.problem
        if (problem != null) {
            Text(problemText(problem), color = MaterialTheme.colorScheme.error)
        }

        if (state.folder == null) {
            if (!state.loading && problem == null && state.folders.isEmpty() && state.available) {
                Text(stringResource(R.string.files_no_folders))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                items(state.folders, key = { it.name }) { folder ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.loading) { files.openFolder(folder.name) }.padding(vertical = 8.dp),
                    ) {
                        Text(folder.name, style = MaterialTheme.typography.titleMedium)
                        if (folder.dateText.isNotEmpty()) {
                            Text(folder.dateText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            if (!state.loading && problem == null && state.files.isEmpty()) {
                Text(stringResource(R.string.files_empty))
            }
            val context = LocalContext.current
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                items(state.files, key = { it.name }) { file ->
                    val size = Formatter.formatFileSize(context, file.sizeBytes)
                    val detail = if (file.dateText.isEmpty()) {
                        stringResource(R.string.files_size_kind, size, kindText(file.kind))
                    } else {
                        stringResource(R.string.files_size_date_kind, size, file.dateText, kindText(file.kind))
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun kindText(kind: FileKind): String = stringResource(
    when (kind) {
        FileKind.VIDEO -> R.string.files_kind_video
        FileKind.PHOTO -> R.string.files_kind_photo
        FileKind.THUMBNAIL, FileKind.OTHER -> R.string.files_kind_other
    },
)

@Composable
private fun problemText(problem: FilesProblem): String = when (problem) {
    FilesProblem.NotConnected -> stringResource(R.string.files_not_connected)
    FilesProblem.Timeout -> stringResource(R.string.files_timeout)
    FilesProblem.CameraBusy -> stringResource(R.string.files_busy)
    is FilesProblem.Failed -> stringResource(R.string.files_failed, problem.detail)
}

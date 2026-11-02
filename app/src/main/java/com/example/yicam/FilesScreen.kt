/**
 * Responsible for: the localized files screen: folder list then file list (name, size, date, kind), one-file download with progress, cancel and resume, the download-folder setting, refresh, empty and error states.
 * Not responsible for: reading listings (CameraFiles.kt), parsing (ListingParser.kt), download logic (FileDownload.kt) or the texts themselves (the strings.xml files of values, values-es and values-uk).
 */
package com.example.yicam

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun FilesScreen(
    files: CameraFiles,
    download: FileDownload,
    prefs: AppPrefs,
    onBack: () -> Unit,
) {
    val state by files.state.collectAsState()
    val downloadState by download.state.collectAsState()
    val context = LocalContext.current
    var folderDialog by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<FileEntry?>(null) }
    var pickMode by remember { mutableStateOf(PickMode.NONE) }

    LaunchedEffect(Unit) { files.screenOpened() }
    BackHandler(enabled = state.folder != null) { files.backToFolders() }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            pendingFile = null
            pickMode = PickMode.NONE
            return@rememberLauncherForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
        when (pickMode) {
            PickMode.SAVE -> {
                prefs.downloadFolderMode = DownloadFolderMode.TREE
                prefs.downloadTreeUri = uri.toString()
            }
            PickMode.ONCE, PickMode.NONE -> { }
        }
        val file = pendingFile
        pendingFile = null
        pickMode = PickMode.NONE
        if (file != null) {
            download.start(file, target = TreeDownloadTarget(context, uri))
        }
    }

    fun beginDownload(file: FileEntry) {
        when (prefs.downloadFolderMode) {
            DownloadFolderMode.ASK -> {
                pendingFile = file
                pickMode = PickMode.ONCE
                treeLauncher.launch(null)
            }
            DownloadFolderMode.TREE -> {
                if (prefs.downloadTreeUri.isNullOrEmpty()) {
                    pendingFile = file
                    pickMode = PickMode.SAVE
                    treeLauncher.launch(null)
                } else {
                    download.start(file)
                }
            }
            DownloadFolderMode.MEDIA_STORE -> download.start(file)
        }
    }

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

        OutlinedButton(onClick = { folderDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.download_folder) + ": " + folderLabel(prefs))
        }
        Text(stringResource(R.string.download_stops_live), style = MaterialTheme.typography.bodySmall)

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

        DownloadBanner(downloadState)

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
            val busy = downloadState is DownloadState.Running
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                items(state.files, key = { it.name }) { file ->
                    val size = Formatter.formatFileSize(context, file.sizeBytes)
                    val detail = if (file.dateText.isEmpty()) {
                        stringResource(R.string.files_size_kind, size, kindText(file.kind))
                    } else {
                        stringResource(R.string.files_size_date_kind, size, file.dateText, kindText(file.kind))
                    }
                    val active = downloadFile(downloadState)?.name == file.name
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                        if (active && downloadState is DownloadState.Running) {
                            val progress = (downloadState as DownloadState.Running).progress
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                            Text(progressText(progress), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = download::cancel) { Text(stringResource(R.string.button_cancel)) }
                        } else {
                            val failed = downloadState as? DownloadState.Failed
                            val cancelled = downloadState as? DownloadState.Cancelled
                            val canResume = (failed != null && failed.resumable && failed.file.name == file.name) ||
                                (cancelled != null && cancelled.file.name == file.name)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { beginDownload(file) },
                                    enabled = !busy,
                                ) {
                                    Text(
                                        if (canResume) stringResource(R.string.button_download_resume)
                                        else stringResource(R.string.button_download),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (downloadState is DownloadState.ConfirmSkip) {
        val skip = downloadState as DownloadState.ConfirmSkip
        AlertDialog(
            onDismissRequest = download::clear,
            title = { Text(stringResource(R.string.download_already)) },
            text = { Text(skip.file.name) },
            confirmButton = {
                TextButton(onClick = download::skipExisting) { Text(stringResource(R.string.button_download_skip)) }
            },
            dismissButton = {
                TextButton(onClick = download::replaceExisting) { Text(stringResource(R.string.button_download_again)) }
            },
        )
    }

    if (folderDialog) {
        AlertDialog(
            onDismissRequest = { folderDialog = false },
            title = { Text(stringResource(R.string.download_folder_dialog)) },
            text = {
                Column {
                    FolderChoice(DownloadFolderMode.MEDIA_STORE, prefs) {
                        prefs.downloadFolderMode = DownloadFolderMode.MEDIA_STORE
                        folderDialog = false
                    }
                    FolderChoice(DownloadFolderMode.TREE, prefs) {
                        pickMode = PickMode.SAVE
                        pendingFile = null
                        folderDialog = false
                        treeLauncher.launch(null)
                    }
                    FolderChoice(DownloadFolderMode.ASK, prefs) {
                        prefs.downloadFolderMode = DownloadFolderMode.ASK
                        folderDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { folderDialog = false }) { Text(stringResource(R.string.button_cancel)) }
            },
        )
    }
}

private enum class PickMode { NONE, SAVE, ONCE }

@Composable
private fun FolderChoice(mode: DownloadFolderMode, prefs: AppPrefs, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = prefs.downloadFolderMode == mode, onClick = onClick)
        Text(
            text = when (mode) {
                DownloadFolderMode.MEDIA_STORE -> stringResource(R.string.download_folder_mediastore)
                DownloadFolderMode.TREE -> stringResource(R.string.download_folder_tree)
                DownloadFolderMode.ASK -> stringResource(R.string.download_folder_ask)
            },
        )
    }
}

@Composable
private fun folderLabel(prefs: AppPrefs): String = when (prefs.downloadFolderMode) {
    DownloadFolderMode.MEDIA_STORE -> stringResource(R.string.download_folder_mediastore)
    DownloadFolderMode.TREE -> stringResource(R.string.download_folder_tree)
    DownloadFolderMode.ASK -> stringResource(R.string.download_folder_ask)
}

@Composable
private fun DownloadBanner(state: DownloadState) {
    when (state) {
        is DownloadState.Done -> Text(stringResource(R.string.download_done, state.file.name))
        is DownloadState.Cancelled -> Text(stringResource(R.string.download_cancelled))
        is DownloadState.Failed -> Text(failText(state.reason), color = MaterialTheme.colorScheme.error)
        else -> {}
    }
}

private fun downloadFile(state: DownloadState): FileEntry? = when (state) {
    is DownloadState.Running -> state.progress.file
    is DownloadState.Cancelled -> state.file
    is DownloadState.Done -> state.file
    is DownloadState.Failed -> state.file
    is DownloadState.ConfirmSkip -> state.file
    DownloadState.Idle -> null
}

@Composable
private fun progressText(progress: DownloadProgress): String {
    val context = LocalContext.current
    val received = Formatter.formatFileSize(context, progress.receivedBytes)
    val total = progress.totalBytes?.let { Formatter.formatFileSize(context, it) }
    val speed = Formatter.formatFileSize(context, progress.bytesPerSecond) + "/s"
    val body = if (total == null) {
        stringResource(R.string.download_progress_unknown, received, speed)
    } else {
        stringResource(R.string.download_progress, received, total, speed)
    }
    val left = progress.remainingMs ?: return body
    val eta = if (left >= 60_000L) {
        stringResource(R.string.download_eta, (left / 60_000L).toInt(), ((left % 60_000L) / 1000L).toInt())
    } else {
        stringResource(R.string.download_eta_sec, (left / 1000L).toInt().coerceAtLeast(1))
    }
    return "$body · $eta"
}

@Composable
private fun failText(reason: DownloadFailReason): String = stringResource(
    when (reason) {
        DownloadFailReason.NO_SPACE -> R.string.download_fail_no_space
        DownloadFailReason.LINK_LOST -> R.string.download_fail_link_lost
        DownloadFailReason.SIZE_MISMATCH -> R.string.download_fail_size
        DownloadFailReason.CAMERA_REFUSED -> R.string.download_fail_refused
        DownloadFailReason.NETWORK -> R.string.download_fail_network
    },
)

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

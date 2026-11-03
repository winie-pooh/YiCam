/**
 * Responsible for: the localized gallery grid of camera media with kind/folder filter chips, downloaded badges, multi-select download (reuses FileDownload), and opening the viewer.
 * Not responsible for: listing protocol (CameraFiles.kt), thumbnail bytes (ThumbnailLoader.kt), badge query (DownloadedBadge.kt) or the full-size viewer (ViewerScreen.kt).
 */
package com.example.yicam

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    files: CameraFiles,
    download: FileDownload,
    thumbLoader: ThumbnailLoader,
    badge: DownloadedBadge,
    prefs: AppPrefs,
    onBack: () -> Unit,
    onOpenViewer: (FileEntry) -> Unit,
) {
    val downloadState by download.state.collectAsState()
    var allFiles by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var folders by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var kindFilter by remember { mutableStateOf(GalleryKindFilter.ALL) }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(GallerySelection()) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        loading = true
        problem = null
        try {
            val f = withContext(Dispatchers.Default) { files.folders(force = false) }
            folders = f
            val collected = ArrayList<FileEntry>()
            for (folder in f) {
                val list = withContext(Dispatchers.Default) { files.files(folder.name, force = false) }
                collected.addAll(list)
            }
            allFiles = collected
        } catch (e: Exception) {
            problem = e.message ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { thumbLoader.cancelAll() }
    }

    val visible = remember(allFiles, kindFilter, folderFilter) {
        allFiles.filtered(kindFilter, folderFilter)
    }

    Column(
        modifier = Modifier.safeDrawingPadding().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("<") }
            Text(
                stringResource(R.string.title_gallery),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            if (selection.selected.isNotEmpty()) {
                OutlinedButton(onClick = {
                    selection.filesFrom(visible).forEach { download.start(it) }
                    selection = selection.clear()
                }) { Text(stringResource(R.string.button_download_selected)) }
                OutlinedButton(onClick = { selection = selection.clear() }) {
                    Text(stringResource(R.string.button_clear_selection))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            GalleryKindFilter.entries.forEach { k ->
                FilterChip(
                    selected = kindFilter == k,
                    onClick = { kindFilter = k },
                    label = {
                        Text(
                            when (k) {
                                GalleryKindFilter.ALL -> stringResource(R.string.filter_all)
                                GalleryKindFilter.PHOTOS -> stringResource(R.string.filter_photos)
                                GalleryKindFilter.VIDEOS -> stringResource(R.string.filter_videos)
                            },
                        )
                    },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = folderFilter == null,
                onClick = { folderFilter = null },
                label = { Text(stringResource(R.string.filter_all_folders)) },
            )
            folders.forEach { folder ->
                FilterChip(
                    selected = folderFilter == folder.name,
                    onClick = { folderFilter = folder.name },
                    label = { Text(folder.name) },
                )
            }
        }

        when {
            loading -> Text(stringResource(R.string.gallery_loading))
            problem != null -> Text(problem!!, color = MaterialTheme.colorScheme.error)
            visible.isEmpty() -> Text(stringResource(R.string.gallery_empty))
            else -> {
                if (downloadState is DownloadState.Running) {
                    val p = (downloadState as DownloadState.Running).progress
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${p.file.name}: ${p.receivedBytes} / ${p.totalBytes ?: \"?\"} B", style = MaterialTheme.typography.bodySmall)
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    state = gridState,
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { GallerySelection.keyOf(it) }) { file ->
                        GalleryCell(
                            file = file,
                            selected = selection.contains(file),
                            downloaded = badge.isDownloaded(file),
                            thumbLoader = thumbLoader,
                            onClick = {
                                if (selection.selected.isNotEmpty()) selection = selection.toggle(file)
                                else onOpenViewer(file)
                            },
                            onLongClick = { selection = selection.toggle(file) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryCell(
    file: FileEntry,
    selected: Boolean,
    downloaded: Boolean,
    thumbLoader: ThumbnailLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var bytes by remember(file.name, file.sizeBytes) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(file.name, file.sizeBytes) {
        thumbLoader.request(file) { bytes = it }
    }
    val bitmap = remember(bytes) {
        bytes?.let { b ->
            try { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } catch (_: Exception) { null }
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(if (file.kind == FileKind.VIDEO) "▶" else "🖼", modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall)
        }
        if (downloaded) {
            Text("✓", color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
        }
        Text(file.name.removePrefix("YDXJ").take(8), style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(2.dp))
    }
}

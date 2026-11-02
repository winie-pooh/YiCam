/**
 * Responsible for: reading the camera's folders and files (cd to an absolute path, then list): cache for one screen visit, error mapping (not connected, timeout, camera busy), and logging of results and failures to the traffic log.
 * Not responsible for: parsing listings (ListingParser.kt), drawing (FilesScreen.kt) or the link to the camera (CameraSession.kt).
 */
package com.example.yicam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class FilesProblem {
    data object NotConnected : FilesProblem()
    data object Timeout : FilesProblem()
    data object CameraBusy : FilesProblem()
    data class Failed(val detail: String) : FilesProblem()
}

data class FilesUiState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val recording: Boolean = false,
    val folder: String? = null,
    val folders: List<FolderEntry> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val problem: FilesProblem? = null,
)

class CameraFiles(
    private val session: CameraSession,
    private val scope: CoroutineScope,
) {
    private data class Local(
        val loading: Boolean = false,
        val folder: String? = null,
        val folders: List<FolderEntry> = emptyList(),
        val files: List<FileEntry> = emptyList(),
        val problem: FilesProblem? = null,
    )

    private val local = MutableStateFlow(Local())
    private var folderCache: List<FolderEntry>? = null
    private val fileCache = HashMap<String, List<FileEntry>>()

    val state: StateFlow<FilesUiState> = combine(session.state, session.status, local) { link, status, own ->
        FilesUiState(
            available = link is SessionState.Connected,
            loading = own.loading,
            recording = status.appStatus in CameraController.RECORDING_STATUSES,
            folder = own.folder,
            folders = own.folders,
            files = own.files,
            problem = own.problem,
        )
    }.stateIn(scope, SharingStarted.Eagerly, FilesUiState())

    init {
        scope.launch {
            session.state.map { it is SessionState.Connected }.distinctUntilChanged().collect { connected ->
                if (!connected) {
                    folderCache = null
                    fileCache.clear()
                    local.value = Local(problem = FilesProblem.NotConnected)
                }
            }
        }
    }

    /** New visit: drop the cache and read the folder list. */
    fun screenOpened() {
        folderCache = null
        fileCache.clear()
        local.value = Local()
        loadFolders(force = true)
    }

    fun refresh() {
        val folder = local.value.folder
        if (folder == null) loadFolders(force = true) else loadFiles(folder, force = true)
    }

    fun openFolder(name: String) = loadFiles(name, force = false)

    fun backToFolders() {
        val cached = folderCache
        local.update { it.copy(folder = null, files = emptyList(), problem = null, folders = cached.orEmpty()) }
        if (cached == null) loadFolders(force = false)
    }

    fun loadFolders(force: Boolean) {
        if (local.value.loading) return
        if (session.state.value !is SessionState.Connected) {
            local.update { it.copy(problem = FilesProblem.NotConnected) }
            return
        }
        val cached = folderCache
        if (!force && cached != null) {
            local.update { it.copy(folder = null, folders = cached, files = emptyList(), problem = null) }
            return
        }
        local.update { it.copy(loading = true, folder = null, problem = null) }
        scope.launch {
            try {
                val folders = folders(force = true)
                local.update { it.copy(loading = false, folders = folders, files = emptyList(), folder = null, problem = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.log.sys("[FILES] [ERR] folder list failed: ${e.message ?: e.javaClass.simpleName}")
                local.update { it.copy(loading = false, problem = problemOf(e)) }
            }
        }
    }

    fun loadFiles(folder: String, force: Boolean) {
        if (local.value.loading) return
        if (session.state.value !is SessionState.Connected) {
            local.update { it.copy(problem = FilesProblem.NotConnected) }
            return
        }
        val cached = fileCache[folder]
        if (!force && cached != null) {
            local.update { it.copy(folder = folder, files = cached, problem = null) }
            return
        }
        local.update { it.copy(loading = true, folder = folder, problem = null) }
        scope.launch {
            try {
                val files = files(folder, force = true)
                local.update { it.copy(loading = false, folder = folder, files = files, problem = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.log.sys("[FILES] [ERR] list of $folder failed: ${e.message ?: e.javaClass.simpleName}")
                local.update { it.copy(loading = false, folder = folder, problem = problemOf(e)) }
            }
        }
    }

    /** cd to the media root, then list. Cached until [force] or a new screen visit. */
    suspend fun folders(force: Boolean = false): List<FolderEntry> {
        if (!force) folderCache?.let { return it }
        val reply = fetchListing(ListingParser.MEDIA_ROOT)
        val folders = ListingParser.folders(reply)
        folderCache = folders
        session.log.sys("[FILES] ${folders.size} folders on the camera")
        return folders
    }

    /** cd to MEDIA_ROOT/[folder], then list. Cached until [force] or a new screen visit. */
    suspend fun files(folder: String, force: Boolean = false): List<FileEntry> {
        if (!force) fileCache[folder]?.let { return it }
        val reply = fetchListing("${ListingParser.MEDIA_ROOT}/$folder")
        val files = ListingParser.files(reply, folder)
        fileCache[folder] = files
        session.log.sys("[FILES] $folder: ${files.size} files")
        return files
    }

    private suspend fun fetchListing(absolutePath: String): CameraMessage.Response {
        val api = session.currentApi ?: throw CameraException.NotConnected()
        val cd = api.send(Cmd.CHANGE_DIR, listOf("param" to absolutePath))
        if (!cd.isOk) throw listingFailure(cd)
        val list = api.send(Cmd.LIST_FILES, listOf("param" to ListingParser.LIST_PARAM))
        if (!list.isOk) throw listingFailure(list)
        return list
    }

    private fun listingFailure(reply: CameraMessage.Response): Exception =
        if (reply.rval == RVAL_BUSY) CameraBusyException() else ListingFailedException(reply.rval)

    private fun problemOf(error: Exception): FilesProblem = when (error) {
        is CameraException.NotConnected -> FilesProblem.NotConnected
        is CameraException.Timeout -> FilesProblem.Timeout
        is CameraBusyException -> FilesProblem.CameraBusy
        is ListingFailedException -> FilesProblem.Failed("rval ${error.rval}")
        else -> FilesProblem.Failed(error.message ?: error.javaClass.simpleName)
    }

    private class CameraBusyException : Exception("camera busy")
    private class ListingFailedException(val rval: Int?) : Exception("rval $rval")

    companion object {
        const val RVAL_BUSY = -21
    }
}

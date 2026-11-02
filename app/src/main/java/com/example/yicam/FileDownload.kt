/**
 * Responsible for: one file download at a time: stopping the live view, checking free space, skip-if-already-there, resume, cancel, mapping errors (no space, link lost, size mismatch, camera refused).
 * Not responsible for: HTTP (Downloader.kt), writing bytes (DownloadSink.kt) or drawing (FilesScreen.kt).
 */
package com.example.yicam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileDownload(
    private val session: CameraSession,
    private val scope: CoroutineScope,
    private val targetOf: () -> DownloadTarget,
    private val onStopLive: () -> Unit,
    private val downloader: Downloader = Downloader(),
    private val urlOf: (FileEntry) -> String = { Downloader.cameraFileUrl(it.folder, it.name) },
) {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val gate = Any()
    private var job: Job? = null

    fun start(file: FileEntry, replace: Boolean = false, target: DownloadTarget? = null) {
        val chosen: DownloadTarget
        synchronized(gate) {
            if (_state.value is DownloadState.Running) return
            if (session.state.value !is SessionState.Connected) {
                _state.value = DownloadState.Failed(file, DownloadFailReason.LINK_LOST, resumable = true)
                return
            }
            chosen = target ?: targetOf()
            if (!replace) {
                val existing = chosen.existingComplete(file)
                if (existing != null) {
                    _state.value = DownloadState.ConfirmSkip(file, existing)
                    return
                }
            } else {
                chosen.removeComplete(file)
            }
            job?.cancel()
            _state.value = DownloadState.Running(
                DownloadProgress(file, 0L, file.sizeBytes.takeIf { it > 0L }, 0L, null),
            )
            job = scope.launch { run(file, chosen) }
        }
    }

    fun skipExisting() {
        val current = _state.value as? DownloadState.ConfirmSkip ?: return
        _state.value = DownloadState.Done(current.file, current.uri)
    }

    fun replaceExisting() {
        val current = _state.value as? DownloadState.ConfirmSkip ?: return
        start(current.file, replace = true)
    }

    fun cancel() {
        synchronized(gate) {
            val current = _state.value
            val file = when (current) {
                is DownloadState.Running -> current.progress.file
                else -> return
            }
            job?.cancel()
            job = null
            _state.value = DownloadState.Cancelled(file)
        }
    }

    fun clear() {
        if (_state.value is DownloadState.Running) return
        _state.value = DownloadState.Idle
    }

    private suspend fun run(file: FileEntry, target: DownloadTarget) {
        onStopLive()
        val sink = try {
            target.open(file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.value = DownloadState.Failed(file, DownloadFailReason.NETWORK, resumable = true)
            return
        }
        val remaining = (file.sizeBytes - sink.length()).coerceAtLeast(0L)
        if (target.availableBytes() < remaining) {
            sink.close()
            _state.value = DownloadState.Failed(file, DownloadFailReason.NO_SPACE, resumable = true)
            return
        }
        _state.value = DownloadState.Running(
            DownloadProgress(file, sink.length(), file.sizeBytes.takeIf { it > 0L }, 0L, null),
        )
        val watch = scope.launch {
            session.state.collect { link ->
                if (link !is SessionState.Connected && _state.value is DownloadState.Running) {
                    job?.cancel()
                }
            }
        }
        try {
            downloader.download(urlOf(file), sink, file).collect { progress ->
                if (_state.value !is DownloadState.Running) return@collect
                val total = progress.totalBytes ?: file.sizeBytes.takeIf { it > 0L }
                _state.value = DownloadState.Running(progress.copy(file = file, totalBytes = total))
            }
            if (_state.value !is DownloadState.Running) return
            val have = sink.length()
            val expected = file.sizeBytes
            if (expected > 0L && have != expected) {
                _state.value = DownloadState.Failed(file, DownloadFailReason.SIZE_MISMATCH, resumable = true)
                return
            }
            val uri = sink.markComplete()
            _state.value = DownloadState.Done(file, uri)
        } catch (e: CancellationException) {
            if (_state.value is DownloadState.Cancelled) throw e
            val lost = session.state.value !is SessionState.Connected
            _state.value = if (lost) {
                DownloadState.Failed(file, DownloadFailReason.LINK_LOST, resumable = true)
            } else {
                DownloadState.Cancelled(file)
            }
        } catch (_: Downloader.DownloadHttpException) {
            _state.value = DownloadState.Failed(file, DownloadFailReason.CAMERA_REFUSED, resumable = false)
        } catch (_: Downloader.DownloadSizeException) {
            _state.value = DownloadState.Failed(file, DownloadFailReason.SIZE_MISMATCH, resumable = true)
        } catch (_: Downloader.DownloadRangeException) {
            _state.value = DownloadState.Failed(file, DownloadFailReason.NETWORK, resumable = true)
        } catch (_: Downloader.DownloadNetworkException) {
            val lost = session.state.value !is SessionState.Connected
            _state.value = DownloadState.Failed(
                file,
                if (lost) DownloadFailReason.LINK_LOST else DownloadFailReason.NETWORK,
                resumable = true,
            )
        } finally {
            watch.cancel()
        }
    }
}

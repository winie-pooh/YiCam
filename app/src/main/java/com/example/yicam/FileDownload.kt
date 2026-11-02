/**
 * Responsible for: one file download at a time: stopping the live view, waking the camera's web server (msg_id 259 "none_force", the PC program's "fix for web port"), checking free space, skip-if-already-there, resume, cancel, mapping errors (no space, link lost, size mismatch, camera refused), and logging every step and failure to the traffic log.
 * Not responsible for: HTTP (Downloader.kt), writing bytes (DownloadSink.kt) or drawing (FilesScreen.kt).
 */
package com.example.yicam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileDownload(
    private val session: CameraSession,
    private val scope: CoroutineScope,
    private val targetOf: () -> DownloadTarget,
    private val onStopLive: () -> Unit,
    private val downloader: Downloader = Downloader(log = { session.log.sys(it) }),
    private val urlOf: (FileEntry) -> String = { Downloader.cameraFileUrl(it.folder, it.name) },
    /** Pause after the web server wake-up before the HTTP request; the camera needs a moment. Tests use ~0. */
    private val wakeDelayMs: Long = 800L,
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
                log("[DL] ${file.name}: refused, the camera is not connected")
                _state.value = DownloadState.Failed(file, DownloadFailReason.LINK_LOST, resumable = true)
                return
            }
            chosen = target ?: targetOf()
            if (!replace) {
                val existing = chosen.existingComplete(file)
                if (existing != null) {
                    log("[DL] ${file.name}: already downloaded, asking the user")
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
        log("[DL] ${current.file.name}: the user kept the existing copy")
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
            log("[DL] ${file.name}: cancelled by the user")
            _state.value = DownloadState.Cancelled(file)
        }
    }

    fun clear() {
        if (_state.value is DownloadState.Running) return
        _state.value = DownloadState.Idle
    }

    private suspend fun run(file: FileEntry, target: DownloadTarget) {
        val startedNs = System.nanoTime()
        log("[DL] ${file.name}: start (${file.sizeBytes} bytes)")
        onStopLive()
        wakeWebServer(file)
        val sink = try {
            target.open(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[DL] ${file.name}: FAILED, cannot open the destination: ${e.message ?: e.javaClass.simpleName}")
            _state.value = DownloadState.Failed(file, DownloadFailReason.NETWORK, resumable = true)
            return
        }
        val resumeFrom = sink.length()
        if (resumeFrom > 0L) log("[DL] ${file.name}: resume from $resumeFrom bytes")
        val remaining = (file.sizeBytes - resumeFrom).coerceAtLeast(0L)
        val free = target.availableBytes()
        if (free < remaining) {
            sink.close()
            log("[DL] ${file.name}: FAILED, not enough space: needs $remaining bytes, has $free")
            _state.value = DownloadState.Failed(file, DownloadFailReason.NO_SPACE, resumable = true)
            return
        }
        _state.value = DownloadState.Running(
            DownloadProgress(file, resumeFrom, file.sizeBytes.takeIf { it > 0L }, 0L, null),
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
                log("[DL] ${file.name}: FAILED, size mismatch: got $have of $expected bytes")
                _state.value = DownloadState.Failed(file, DownloadFailReason.SIZE_MISMATCH, resumable = true)
                return
            }
            val uri = sink.markComplete()
            val ms = (System.nanoTime() - startedNs) / 1_000_000
            log("[DL] ${file.name}: done, $have bytes in $ms ms")
            _state.value = DownloadState.Done(file, uri)
        } catch (e: CancellationException) {
            if (_state.value is DownloadState.Cancelled) throw e
            val lost = session.state.value !is SessionState.Connected
            log("[DL] ${file.name}: ${if (lost) "FAILED, the link to the camera was lost" else "cancelled"}")
            _state.value = if (lost) {
                DownloadState.Failed(file, DownloadFailReason.LINK_LOST, resumable = true)
            } else {
                DownloadState.Cancelled(file)
            }
        } catch (e: Downloader.DownloadHttpException) {
            log("[DL] ${file.name}: FAILED, the camera refused the download (HTTP ${e.code})")
            _state.value = DownloadState.Failed(file, DownloadFailReason.CAMERA_REFUSED, resumable = false)
        } catch (e: Downloader.DownloadSizeException) {
            log("[DL] ${file.name}: FAILED, ${e.message}")
            _state.value = DownloadState.Failed(file, DownloadFailReason.SIZE_MISMATCH, resumable = true)
        } catch (e: Downloader.DownloadRangeException) {
            log("[DL] ${file.name}: FAILED, resume problem: ${e.message}")
            _state.value = DownloadState.Failed(file, DownloadFailReason.NETWORK, resumable = true)
        } catch (e: Downloader.DownloadNetworkException) {
            val lost = session.state.value !is SessionState.Connected
            log("[DL] ${file.name}: FAILED, ${if (lost) "the link to the camera was lost" else "network"}: ${e.message}")
            _state.value = DownloadState.Failed(
                file,
                if (lost) DownloadFailReason.LINK_LOST else DownloadFailReason.NETWORK,
                resumable = true,
            )
        } finally {
            watch.cancel()
        }
    }

    /**
     * The camera's HTTP server (port 80) sleeps while the camera is not in viewfinder mode; msg_id 259
     * "none_force" wakes it (the PC program's "fix for web port", docs/protocol-findings.md). This does NOT
     * start the live view on the phone: no player is involved. A refusal or a failed wake-up is only logged;
     * the download itself shows whether the server is up.
     */
    private suspend fun wakeWebServer(file: FileEntry) {
        val api = session.currentApi
        if (api == null) {
            log("[DL] ${file.name}: [WARN] no session, the web server wake-up is skipped")
            return
        }
        try {
            log("[DL] ${file.name}: waking the camera web server (msg_id 259 none_force)")
            val reply = api.send(Cmd.START_PREVIEW, listOf("param" to "none_force"))
            when {
                reply.isOk -> {
                    delay(wakeDelayMs)
                    log("[DL] ${file.name}: web server wake-up accepted")
                }
                reply.rval == RVAL_BUSY -> // seen while the camera records; whether HTTP works then is [VERIFY]
                    log("[DL] ${file.name}: [WARN] the camera is busy (rval $RVAL_BUSY); trying the download anyway")
                else ->
                    log("[DL] ${file.name}: [WARN] the wake-up was answered with rval ${reply.rval}; trying the download anyway")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[DL] ${file.name}: [WARN] the wake-up failed: ${e.message ?: e.javaClass.simpleName}; trying the download anyway")
        }
    }

    private fun log(text: String) = session.log.sys(text)

    private companion object {
        /** rval of msg_id 259 while the camera is recording (docs/protocol-findings.md). */
        const val RVAL_BUSY = -21
    }
}

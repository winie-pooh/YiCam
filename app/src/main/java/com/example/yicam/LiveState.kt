/**
 * Responsible for: the states of the live view (idle, starting the preview, connecting the stream, playing, problem with a reason) and the list of problems.
 * Not responsible for: changing the state (LiveViewController.kt) or the texts shown for them (StatusScreen.kt, LiveScreen.kt, res/values folders).
 */
package com.example.yicam

enum class LiveProblem {
    NOT_CONNECTED,
    RECORDING,
    PREVIEW_DISABLED,
    PREVIEW_COMMAND_FAILED,
    STREAM_FAILED,
    STREAM_TIMEOUT,
}

sealed interface LiveState {
    data object Idle : LiveState

    /** The command that starts the preview on the camera (msg_id 259) is running. */
    data object StartingPreview : LiveState

    /** The preview runs on the camera; the video player is connecting to the RTSP stream. */
    data object ConnectingStream : LiveState

    /** A picture is on screen. [startMs] is the time from pressing Start to the first picture. */
    data class Playing(val startMs: Long) : LiveState

    /** Nothing is playing. [detail] is English text for the log and the technical line on screen (may be empty). */
    data class Problem(val problem: LiveProblem, val detail: String) : LiveState
}

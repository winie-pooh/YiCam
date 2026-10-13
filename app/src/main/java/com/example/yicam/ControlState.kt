/**
 * Responsible for: the data models of the camera controls (photo/video mode, capture mode, what the screen shows and the last result note).
 * Not responsible for: sending commands or deriving the state (CameraController.kt) or drawing it (ControlPanel.kt).
 */
package com.example.yicam

/** The camera's main mode: setting system_mode "record" (video) or "capture" (photo). */
enum class CameraMode(val value: String) {
    VIDEO("record"),
    PHOTO("capture");

    companion object {
        fun fromValue(value: String?): CameraMode? = entries.firstOrNull { it.value == value }
    }
}

/** How photos are taken: setting capture_mode. The values are the camera's own strings (plan, table 2.4). */
enum class CaptureMode(val value: String) {
    SINGLE("precise quality"),
    TIMELAPSE("precise quality cont."),
    BURST("burst quality"),
    DELAYED("precise self quality");

    companion object {
        fun fromValue(value: String?): CaptureMode? = entries.firstOrNull { it.value == value }
    }
}

/** What happened last. The screen turns the kind into a translated sentence; [detail] is shown as is (a file name or an error). */
enum class ControlNote {
    PHOTO_REQUESTED,
    RECORDING_STARTED,
    RECORDING_STOPPED,
    TIMELAPSE_STOPPED,
    TIMELAPSE_DONE,
    SAVED,
    MODE_CHANGED,
    FAILED,
    NOT_CONNECTED,
}

data class ControlMessage(val kind: ControlNote, val detail: String = "")

data class ControlState(
    /** False unless the link to the camera is up. */
    val available: Boolean = false,
    /** Null until the settings were read. */
    val mode: CameraMode? = null,
    val captureMode: CaptureMode? = null,
    /** A video is being recorded (app_status "recording"). */
    val recording: Boolean = false,
    /** A time-lapse or burst is running (precise_cont_capturing "on" or a capturing app_status). */
    val timelapseRunning: Boolean = false,
    /** Zoom 0..100 as last set or read; null if unknown. */
    val zoom: Int? = null,
    /** A command is waiting for the camera's reply. */
    val busy: Boolean = false,
    val note: ControlMessage? = null,
)

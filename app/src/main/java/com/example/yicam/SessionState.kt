/**
 * Responsible for: the states of the camera link as shown to the user (searching Wi-Fi, connecting, connected, reconnecting, camera off, error with a reason) and the list of failure reasons.
 * Not responsible for: changing the state (CameraSession.kt) or the low-level state of one connection (ConnectionState.kt).
 */
package com.example.yicam

/** Why the link is not working. Mapped to a user-facing text in the UI (strings.xml). */
enum class FailReason {
    NO_WIFI,
    NOT_CAMERA_NETWORK,
    PERMISSION_DENIED,
    TIMEOUT,
    CONNECTION_LOST,
    PROTOCOL,
}

sealed interface SessionState {
    /** The user has not asked to connect, or pressed Disconnect. */
    data object Disconnected : SessionState

    data object SearchingWifi : SessionState

    /** Android 17+: the local network permission is missing. */
    data object PermissionRequired : SessionState

    data object Connecting : SessionState

    /** Session is open; [token] is the current camera session token. */
    data class Connected(val token: Int) : SessionState

    /** The link was lost and the app is waiting [nextDelayMs] before attempt number [attempt]. */
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long, val reason: FailReason) : SessionState

    /** The camera Wi-Fi disappeared and did not come back: the camera is switched off or out of range. */
    data object CameraOff : SessionState

    /** The app stopped trying. [detail] is English text for the log, the UI shows a translated text for [reason]. */
    data class Error(val reason: FailReason, val detail: String) : SessionState
}

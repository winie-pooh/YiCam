/**
 * Responsible for: the possible states of the camera connection, as shown to the UI.
 * Not responsible for: changing the state (CameraApi.kt does that).
 */
package com.example.yicam

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    /** Session is open; [token] is the camera session token used in every command. */
    data class Connected(val token: Int) : ConnectionState

    /** Connect failed or the connection was lost. [reason] is human readable (English, for logs). */
    data class Failed(val reason: String) : ConnectionState
}

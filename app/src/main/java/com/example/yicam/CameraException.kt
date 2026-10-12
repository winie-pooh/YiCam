/**
 * Responsible for: the error types of camera communication.
 * Not responsible for: deciding when they are thrown (CameraApi.kt, CameraConnection.kt) or how they are shown (UI).
 */
package com.example.yicam

sealed class CameraException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No open session and automatic reconnect is off or the user disconnected on purpose. */
    class NotConnected : CameraException("Not connected to the camera")

    /** TCP connect or Wi-Fi lookup failed. */
    class ConnectFailed(message: String, cause: Throwable? = null) : CameraException(message, cause)

    /** The socket closed or failed while a command was in flight. */
    class ConnectionLost(message: String, cause: Throwable? = null) : CameraException(message, cause)

    /** The camera did not answer in time. The connection may still be fine. */
    class Timeout(val msgId: Int, val timeoutMs: Long) :
        CameraException("No reply to msg_id $msgId within $timeoutMs ms")

    /** The camera answered the session handshake with an error, or the reply was unusable. */
    class Rejected(val msgId: Int, val rval: Int?, message: String) : CameraException(message)

    /** A raw command typed by the user is not valid JSON or has no msg_id. */
    class BadRequest(message: String) : CameraException(message)
}

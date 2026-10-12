/**
 * Responsible for: the data models of what the app knows about the camera: CameraStatus (battery, SD card, mode) and SettingsSnapshot (key/value copy of the camera settings, without secrets).
 * Not responsible for: reading them from the camera (CameraSession.kt) or parsing replies (StatusParser.kt).
 */
package com.example.yicam

/** Every field is null until it has been read from the camera. Sizes are in kilobytes (KiB) as the camera reports them. */
data class CameraStatus(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val sdTotalKb: Long? = null,
    val sdFreeKb: Long? = null,
    /** Set when the camera reports a card size too small to be a real card (seen: 17490 KB). Then sdTotalKb and sdFreeKb are null. */
    val sdUnusableReportedKb: Long? = null,
    val appStatus: String? = null,
    val sdCardStatus: String? = null,
)

/** All settings returned by msg_id 3, as text. The Wi-Fi password and the serial number are never included. */
data class SettingsSnapshot(
    val values: Map<String, String>,
    val readAtMs: Long,
)

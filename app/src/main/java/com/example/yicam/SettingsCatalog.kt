/**
 * Responsible for: what the settings screen knows about camera setting keys: which are hidden, which group a key belongs to, the order inside a group and which values mean a switch, the built-in values of settings the camera does not list, and how a value is turned into a resource name for its translation.
 * Not responsible for: reading or changing settings (CameraSettings.kt), drawing (SettingsScreen.kt) or the translated names (the strings.xml files: setting_<key>).
 */
package com.example.yicam

enum class SettingGroup { VIDEO, PHOTO, SOUND_LED, SYSTEM, OTHER }

object SettingsCatalog {
    /**
     * Never shown as a row: controls with their own place (capture_mode, system_mode, camera_clock), things that must not be
     * changed here (Wi-Fi name and password, reboot, factory reset, a running self-timer) and read-only status keys.
     * timelapse_video_duration is free text with an unknown format, so it stays hidden until the format is known; timelapse_photo is read only even when the camera is idle.
     */
    val HIDDEN: Set<String> = setOf(
        "capture_mode", "system_mode", "camera_clock", "precise_self_running",
        "wifi_ssid", "wifi_password", "serial_number", "dev_reboot", "restore_factory_settings",
        "app_status", "timelapse_video_duration", "timelapse_photo",
    )

    /** Settings whose allowed values change when the video standard changes. */
    val DEPENDS_ON_VIDEO_STANDARD: Set<String> = setOf("video_resolution", "timelapse_video_resolution")

    /**
     * Settings the camera reports but does not list with values ("readonly:" with nothing after it), so they were missing from the
     * screen. They get these built-in values and are sent like any other setting; if the camera refuses, the row stays marked as changed.
     */
    val BUILT_IN_OPTIONS: List<Pair<String, List<String>>> = listOf(
        "warp_enable" to listOf("on", "off"),
        "meter_mode" to listOf("center", "average", "spot"),
    )

    const val VIDEO_STANDARD = "video_standard"
    const val CAMERA_CLOCK = "camera_clock"

    private val ORDER: List<Pair<SettingGroup, List<String>>> = listOf(
        SettingGroup.VIDEO to listOf(
            VIDEO_STANDARD, "video_resolution", "video_quality", "video_stamp", "video_rotate", "loop_record",
            "rec_mode", "rec_default_mode", "timelapse_video", "timelapse_video_resolution", "record_photo_time",
            "emergency_file_backup", "warp_enable", "meter_mode", "auto_low_light",
        ),
        SettingGroup.PHOTO to listOf(
            "photo_size", "photo_quality", "photo_stamp", "capture_default_mode", "precise_cont_time",
            "burst_capture_number", "precise_selftime",
        ),
        SettingGroup.SOUND_LED to listOf("buzzer_volume", "buzzer_ring", "led_mode", "osd_enable"),
        SettingGroup.SYSTEM to listOf(
            "auto_power_off", "start_wifi_while_booted", "preview_status", "video_output_dev_type",
            "system_default_mode", "rc_button_mode", "save_log",
        ),
    )

    fun groupOf(key: String): SettingGroup = ORDER.firstOrNull { key in it.second }?.first ?: SettingGroup.OTHER

    /** Sort position: known keys by group and list order, unknown keys last (by name). */
    fun sortIndex(key: String): Int {
        var index = 0
        for ((_, keys) in ORDER) {
            val i = keys.indexOf(key)
            if (i >= 0) return index + i
            index += keys.size
        }
        return index
    }

    fun isVisible(key: String): Boolean = key !in HIDDEN && key !in StatusParser.SECRET_KEYS

    /** on/off settings are drawn as a switch. */
    fun isSwitch(values: List<String>): Boolean = values.size == 2 && values.toSet() == setOf("on", "off")

    /** "all enable" -> "all_enable", "date/time" -> "date_time": the end of a string resource name opt_<key>_<value>. */
    fun normalizeValue(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** "0.5 sec" -> "0.5", else null. */
    fun secondsOf(value: String): String? = Regex("""^(\d+(?:\.\d+)?) sec$""").find(value)?.groupValues?.get(1)

    /** "10 minutes" -> "10", else null. */
    fun minutesOf(value: String): String? = Regex("""^(\d+) minutes$""").find(value)?.groupValues?.get(1)
}

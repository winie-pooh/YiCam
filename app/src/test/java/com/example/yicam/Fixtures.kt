/**
 * Responsible for: real camera messages copied from camera logs of 2026-10-12 (YDXJ firmware 1.3.0), plus small helpers that build a message of the same shape; used as test data.
 * Not responsible for: any test logic.
 */
package com.example.yicam

object Fixtures {
    /** Reply of msg_id 3. Note: "7 p \/ s" has an escaped slash, spaces around braces are the camera's own style. */
    val SETTINGS_REPLY = """{ "rval": 0, "msg_id": 3, "param": [ { "camera_clock": "2008-03-23 00:54:08" }, { "video_standard": "NTSC" }, { "app_status": "idle" }, { "video_resolution": "1920x1080 60P 16:9" }, { "video_stamp": "off" }, { "video_quality": "S.Fine" }, { "timelapse_video": "10" }, { "capture_mode": "precise quality" }, { "photo_size": "16M (4608x3456 4:3)" }, { "photo_stamp": "off" }, { "photo_quality": "S.Fine" }, { "timelapse_photo": "60" }, { "preview_status": "on" }, { "buzzer_volume": "low" }, { "buzzer_ring": "off" }, { "capture_default_mode": "precise quality" }, { "precise_cont_time": "60.0 sec" }, { "burst_capture_number": "7 p \/ s" }, { "wifi_ssid": "YDXJ_3098638" }, { "wifi_password": "*" }, { "led_mode": "all enable" }, { "meter_mode": "center" }, { "sd_card_status": "insert" }, { "video_output_dev_type": "hdmi" }, { "sw_version": "YDXJv22L_1.3.0_build-20160524143845_b1049_i841_s1125" }, { "hw_version": "YDXJ_v25L" }, { "dual_stream_status": "on" }, { "streaming_status": "off" }, { "precise_cont_capturing": "off" }, { "piv_enable": "off" }, { "auto_low_light": "on" }, { "loop_record": "off" }, { "warp_enable": "off" }, { "support_auto_low_light": "on" }, { "precise_selftime": "5s" }, { "precise_self_running": "off" }, { "auto_power_off": "3 minutes" }, { "serial_number": "*" }, { "system_mode": "record" }, { "system_default_mode": "record" }, { "start_wifi_while_booted": "off" }, { "quick_record_time": "0" }, { "precise_self_remain_time": "0" }, { "sdcard_need_format": "no-need" }, { "video_rotate": "off" }, { "emergency_file_backup": "off" }, { "osd_enable": "off" }, { "rec_default_mode": "record" }, { "rec_mode": "record" }, { "record_photo_time": "5" }, { "dev_functions": "7743" }, { "rc_button_mode": "mode_shutter" }, { "timelapse_video_duration": "6s" }, { "timelapse_video_resolution": "1920x1080 60P 16:9" }, { "save_log": "off" } ] }"""

    const val SETTINGS_PARAM_COUNT = 55

    /** First session reply: param is a NUMBER. */
    const val SESSION_REPLY_1 = """{ "rval": 0, "msg_id": 257, "param": 1 }"""

    /** Battery reply: compact style, param is a STRING. */
    const val BATTERY_REPLY = """{"rval":0,"msg_id":13,"type":"battery","param":"82"}"""

    /** Battery event: note the space before the comma. */
    const val BATTERY_EVENT = """{ "msg_id": 7, "type": "battery" ,"param":"82"}"""

    /** msg_id 5 replies for type total / free, in kilobytes (real values of the camera's 32 GB card). */
    const val SD_TOTAL_REPLY = """{ "rval": 0, "msg_id": 5, "param": 30317536 }"""
    const val SD_FREE_REPLY = """{ "rval": 0, "msg_id": 5, "param": 20959840 }"""
    const val SD_TOTAL_KB = 30317536L

    /** What the camera answered on the first phone run, while it still reported a card slot: about 17 MB. */
    const val SMALL_SD_TOTAL_REPLY = """{ "rval": 0, "msg_id": 5, "param": 17490 }"""
    const val SMALL_SD_FREE_REPLY = """{ "rval": 0, "msg_id": 5, "param": 17414 }"""
    const val SMALL_SD_TOTAL_KB = 17490L
    const val SD_FREE_KB = 20959840L

    /** Battery event with the given level, in the camera's own spacing. */
    fun batteryEvent(percent: Int): String = """{ "msg_id": 7, "type": "battery" ,"param":"$percent"}"""

    const val VF_START_EVENT = """{ "msg_id": 7, "type": "vf_start" }"""
    const val VF_STOP_EVENT = """{ "msg_id": 7, "type": "vf_stop" }"""
    const val START_VIDEO_RECORD_EVENT = """{ "msg_id": 7, "type": "start_video_record" }"""

    /** param is the path of the new video (seen in the phone log of 2026-10-13). */
    const val VIDEO_RECORD_COMPLETE_EVENT =
        """{ "msg_id": 7, "type": "video_record_complete", "param": "\/tmp\/fuse_d\/DCIM\/101MEDIA\/YDXJ0042.mp4" }"""

    /** Reply of msg_id 3 with param=key: [value] is the camera's own text, e.g. "settable:NTSC#PAL" (phone log 2026-10-19). */
    fun optionsReply(key: String, value: String): String =
        """{ "rval": 0, "msg_id": 3, "param": [ { "$key": "${value.replace("/", "\\/")}" } ] }"""

    /** Real answers of the camera for some keys; every other key answers "readonly:". */
    val OPTION_TEXTS: Map<String, String> = mapOf(
        "camera_clock" to "settable:2008-03-30 09:31:22",
        "video_standard" to "settable:NTSC#PAL",
        "app_status" to "readonly:idle#vf#record#recording#capture#precise_cont_capturing#burst_capturing#precise_capturing#operation_done",
        "video_resolution" to "settable:1920x1080 60P 16:9#1920x1080 30P 16:9#1280x720 120P 16:9#848x480 240P 16:9",
        "video_quality" to "settable:S.Fine#Fine#Normal",
        "video_stamp" to "settable:off#date#time#date/time",
        "capture_mode" to "settable:precise quality#precise quality cont.#burst quality#precise self quality",
        "timelapse_photo" to "readonly:off#2",
        "buzzer_volume" to "settable:high#low#mute",
        "buzzer_ring" to "settable:on#off",
        "burst_capture_number" to "settable:3 p / s#5 p / s#7 p / s#7 p / 2s",
        "wifi_ssid" to "settable:",
        "sd_card_status" to "readonly:",
        "system_mode" to "settable:capture#record",
        "timelapse_video_duration" to "settable:",
    )

    /** The resolutions a PAL camera offers (shape of the real list; the 50P values are assumed). */
    const val PAL_RESOLUTIONS = "settable:1920x1080 50P 16:9#1920x1080 25P 16:9#1280x720 100P 16:9"

    const val CHDIR_REPLY = """{ "rval": 0, "msg_id": 1283, "pwd": "\/tmp\/fuse_d\/DCIM" }"""

    const val START_PREVIEW_REPLY = """{"rval":0,"msg_id":259}"""

    const val LISTING_REPLY =
        """{"rval":0,"msg_id":1282,"listing":[{"101MEDIA/":"0 bytes|2025-10-14 17:05:56"},{"Camera/":"0 bytes|2025-10-15 00:07:02"},{"EOSMISC/":"0 bytes|2024-01-23 20:07:14"},{"102MEDIA/":"0 bytes|2008-03-09 06:26:52"}]}"""

    fun chdirReply(path: String): String =
        MiniJson.toJson(linkedMapOf("rval" to 0L, "msg_id" to 1283L, "pwd" to path))

    fun listingReply(vararg entries: Pair<String, String>): String {
        val listing = entries.map { (name, meta) -> mapOf(name to meta) }
        return MiniJson.toJson(linkedMapOf("rval" to 0L, "msg_id" to 1282L, "listing" to listing))
    }
}

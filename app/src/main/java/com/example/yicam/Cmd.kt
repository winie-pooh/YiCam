/**
 * Responsible for: the list of camera command msg_id values (the only place with these numbers) and their reply timeouts.
 * Not responsible for: building or sending requests (CameraMessage.kt, CameraApi.kt).
 */
package com.example.yicam

/** Camera commands confirmed in docs (plan section 2.3). Never invent a msg_id: probe first, record in docs/protocol-findings.md. */
enum class Cmd(val msgId: Int, val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    START_SESSION(257),
    GET_SETTINGS(3),
    SET_SETTING(2),
    TAKE_PHOTO(769),
    STOP_TIMELAPSE(770),
    START_RECORD(513),
    STOP_RECORD(514),
    START_PREVIEW(259),
    ZOOM(14),
    GET_ZOOM(15),
    SD_MEMORY(5),
    BATTERY(13),
    FORMAT_SD(4),
    CHANGE_DIR(1283),
    LIST_FILES(1282, 15_000L),
    READ_FILE(1285);

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L

        /** msg_id of messages the camera sends on its own (battery, vf_start, ...). */
        const val EVENT_MSG_ID = 7

        /** rval meaning "token is not valid any more". */
        const val RVAL_INVALID_TOKEN = -4

        fun byMsgId(msgId: Int): Cmd? = entries.firstOrNull { it.msgId == msgId }

        fun timeoutFor(msgId: Int): Long = byMsgId(msgId)?.timeoutMs ?: DEFAULT_TIMEOUT_MS
    }
}

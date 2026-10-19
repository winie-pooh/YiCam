/**
 * Responsible for: turning camera replies and events (battery, SD memory, settings and their allowed values) into plain values, tolerant to numbers or text, and leaving secrets out.
 * Not responsible for: sending commands or keeping state (CameraSession.kt) or the models themselves (CameraStatus.kt).
 */
package com.example.yicam

object StatusParser {
    /** Settings that must never be stored, shown or logged. */
    val SECRET_KEYS: Set<String> = setOf("wifi_password", "serial_number")

    class Battery(val percent: Int?, val charging: Boolean?)

    /**
     * Reply of msg_id 13: {"type":"battery","param":"82"}. percent is null if param is not 0..100.
     * charging: type "adapter" means the charger is connected, type "battery" means it is not, anything else is unknown (null).
     * The meaning of "adapter" comes from the original PC client and is not yet seen on this camera.
     */
    fun battery(reply: CameraMessage.Response): Battery {
        val charging = when (textOf(reply.fields["type"])) {
            "adapter" -> true
            "battery" -> false
            else -> null
        }
        return Battery(percentOf(reply.param), charging)
    }

    /** A battery level from a reply or event param: "82", 82 or 82.0 give 82; anything outside 0..100 gives null. */
    fun percentOf(value: Any?): Int? {
        val number: Long = when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        } ?: return null
        return if (number in 0L..100L) number.toInt() else null
    }

    /** Reply of msg_id 5 (type total or free): the size in kilobytes, or null if missing or negative. */
    fun kilobytes(reply: CameraMessage.Response): Long? = reply.paramLong()?.takeIf { it >= 0L }

    /** Reply of msg_id 3: a list of one-key objects becomes one map, in the camera's order, without secrets. */
    fun settings(reply: CameraMessage.Response): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val items = reply.paramList() ?: return result
        for (item in items) {
            val entry = item as? Map<*, *> ?: continue
            for ((rawKey, rawValue) in entry) {
                val key = rawKey as? String ?: continue
                if (key in SECRET_KEYS) continue
                val text = textOf(rawValue) ?: continue
                result[key] = text
            }
        }
        return result
    }

    /**
     * What the camera allows for one setting. [settable] false means read only. [values] is empty for free text
     * (also for the camera clock, where the camera puts the current time after "settable:").
     */
    class Options(val settable: Boolean, val values: List<String>)

    /**
     * Reply of msg_id 3 with param=key (seen in the phone log of 2026-10-19):
     * {"param":[{"video_standard":"settable:NTSC#PAL"}]}, "readonly:a#b", "readonly:" or "settable:".
     * Null if the reply has no entry for [key] or the text has neither prefix.
     */
    fun options(reply: CameraMessage.Response, key: String): Options? {
        if (!reply.isOk) return null
        val items = reply.paramList() ?: return null
        for (item in items) {
            val text = textOf((item as? Map<*, *>)?.get(key)) ?: continue
            val settable = when {
                text.startsWith(SETTABLE) -> true
                text.startsWith(READONLY) -> false
                else -> return null
            }
            val rest = text.substring(if (settable) SETTABLE.length else READONLY.length)
            return Options(settable, if (rest.isEmpty()) emptyList() else rest.split('#').filter { it.isNotEmpty() })
        }
        return null
    }

    private const val SETTABLE = "settable:"
    private const val READONLY = "readonly:"

    private fun textOf(value: Any?): String? = when (value) {
        is String -> value
        is Boolean -> value.toString()
        is Long, is Int -> value.toString()
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
        else -> null
    }
}

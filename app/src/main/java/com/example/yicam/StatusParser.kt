/**
 * Responsible for: turning camera replies and events (battery, SD memory, settings) into plain values, tolerant to numbers or text, and leaving secrets out.
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

    private fun textOf(value: Any?): String? = when (value) {
        is String -> value
        is Boolean -> value.toString()
        is Long, is Int -> value.toString()
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
        else -> null
    }
}

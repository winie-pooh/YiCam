/**
 * Responsible for: the model of camera JSON messages (reply, event) and the conversion between text and these objects, including building requests.
 * Not responsible for: sockets, timeouts, tokens or waiting for replies (CameraApi.kt).
 */
package com.example.yicam

sealed interface CameraMessage {
    /** Reply to a request we sent. Matched to the request by [msgId]. */
    class Response(val msgId: Int, val fields: Map<String, Any?>, val raw: String) : CameraMessage {
        /** 0 = ok, -4 = invalid token, other = error. Null if the camera did not send rval. */
        val rval: Int? get() = (fields["rval"] as? Number)?.toInt()
        val isOk: Boolean get() = rval == 0
        val param: Any? get() = fields["param"]

        /** param as text: strings stay as they are, numbers are printed without ".0". */
        fun paramString(): String? = scalarText(param)

        /** param as a number: works for 1, "82" and 82.0. Null if it is neither. */
        fun paramLong(): Long? = when (val p = param) {
            is Long -> p
            is Number -> p.toLong()
            is String -> p.trim().toLongOrNull()
            else -> null
        }

        fun paramList(): List<Any?>? = param as? List<Any?>
    }

    /** Message the camera sends on its own: msg_id 7 with a "type" (battery, vf_start, ...). */
    class Event(val type: String?, val param: Any?, val fields: Map<String, Any?>, val raw: String) : CameraMessage

    /** Text that is JSON but not a camera message we can use. */
    class Malformed(val raw: String, val reason: String) : CameraMessage

    companion object {
        fun parse(raw: String): CameraMessage {
            val value = try {
                MiniJson.parse(raw)
            } catch (e: JsonException) {
                return Malformed(raw, e.message ?: "bad JSON")
            }
            @Suppress("UNCHECKED_CAST")
            val fields = value as? Map<String, Any?> ?: return Malformed(raw, "not a JSON object")
            val msgId = (fields["msg_id"] as? Number)?.toInt() ?: return Malformed(raw, "no msg_id")
            return if (msgId == Cmd.EVENT_MSG_ID) {
                Event(scalarText(fields["type"]), fields["param"], fields, raw)
            } else {
                Response(msgId, fields, raw)
            }
        }

        /**
         * Request text: {"msg_id":N,"token":T,<fields in the given order>}. '/' in strings is written as "\/".
         * Field order is kept because the camera firmware is a simple parser.
         */
        fun encodeRequest(msgId: Int, token: Int, fields: List<Pair<String, Any?>> = emptyList()): String {
            val map = LinkedHashMap<String, Any?>()
            map["msg_id"] = msgId
            map["token"] = token
            for ((k, v) in fields) map[k] = v
            return MiniJson.toJson(map)
        }

        private fun scalarText(v: Any?): String? = when (v) {
            null -> null
            is String -> v
            is Long, is Int -> v.toString()
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            is Boolean -> v.toString()
            else -> null
        }
    }
}

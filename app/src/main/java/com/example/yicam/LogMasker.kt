/**
 * Responsible for: hiding secrets (Wi-Fi password, serial number) in text that goes to the log.
 * Not responsible for: storing or showing the log (TrafficLog.kt).
 */
package com.example.yicam

object LogMasker {
    private const val SECRET_KEYS = "wifi_password|serial_number"
    private const val STRING = "\"(?:[^\"\\\\]|\\\\.)*\""

    // "wifi_password": "xxx"  (reply of msg_id 3)
    private val keyValue = Regex("(\"(?:$SECRET_KEYS)\"\\s*:\\s*)$STRING")

    // {"type":"wifi_password","param":"xxx"}  (set command, type first)
    private val typeThenParam =
        Regex("(\"type\"\\s*:\\s*\"(?:$SECRET_KEYS)\"\\s*,\\s*\"param\"\\s*:\\s*)$STRING")

    // {"param":"xxx","type":"wifi_password"}  (set command, param first)
    private val paramThenType =
        Regex("(\"param\"\\s*:\\s*)$STRING(?=\\s*,\\s*\"type\"\\s*:\\s*\"(?:$SECRET_KEYS)\")")

    fun mask(text: String): String {
        var result = text
        for (rx in listOf(keyValue, typeThenParam, paramThenType)) {
            result = rx.replace(result, "\$1\"*\"")
        }
        return result
    }
}

/**
 * Responsible for: the small app preferences kept on the phone: the orientation of the live picture and the grid mode (SharedPreferences, no camera data).
 * Not responsible for: the calculations (LiveOrientation.kt, GridGeometry.kt) or any screen (LiveScreen.kt).
 */
package com.example.yicam

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("yicam_prefs", Context.MODE_PRIVATE)

    var liveOrientation: LiveOrientation
        get() = LiveOrientation.fromName(prefs.getString(KEY_ORIENTATION, null))
        set(value) {
            prefs.edit().putString(KEY_ORIENTATION, value.name).apply()
        }

    var gridMode: GridMode
        get() = GridMode.fromName(prefs.getString(KEY_GRID, null))
        set(value) {
            prefs.edit().putString(KEY_GRID, value.name).apply()
        }

    private companion object {
        const val KEY_ORIENTATION = "live_orientation"
        const val KEY_GRID = "grid_mode"
    }
}

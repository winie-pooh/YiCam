/**
 * Responsible for: creating the one player and the one LiveViewController of the app, so the live view keeps playing when the activity is recreated (for example by a screen rotation).
 * Not responsible for: the live view logic (LiveViewController.kt), the player (ExoRtspPlayer.kt) or the screen (LiveScreen.kt).
 */
package com.example.yicam

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object LiveProvider {
    class Live(val controller: LiveViewController, val player: ExoRtspPlayer)

    @Volatile
    private var instance: Live? = null

    /** Call on the main thread. */
    fun get(context: Context): Live =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): Live {
        val session = SessionProvider.get(context)
        val player = ExoRtspPlayer(context, session.log)
        // The controller and the player must be used from the main thread.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        return Live(LiveViewController(session, player, scope), player)
    }
}

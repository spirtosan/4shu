package com.fshu.next.trail

import android.content.Context
import android.util.Log
import com.fshu.next.util.Prefs
import com.google.gson.JsonObject

/**
 * Chunk 2 — guardian-side relationship registry.
 *
 * Maintains the local "wards" lists (people who granted ME guardianship of their trail)
 * from `trail-guardian-changed` pushes. Registered app-wide in [FshuService] so the
 * lists stay current even when no Trail screen is open.
 *
 * Server message shape:
 *   {type:"trail-guardian-changed", user:<tracked>, guardian:<G>, state:"granted|accepted|revoked"}
 * I am the guardian in a row exactly when `guardian == my username`; then `user` is the
 * tracked person who shared their trail with me.
 */
object GuardianRegistry {
    private const val TAG = "GuardianRegistry"

    fun onServerMessage(context: Context, json: JsonObject) {
        if (json.get("type")?.asString != "trail-guardian-changed") return
        val me = Prefs.getUsername(context).lowercase()
        val user = json.get("user")?.asString?.lowercase() ?: return
        val guardian = json.get("guardian")?.asString?.lowercase() ?: return
        if (guardian != me) return                       // row is about someone else guarding `user`
        val state = json.get("state")?.asString ?: return

        val pending = Prefs.getTrailWardsPending(context).toMutableSet()
        val accepted = Prefs.getTrailWardsAccepted(context).toMutableSet()
        when (state) {
            "granted"  -> pending.add(user)
            "accepted" -> { pending.remove(user); accepted.add(user) }
            "revoked"  -> { pending.remove(user); accepted.remove(user) }
            else -> return
        }
        Prefs.setTrailWardsPending(context, pending)
        Prefs.setTrailWardsAccepted(context, accepted)
        Log.d(TAG, "ward $user -> $state (pending=${pending.size} accepted=${accepted.size})")
    }

    /** Optimistic local accept — move a ward from pending to accepted before the echo lands. */
    fun markAcceptedLocally(context: Context, user: String) {
        val u = user.lowercase()
        Prefs.setTrailWardsPending(context, Prefs.getTrailWardsPending(context) - u)
        Prefs.setTrailWardsAccepted(context, Prefs.getTrailWardsAccepted(context) + u)
    }

    /** Local cleanup for decline (pending) or stop-guarding (accepted). */
    fun removeLocally(context: Context, user: String) {
        val u = user.lowercase()
        Prefs.setTrailWardsPending(context, Prefs.getTrailWardsPending(context) - u)
        Prefs.setTrailWardsAccepted(context, Prefs.getTrailWardsAccepted(context) - u)
    }
}

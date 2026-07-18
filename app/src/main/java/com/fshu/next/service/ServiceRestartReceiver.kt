package com.fshu.next.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.util.Prefs

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val intentAction = intent.action
        if (intentAction == FshuService.ACTION_ALARM_CHECK) {
            val username = try { Prefs.getUsername(context) } catch (e: Exception) { "" }
            if (username.isNotEmpty()) {
                val serviceRunning = isServiceRunning(context)
                val wsConnected = WebSocketClient.isConnected
                if (!serviceRunning) {
                    Log.w("AlarmCheck", "Service dead — restarting")
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, FshuService::class.java)
                    )
                } else if (!wsConnected) {
                    Log.w("AlarmCheck", "WS disconnected — forcing reconnect")
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, FshuService::class.java).apply {
                            action = FshuService.ACTION_RECONNECT
                        }
                    )
                }
                scheduleNextAlarm(context)
            }
            return
        }
        if (intentAction == "android.intent.action.LOCKED_BOOT_COMPLETED") {
            val plain = context.getSharedPreferences("fshu_boot", Context.MODE_PRIVATE)
            if (plain.getBoolean("was_logged_in", false)) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FshuService::class.java)
                )
            }
            return
        }
        if (intentAction == Intent.ACTION_USER_UNLOCKED) {
            if (Prefs.getUsername(context).isNotEmpty()) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FshuService::class.java)
                )
            }
            maybeRestartTrail(context)
            return
        }
        // T13 Block D: BOOT_COMPLETED previously had no explicit branch here and fell
        // through to the generic block below (same FshuService-start result) -- given
        // its own branch now so the "boot" trigger for Trail is only attached to a
        // genuine device-boot completion, not the generic ACTION_RESTART_SERVICE case
        // that also reaches the fallback block.
        if (intentAction == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs.getUsername(context).isNotEmpty()) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FshuService::class.java)
                )
            }
            maybeRestartTrail(context)
            return
        }
        if (Prefs.getUsername(context).isNotEmpty()) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FshuService::class.java)
            )
        }
    }

    /** Restarts TrailService with the boot trigger if Trail is enabled (§3.1/§3.6).
     *  Not called from LOCKED_BOOT_COMPLETED: trail_enabled lives in normal
     *  (credential-encrypted) prefs, same as FshuService's own username check there --
     *  neither is reliably readable before first unlock, which is why FshuService's
     *  own LOCKED_BOOT_COMPLETED branch above uses a separate direct-boot-safe flag
     *  instead. Not duplicating that machinery for Trail here; USER_UNLOCKED and
     *  BOOT_COMPLETED already cover the case once storage is actually available. */
    private fun maybeRestartTrail(context: Context) {
        if (!Prefs.isTrailEnabled(context)) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, TrailService::class.java).apply {
                putExtra(TrailService.EXTRA_TRIGGER, TrailService.TRIGGER_BOOT)
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == FshuService::class.java.name }
    }

    private fun scheduleNextAlarm(context: Context) {
        val intent = Intent(context, ServiceRestartReceiver::class.java).apply {
            action = FshuService.ACTION_ALARM_CHECK
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context, 1001, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + 3 * 60 * 1000L
        val am = context.getSystemService(android.app.AlarmManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } else {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }
}

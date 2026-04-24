package com.fshu.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.fshu.data.remote.WebSocketClient
import com.fshu.util.Prefs

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
            return
        }
        if (Prefs.getUsername(context).isNotEmpty()) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FshuService::class.java)
            )
        }
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

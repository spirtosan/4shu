package com.fshu.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.fshu.data.remote.WebSocketClient
import com.fshu.util.Prefs

class ServiceWatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val username = Prefs.getUsername(applicationContext)
        if (username.isEmpty()) return Result.success()

        if (!isServiceRunning()) {
            Log.w("Watchdog", "Service not running — restarting")
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, FshuService::class.java)
            )
        } else if (!WebSocketClient.isConnected) {
            Log.w("Watchdog", "Service running but WebSocket disconnected — forcing reconnect")
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, FshuService::class.java).apply {
                    action = FshuService.ACTION_RECONNECT
                }
            )
        }
        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = applicationContext.getSystemService(ActivityManager::class.java)
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == FshuService::class.java.name }
    }
}

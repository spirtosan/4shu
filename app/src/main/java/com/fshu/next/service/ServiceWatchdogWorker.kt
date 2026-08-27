package com.fshu.next.service

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.fshu.next.data.local.AppDatabase
import com.fshu.next.data.remote.WebSocketClient
import com.fshu.next.util.Prefs

class ServiceWatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val username = Prefs.getUsername(applicationContext)
        if (username.isEmpty()) return Result.success()

        if (!isServiceRunning(FshuService::class.java)) {
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

        // B.1.4: TrailService has no restart path of its own (no AlarmManager kick, no
        // BOOT_COMPLETED-only receiver coverage while running) — this worker was already
        // watching FshuService, so it's the natural place to also watch Trail rather than
        // standing up a second periodic job. See SPEC_T13.md's Block B.1.4.
        if (Prefs.isTrailEnabled(applicationContext) && !isServiceRunning(TrailService::class.java)) {
            Log.w("Watchdog", "Trail enabled but TrailService not running — restarting")
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, TrailService::class.java).apply {
                    putExtra(TrailService.EXTRA_TRIGGER, TrailService.TRIGGER_WATCHDOG)
                }
            )
        }

        // T13 Block H — local frozen-clock retention purge, at most once/day (SPEC_T13 §3.5/§4.4):
        // window measured from this device's OWN newest point, so a trail that stopped survives.
        if (Prefs.isTrailEnabled(applicationContext)) {
            val now = System.currentTimeMillis()
            if (now - Prefs.getTrailLastPurgeTs(applicationContext) >= 24L * 60 * 60 * 1000) {
                try {
                    kotlinx.coroutines.runBlocking {
                        AppDatabase.getInstance(applicationContext).trailDao()
                            .purgeOlderThanFrozenWindow(7L * 24 * 60 * 60 * 1000)
                    }
                    Prefs.setTrailLastPurgeTs(applicationContext, now)
                    Log.d("Watchdog", "trail retention purge ran")
                } catch (e: Exception) { Log.w("Watchdog", "trail purge failed: ${e.message}") }
            }
        }

        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(cls: Class<out Service>): Boolean {
        val am = applicationContext.getSystemService(ActivityManager::class.java)
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == cls.name }
    }
}

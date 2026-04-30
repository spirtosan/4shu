package com.fshu.next.util

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler : Thread.UncaughtExceptionHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null
    private const val MAX_LOGS = 5
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun install(ctx: Context) {
        appContext = ctx.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            val sw = StringWriter()
            ex.printStackTrace(PrintWriter(sw))
            val ts = System.currentTimeMillis()
            val versionName = try {
                appContext!!.packageManager.getPackageInfo(appContext!!.packageName, 0).versionName
            } catch (_: Exception) { "unknown" }
            val log = buildString {
                append("=== 4shu CRASH ${fmt.format(Date(ts))} ===\n")
                append("Device:  ${Build.MODEL}\n")
                append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("App:     $versionName\n")
                append("Thread:  ${thread.name}\n")
                append("\n")
                append(sw.toString())
                append("\n")
            }

            // Write to private app storage (readable via menu)
            val dir = File(appContext!!.filesDir, "crashes").also { it.mkdirs() }
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size >= MAX_LOGS) files.take(files.size - MAX_LOGS + 1).forEach { it.delete() }
            File(dir, "crash_$ts.txt").writeText(log)

            // Write to public Downloads folder (no permission needed on API 29+)
            try {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(downloads, "4shu_crash_$ts.txt").writeText(log)
            } catch (_: Exception) {}

        } catch (_: Exception) {}
        defaultHandler?.uncaughtException(thread, ex)
    }

    fun getLastCrash(ctx: Context): String? {
        val dir = File(ctx.filesDir, "crashes")
        if (!dir.exists()) return null
        val last = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return null
        return try { last.readText() } catch (_: Exception) { null }
    }

    fun getAllCrashes(ctx: Context): List<String> {
        val dir = File(ctx.filesDir, "crashes")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { try { it.readText() } catch (_: Exception) { null } }
            ?: emptyList()
    }

    fun clearCrashes(ctx: Context) {
        File(ctx.filesDir, "crashes").deleteRecursively()
    }
}

package com.ifafu.kyzz

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.ifafu.kyzz.service.MockLocationService
import com.ifafu.kyzz.ui.main.MainActivity
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
        val darkMode = prefs.getInt("dark_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(darkMode)
    }

    private class CrashHandler(private val app: Application) : Thread.UncaughtExceptionHandler {
        private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            Log.e("iFAFU", "Uncaught exception on ${thread.name}", throwable)
            try {
                val prefs = app.getSharedPreferences("ifafu_crash", MODE_PRIVATE)
                val crashCount = prefs.getInt("crash_count", 0)
                val lastCrashTime = prefs.getLong("last_crash_time", 0L)
                val now = System.currentTimeMillis()
                val recentCrashCount = if (now - lastCrashTime < 10_000) crashCount + 1 else 1
                prefs.edit()
                    .putString("last_crash_trace", throwable.stackTraceToString())
                    .putString("last_crash_message", throwable.message ?: "Unknown error")
                    .putLong("last_crash_time", now)
                    .putInt("crash_count", recentCrashCount)
                    .apply()
                if (recentCrashCount >= 3) {
                    app.getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
                        .putBoolean("mock_location_running", false)
                        .apply()
                    try {
                        app.stopService(Intent(app, MockLocationService::class.java))
                    } catch (_: Exception) {}
                    prefs.edit().putInt("crash_count", 0).apply()
                }
            } catch (_: Exception) {}
            try {
                val intent = Intent(app, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                intent.putExtra("crash_recovery", true)
                app.startActivity(intent)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

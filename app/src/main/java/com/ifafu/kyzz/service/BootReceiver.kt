package com.ifafu.kyzz.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.Calendar

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("isLogin", false)) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Course reminder at 7:30 AM daily
        val courseIntent = Intent(context, CourseReminderReceiver::class.java)
        val coursePending = PendingIntent.getBroadcast(
            context, 0, courseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val courseCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setRepeating(AlarmManager.RTC_WAKEUP, courseCal.timeInMillis, AlarmManager.INTERVAL_DAY, coursePending)

        // Score check at 12:00 PM daily
        val scoreIntent = Intent(context, ScoreCheckReceiver::class.java)
        val scorePending = PendingIntent.getBroadcast(
            context, 1, scoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val scoreCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setRepeating(AlarmManager.RTC_WAKEUP, scoreCal.timeInMillis, AlarmManager.INTERVAL_DAY, scorePending)

        // Restart mock location if it was running before reboot
        if (prefs.getBoolean("mock_location_running", false)) {
            val lat = prefs.getFloat("mock_location_lat", 0f).toDouble()
            val lng = prefs.getFloat("mock_location_lng", 0f).toDouble()
            if (lat != 0.0 && lng != 0.0) {
                val mockIntent = Intent(context, MockLocationService::class.java).apply {
                    putExtra(MockLocationService.EXTRA_LATITUDE, lat)
                    putExtra(MockLocationService.EXTRA_LONGITUDE, lng)
                }
                ContextCompat.startForegroundService(context, mockIntent)
            }
        }
    }
}

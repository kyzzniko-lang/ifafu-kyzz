package com.ifafu.kyzz.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Score

class ScoreCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notify_score", true)) return

        val pendingResult = goAsync()
        Thread {
            try {
                checkNewScores(context)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun checkNewScores(context: Context) {
        try {
            val userPrefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
            val account = userPrefs.getString("account", "") ?: ""
            if (account.isEmpty()) return

            val cachePrefs = context.getSharedPreferences("ifafu_cache", Context.MODE_PRIVATE)
            val json = cachePrefs.getString("scores_$account", null) ?: return
            val type = object : TypeToken<List<Score>>() {}.type
            val scores: List<Score> = Gson().fromJson<List<Score>>(json, type) ?: return

            val lastCount = cachePrefs.getInt("last_score_count_$account", 0)
            val currentCount = scores.size

            if (lastCount > 0 && currentCount > lastCount) {
                val newCount = currentCount - lastCount
                val newScores = scores.takeLast(newCount)
                val names = newScores.joinToString("、") { it.courseName }
                showNotification(context, "有${newCount}门新成绩", names)
            }

            cachePrefs.edit().putInt("last_score_count_$account", currentCount).apply()
        } catch (_: Exception) {}
    }

    private fun showNotification(context: Context, title: String, content: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "成绩通知", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .build()
        nm.notify(1002, notification)
    }

    companion object {
        const val CHANNEL_ID = "score_notification"
    }
}

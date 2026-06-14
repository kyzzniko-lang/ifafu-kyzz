package com.ifafu.kyzz.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.repository.UserRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class iFAFUWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "iFAFUWidget"
        private const val ACTION_UPDATE = "com.ifafu.kyzz.WIDGET_UPDATE"
        private const val ACTION_CLEAR = "com.ifafu.kyzz.WIDGET_CLEAR"
        private const val UPDATE_INTERVAL_MINUTES = 30L

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_today_courses)
            val content = getTodayCoursesText(context)
            views.setTextViewText(R.id.tvWidgetContent, content)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, iFAFUWidget::class.java).apply {
                action = ACTION_UPDATE
            }
            context.sendBroadcast(intent)
        }

        fun clearWidgetData(context: Context) {
            val prefs = context.getSharedPreferences("ifafu_widget", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }

        fun scheduleNextUpdate(context: Context) {
            try {
                val intent = Intent(context, iFAFUWidget::class.java).apply {
                    action = ACTION_UPDATE
                }
                val pending = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val triggerTime = System.currentTimeMillis() + (UPDATE_INTERVAL_MINUTES * 60 * 1000)
                alarmManager.setRepeating(
                    AlarmManager.RTC,
                    triggerTime,
                    UPDATE_INTERVAL_MINUTES * 60 * 1000,
                    pending
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule widget update", e)
            }
        }

        private fun getTodayCoursesText(context: Context): String {
            try {
                val app = context.applicationContext
                val userRepo = UserRepository(app)
                val user = userRepo.getUser()
                val firstDay = userRepo.termFirstDay
                if (!user.isLogin || firstDay.isEmpty()) return "未登录"

                val cacheManager = CacheManager(app)
                val syllabus = cacheManager.loadSyllabus(user.account) ?: return "暂无课表数据"

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val parsed = sdf.parse(firstDay) ?: return "日期格式错误"
                val termStart = Calendar.getInstance().apply { time = parsed }
                val today = Calendar.getInstance()
                val diffDays = ((today.timeInMillis - termStart.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
                val currentWeek = ((diffDays / 7) + 1).coerceAtLeast(1)
                val dayOfWeek = today.get(Calendar.DAY_OF_WEEK)
                val todayDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1

                val matched = syllabus.courses.filter { c ->
                    currentWeek in c.weekBegin..c.weekEnd && c.weekDay == todayDay &&
                        (c.oddOrTwice == 0 || (c.oddOrTwice == 1 && currentWeek % 2 == 1) || (c.oddOrTwice == 2 && currentWeek % 2 == 0))
                }.sortedBy { it.begin }

                if (matched.isEmpty()) return "今天没有课程"

                return matched.joinToString("\n") { c ->
                    "第${c.begin}-${c.end}节 ${c.name} ${c.address}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get today's courses", e)
                return "暂无数据"
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
        scheduleNextUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_UPDATE -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(
                    android.content.ComponentName(context, iFAFUWidget::class.java)
                )
                for (appWidgetId in appWidgetIds) {
                    updateWidget(context, appWidgetManager, appWidgetId)
                }
            }
            ACTION_CLEAR -> {
                clearWidgetData(context)
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleNextUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        try {
            val intent = Intent(context, iFAFUWidget::class.java).apply {
                action = ACTION_UPDATE
            }
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pending)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel alarm", e)
        }
    }
}

class WidgetClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        iFAFUWidget.clearWidgetData(context)
    }
}
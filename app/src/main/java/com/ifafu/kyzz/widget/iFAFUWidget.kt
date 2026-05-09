package com.ifafu.kyzz.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.repository.UserRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class iFAFUWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_today_courses)
            val content = getTodayCoursesText(context)
            views.setTextViewText(R.id.tvWidgetContent, content)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun getTodayCoursesText(context: Context): String {
            try {
                val prefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
                val account = prefs.getString("account", "") ?: ""
                val firstDay = prefs.getString("termFirstDay", "") ?: ""
                if (account.isEmpty() || firstDay.isEmpty()) return "未登录"

                val cachePrefs = context.getSharedPreferences("ifafu_cache", Context.MODE_PRIVATE)
                val json = cachePrefs.getString("syllabus_$account", null) ?: return "暂无课表数据"
                val gson = com.google.gson.Gson()
                val syllabus = gson.fromJson(json, com.ifafu.kyzz.data.model.Syllabus::class.java)

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
            } catch (_: Exception) {
                return "暂无数据"
            }
        }
    }
}

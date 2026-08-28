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
                // 一次性闹钟 + 每次触发后自续（见 ACTION_UPDATE）：setRepeating 自
                // API 19 起不精确、Doze 下被系统大幅延迟，跨天/跨周切换点会显示
                // "昨天"的课表
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, triggerTime, pending)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule widget update", e)
            }
        }

        /**
         * 重启/应用升级后系统闹钟被清空，Widget 会永远停在重启前的数据。
         * 补发一次更新广播；无 Widget 时广播分支不再续排闹钟，链条自然终止。
         */
        fun pokeAfterBoot(context: Context) {
            val intent = Intent(context, iFAFUWidget::class.java).apply {
                action = ACTION_UPDATE
            }
            context.sendBroadcast(intent)
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
                // 双方归零到当日 00:00：termFirstDay 带保存时刻的时分秒，不归零时
                // 周切换点会漂移到当天那个时刻（与首页/课表页的口径一致）。
                val termStart = Calendar.getInstance().apply {
                    time = parsed
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val today = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val diffDays = ((today.timeInMillis - termStart.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
                val currentWeek = ((diffDays / 7) + 1).coerceAtLeast(1)

                // 检测是否在假期中（当前周远超课表范围）
                val maxCourseWeek = if (syllabus.courses.isNotEmpty()) syllabus.courses.maxOf { it.weekEnd } else 20
                if (currentWeek > maxCourseWeek + 4) {
                    return "🏖️ 假期中"
                }

                // 检测是否在学期开始前（termFirstDay 在未来）
                if (diffDays < 0) {
                    val daysToStart = -diffDays
                    return "📅 新学期${daysToStart}天后开始"
                }

                val dayOfWeek = today.get(Calendar.DAY_OF_WEEK)
                val todayDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1

                val matched = syllabus.courses.filter { c ->
                    c.coversWeek(currentWeek) && c.weekDay == todayDay &&
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
        updateWidgetsAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_UPDATE -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(
                    android.content.ComponentName(context, iFAFUWidget::class.java)
                )
                // 无 Widget 时直接终止更新链条，避免 30 分钟空转闹钟永不停止
                if (appWidgetIds.isEmpty()) return
                updateWidgetsAsync(context, appWidgetManager, appWidgetIds)
            }
            ACTION_CLEAR -> {
                clearWidgetData(context)
            }
        }
    }

    /**
     * 课表 JSON 反序列化 + SharedPreferences 读盘挪到后台线程：广播 onReceive 在
     * 主线程执行，大课表时每 30 分钟卡一次主线程（老设备可被 ANR 看门狗记为阻塞）。
     */
    private fun updateWidgetsAsync(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        Thread {
            try {
                for (appWidgetId in appWidgetIds) {
                    updateWidget(context, appWidgetManager, appWidgetId)
                }
                scheduleNextUpdate(context)
            } catch (e: Exception) {
                Log.e(TAG, "Widget update failed", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
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
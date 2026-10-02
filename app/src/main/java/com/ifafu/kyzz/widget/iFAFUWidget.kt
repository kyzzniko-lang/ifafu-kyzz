package com.ifafu.kyzz.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.TermResolver
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

        fun clearWidgetData(context: Context) {
            // 登出后立即刷新所有 Widget，而不是清缓存：Widget 没有任何本地缓存，
            // 旧实现清的是一个从未写入过的 prefs 文件（死代码），登出后 Widget 最长
            // 30 分钟仍显示上一账号课表。改发已注册的 WIDGET_UPDATE 显式广播自触发
            // 全量刷新，未登录态下 getTodayCoursesText 会渲染"未登录"占位。
            // 方法名保留 clearWidgetData，SettingsFragment 登出路径依赖此签名。
            val intent = Intent(context, iFAFUWidget::class.java).apply {
                action = ACTION_UPDATE
                // onReceive 会对自定义 action 校验来源包名，自发广播须显式声明
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        fun scheduleNextUpdate(context: Context) {
            try {
                val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
                    android.content.ComponentName(context, iFAFUWidget::class.java)
                )
                if (ids.isEmpty()) return
                val intent = Intent(context, iFAFUWidget::class.java).apply {
                    action = ACTION_UPDATE
                    // 闹钟回发的广播同样要过 onReceive 的来源包名校验
                    setPackage(context.packageName)
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
                // onReceive 会对自定义 action 校验来源包名，自发广播须显式声明
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        private fun getTodayCoursesText(context: Context): String {
            try {
                val app = context.applicationContext
                // Widget 进程内手动构造（非 Hilt）：UserRepository 删除账号时要
                // 清理 CacheManager 中该账号的缓存，两者共用同一实例
                val cacheManager = CacheManager(app)
                val userRepo = UserRepository(app, cacheManager)
                val user = userRepo.getUser()
                val firstDay = userRepo.termFirstDay
                if (!user.isLogin || firstDay.isEmpty()) return "未登录"

                val syllabus = cacheManager.loadSyllabus(user.account) ?: return "暂无课表数据"

                // 天数差用 LocalDate 计算：毫秒差整除 24h 在夏令时/非 UTC+8 时区下
                // 会差一天、周次随之错一周（与 ExamDates 的口径一致）。
                // substringBefore(' ') 剥掉 termFirstDay 尾随的保存时刻时分秒。
                val termStart = try {
                    LocalDate.parse(firstDay.substringBefore(' '))
                } catch (e: Exception) {
                    return "日期格式错误"
                }
                val today = LocalDate.now()
                val diffDays = ChronoUnit.DAYS.between(termStart, today).toInt()
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

                // 缓存学期 vs 日历推断学期守卫(与 CourseReminderReceiver.loadTodayCourses
                // 及首页 belongsToDifferentTerm 同口径):termFirstDay 已翻到新学期而课表
                // 缓存还是旧学期时,旧学期课程不能按新学期周次渲染整个开学周。
                val upcoming = TermResolver.breakTransition()?.upcoming
                val cachedYear = syllabus.searchYearOptions.getOrNull(syllabus.selectedYearOption)
                val cachedTerm = syllabus.searchTermOptions.getOrNull(syllabus.selectedTermOption)
                if (upcoming != null && cachedYear == upcoming.year && cachedTerm == upcoming.term) {
                    // 新学期课表已发布但尚未开学:不按旧学期周次出课。
                    return "🗓️ 新学期课表待开学"
                }
                if (!cachedYear.isNullOrEmpty() && !cachedTerm.isNullOrEmpty()) {
                    val inferred = TermResolver.inferCurrentTerm()
                    if (cachedYear != inferred.year || cachedTerm != inferred.term) {
                        return "🗓️ 课表待更新"
                    }
                }

                // ISO 周序（周一=1…周日=7），与原来的 Calendar.SUNDAY 映射等价
                val todayDay = today.dayOfWeek.value

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
        val action = intent.action
        // Custom actions use a non-exported receiver and an explicit target package.
        // The package check only validates routing; sender isolation is enforced by Manifest.
        if ((action == ACTION_UPDATE || action == ACTION_CLEAR) &&
            intent.getPackage() != context.packageName
        ) {
            Log.w(TAG, "Rejected foreign widget broadcast: $action")
            return
        }
        when (action) {
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
            } catch (e: Exception) {
                Log.e(TAG, "Widget update failed", e)
            } finally {
                // A failed RemoteViews update must not permanently break the refresh chain.
                // scheduleNextUpdate also checks for removal of the last widget during this task.
                scheduleNextUpdate(context)
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
                // 与 scheduleNextUpdate 的 Intent 保持完全一致，确保能匹配取消
                setPackage(context.packageName)
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

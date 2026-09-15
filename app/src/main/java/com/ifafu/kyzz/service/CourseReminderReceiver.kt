package com.ifafu.kyzz.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.TermResolver
import com.ifafu.kyzz.ui.main.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Calendar

class CourseReminderReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CourseReminderEntryPoint {
        fun userRepository(): UserRepository
        fun cacheManager(): CacheManager
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val userPrefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
        val loggedIn = userPrefs.getBoolean("isLogin", false)
        if (!preferences.getBoolean("notify_course", true)) {
            // 开关关闭时只跳过通知,不能提前 return:每日摘要闹钟是自续链,
            // 这里不重排的话当天之后链条永久断裂,重新打开开关也无法恢复。
            // 登录态仍优先——未登出干净时链应由 cancel 拆除,不能借续链复活。
            if (!loggedIn) {
                cancel(context)
            } else {
                // 当日单次课前提醒是每日 7:30 汇总时挂的 setExactAndAllowWhileIdle
                // 单次闹钟,拆每日链不影响它们:开关关闭后任一已挂闹钟触发走到这里,
                // 必须把当天剩余的单次闹钟一并清掉,否则剩余提醒照常弹出
                // （cancelTodayCourseAlarms 是 companion 的 private 方法,本类可直接访问）。
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                cancelTodayCourseAlarms(context, am)
                ensureScheduled(context)
            }
            return
        }

        // 未登录（登出/切号后）：不仅每日摘要不自续，已挂的当日单次课前提醒
        // 也不再触发——否则登出当天剩余的"即将上课"仍会通知已登出的账号。
        if (!loggedIn) {
            android.util.Log.i("CourseReminderReceiver", "用户未登录，停止课程提醒闹钟链")
            cancel(context)
            return
        }

        val courseName = intent?.getStringExtra(EXTRA_COURSE_NAME)
        if (!courseName.isNullOrEmpty()) {
            showNotification(
                context,
                "即将上课 · $courseName",
                intent.getStringExtra(EXTRA_COURSE_DETAIL).orEmpty(),
                intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1100)
            )
            return
        }

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // BootReceiver 重启后补挂提醒时置位：只重排今天剩余课程的精确闹钟，
        // 不再弹一次"今日课程提醒"汇总通知（开机时已经看过或还未到早晨）。
        val rescheduleOnly = intent?.getBooleanExtra(EXTRA_RESCHEDULE_ONLY, false) == true
        scope.launch {
            try {
                val courses = loadTodayCourses(context)
                if (courses.isNotEmpty()) {
                    if (!rescheduleOnly) {
                        val text = courses.joinToString("\n") { c ->
                            "第${c.begin}-${c.end}节 ${c.name} @${c.address}"
                        }
                        withContext(Dispatchers.Main) {
                            showNotification(context, "今日课程提醒", text, 1001)
                        }
                    }
                    scheduleCourseReminders(context, courses)
                }

            } catch (e: Exception) {
                // 通知权限、系统闹钟服务或缓存异常都不应把每日自续链永久打断。
                android.util.Log.w("CourseReminderReceiver", "本次课程提醒处理失败", e)
            } finally {
                try {
                    ensureScheduled(context)
                } catch (e: Exception) {
                    android.util.Log.w("CourseReminderReceiver", "下一次课程提醒注册失败", e)
                }
                scope.cancel()
                pendingResult.finish()
            }
        }
    }

    private fun loadTodayCourses(context: Context): List<Course> {
        try {
            val appContext = context.applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext, CourseReminderEntryPoint::class.java
            )
            val userRepo = entryPoint.userRepository()
            val cacheManager = entryPoint.cacheManager()
            val user = userRepo.getUser()
            val firstDay = userRepo.termFirstDay
            if (!user.isLogin || firstDay.isEmpty()) return emptyList()

            val syllabus = cacheManager.loadSyllabus(user.account) ?: return emptyList()
            val upcoming = TermResolver.breakTransition()?.upcoming
            val cachedYear = syllabus.searchYearOptions.getOrNull(syllabus.selectedYearOption)
            val cachedTerm = syllabus.searchTermOptions.getOrNull(syllabus.selectedTermOption)
            if (upcoming != null && cachedYear == upcoming.year && cachedTerm == upcoming.term) {
                // 新学期课表已发布但尚未开学：不提醒。
                return emptyList()
            }
            // 缓存学期与日历推断学期不一致（termFirstDay 已翻到新学期、课表缓存还是
            // 旧学期，如 8月末开学周新课表尚未发布）时，旧学期课程不能按新学期周次
            // 推送。与首页 belongsToDifferentTerm 同口径；无论是否处于假期过渡期都生效。
            if (!cachedYear.isNullOrEmpty() && !cachedTerm.isNullOrEmpty()) {
                val inferred = TermResolver.inferCurrentTerm()
                if (cachedYear != inferred.year || cachedTerm != inferred.term) {
                    return emptyList()
                }
            }

            // 天数差用 LocalDate 计算：夏令时/非 UTC+8 时区下两个"本地零点"的
            // 间隔是 23/25 小时，毫秒差整除 24h 会差一天、周次随之错一周
            // （与 ExamDates 的口径一致）。substringBefore(' ') 剥掉 termFirstDay
            // 尾随的保存时刻时分秒；解析失败由外层 catch 兜底返回空表。
            val termStart = LocalDate.parse(firstDay.substringBefore(' '))
            val today = LocalDate.now()
            val diffDays = ChronoUnit.DAYS.between(termStart, today).toInt()
            val currentWeek = ((diffDays / 7) + 1).coerceAtLeast(1)
            // ISO 周序（周一=1…周日=7），与原来的 Calendar.SUNDAY 映射等价
            val todayDay = today.dayOfWeek.value

            // 检测是否在假期中（当前周远超课表范围）
            val maxCourseWeek = if (syllabus.courses.isNotEmpty()) syllabus.courses.maxOf { it.weekEnd } else 20
            if (currentWeek > maxCourseWeek + 4) {
                return emptyList()
            }

            // 检测是否在学期开始前（termFirstDay 在未来）
            if (diffDays < 0) {
                return emptyList()
            }

            val matched = syllabus.courses.filter { c ->
                c.coversWeek(currentWeek) && c.weekDay == todayDay &&
                    (c.oddOrTwice == 0 || (c.oddOrTwice == 1 && currentWeek % 2 == 1) || (c.oddOrTwice == 2 && currentWeek % 2 == 0))
            }.sortedBy { it.begin }

            return matched
        } catch (e: Exception) {
            android.util.Log.w("CourseReminderReceiver", "构建提醒文本失败", e)
            return emptyList()
        }
    }

    private fun scheduleCourseReminders(context: Context, courses: List<Course>) {
        val advanceMinutes = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("notify_advance", "15")?.toIntOrNull() ?: 15
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()

        courses.forEachIndexed { index, course ->
            val start = COURSE_START_TIMES[course.begin] ?: return@forEachIndexed
            val trigger = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, start.first)
                set(Calendar.MINUTE, start.second)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, -advanceMinutes)
            }.timeInMillis
            if (trigger <= now) {
                // 每日汇总广播 7:30 才排今天的精确闹钟，提前量选 30/60 分钟时，
                // 第一节课(8:00)的触发时刻(7:30/7:00)已经过了——直接跳过会让
                // 每天第一节课永远收不到课前提醒。只要还没上课就立即补发，
                // 按真实剩余分钟数展示。
                val startMillis = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, start.first)
                    set(Calendar.MINUTE, start.second)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (now < startMillis) {
                    val remainMinutes = ((startMillis - now) / 60_000L).toInt().coerceAtLeast(1)
                    showNotification(
                        context,
                        "即将上课 · ${course.name}",
                        "${remainMinutes}分钟后 · 第${course.begin}-${course.end}节 · ${course.address}",
                        1100 + index
                    )
                }
                return@forEachIndexed
            }

            // requestCode 须全局唯一。原仅用 DAY_OF_YEAR（每年重复），跨年的 PendingIntent
            // 会因 FLAG_UPDATE_CURRENT 互相覆盖，导致旧一年同一天的提醒顶掉新一年的。
            // 这里乘入年份，保证不同年份的 PendingIntent 互不冲突。
            val cal = Calendar.getInstance()
            val requestCode = cal.get(Calendar.YEAR) * 100000 +
                cal.get(Calendar.DAY_OF_YEAR) * 100 + index + 10
            val reminder = Intent(context, CourseReminderReceiver::class.java).apply {
                putExtra(EXTRA_COURSE_NAME, course.name)
                putExtra(
                    EXTRA_COURSE_DETAIL,
                    "${advanceMinutes}分钟后 · 第${course.begin}-${course.end}节 · ${course.address}"
                )
                putExtra(EXTRA_NOTIFICATION_ID, 1100 + index)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                reminder,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, trigger, pending)
                }
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
        }
    }

    private fun showNotification(context: Context, title: String, text: String, notificationId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Android 13+ 通知权限被拒时 notify() 是静默 no-op：提前跳过，省掉无谓的
        // 通知构建开销，也避免每次提醒都往 logcat 刷警告
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            android.util.Log.i("CourseReminderReceiver", "通知权限未授予，跳过提醒通知")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "课程提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val openApp = PendingIntent.getActivity(
            context, 1001, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId, notification)
    }

    companion object {
        const val CHANNEL_ID = "course_reminder"
        const val EXTRA_RESCHEDULE_ONLY = "reschedule_only"
        private const val EXTRA_COURSE_NAME = "course_name"
        private const val EXTRA_COURSE_DETAIL = "course_detail"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        /**
         * 挂每日 7:30 的课表摘要闹钟（自续链的起点）。幂等：requestCode 固定，
         * 重复调用只会覆盖同一 PendingIntent。BootReceiver 开机重挂与
         * MainViewModel 登录态重挂（登出会拆链，重新登录后必须重挂）都走这里。
         */
        fun ensureScheduled(context: Context) {
            try {
                val intent = Intent(context, CourseReminderReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
                    // 开机补挂场景下（7:30 前重启）今天的摘要还没发，不能直接跳到明天
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, null), pending)
            } catch (e: SecurityException) {
                val pending = PendingIntent.getBroadcast(
                    context, 0, Intent(context, CourseReminderReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 7)
                    set(Calendar.MINUTE, 30)
                    set(Calendar.SECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                android.util.Log.w("CourseReminderReceiver", "无精确闹钟权限，降级为非精确闹钟", e)
            }
        }

        /** 登出时取消每日摘要闹钟（requestCode 与 ensureScheduled 一致），拆断自续链。 */
        fun cancel(context: Context) {
            try {
                val pending = PendingIntent.getBroadcast(
                    context, 0, Intent(context, CourseReminderReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pending)
                pending.cancel()
                cancelTodayCourseAlarms(context, am)
            } catch (e: Exception) {
                android.util.Log.w("CourseReminderReceiver", "取消课程提醒闹钟失败", e)
            }
        }

        /**
         * 清掉当天已挂的单次课前提醒。requestCode = 年*100000+天*100+序号+10，
         * 序号上限 30 覆盖一天内全部课程；Intent 匹配不含 extras，同 component
         * 即可命中。不清理的话登出后当天剩余提醒仍会照常触发。
         */
        private fun cancelTodayCourseAlarms(context: Context, am: AlarmManager) {
            try {
                val cal = Calendar.getInstance()
                val base = cal.get(Calendar.YEAR) * 100000 +
                    cal.get(Calendar.DAY_OF_YEAR) * 100 + 10
                for (i in 0 until 30) {
                    val pending = PendingIntent.getBroadcast(
                        context, base + i, Intent(context, CourseReminderReceiver::class.java),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    ) ?: continue
                    am.cancel(pending)
                    pending.cancel()
                }
            } catch (e: Exception) {
                android.util.Log.w("CourseReminderReceiver", "清理当日课前提醒失败", e)
            }
        }
        private val COURSE_START_TIMES = mapOf(
            1 to (8 to 0),
            2 to (8 to 50),
            3 to (9 to 55),
            4 to (10 to 45),
            5 to (11 to 35),
            6 to (14 to 0),
            7 to (14 to 50),
            8 to (15 to 50),
            9 to (16 to 40),
            10 to (18 to 25),
            11 to (19 to 15),
            12 to (20 to 5)
        )
    }
}

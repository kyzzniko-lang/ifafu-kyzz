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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CourseReminderReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CourseReminderEntryPoint {
        fun userRepository(): UserRepository
        fun cacheManager(): CacheManager
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (!preferences.getBoolean("notify_course", true)) return

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
        scope.launch {
            try {
                val courses = loadTodayCourses(context)
                if (courses.isNotEmpty()) {
                    val text = courses.joinToString("\n") { c ->
                        "第${c.begin}-${c.end}节 ${c.name} @${c.address}"
                    }
                    withContext(Dispatchers.Main) {
                        showNotification(context, "今日课程提醒", text, 1001)
                    }
                    scheduleCourseReminders(context, courses)
                }

                scheduleNext(context)
            } finally {
                scope.cancel()
                pendingResult.finish()
            }
        }
    }

    private fun scheduleNext(context: Context) {
        try {
            val intent = Intent(context, CourseReminderReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, null), pending)
        } catch (e: SecurityException) {
            scheduleFallback(context)
            android.util.Log.w("CourseReminderReceiver", "无精确闹钟权限", e)
        }
    }

    private fun scheduleFallback(context: Context) {
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, CourseReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
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
            // 寒暑假期间，缓存的课表可能还是上一学期（termFirstDay 已被同步到新学期、
            // 但课表缓存未更新）。若缓存学期既不是推断当前学期、也不是已发布新学期，
            // 周数可能恰好落在旧课范围内而误推一条旧学期课程。与首页 belongsToDifferentTerm 对齐。
            if (upcoming != null && !cachedYear.isNullOrEmpty() && !cachedTerm.isNullOrEmpty() &&
                cachedYear != upcoming.year && cachedTerm != upcoming.term
            ) {
                val inferred = TermResolver.inferCurrentTerm()
                if (cachedYear != inferred.year || cachedTerm != inferred.term) {
                    return emptyList()
                }
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val parsed = sdf.parse(firstDay) ?: return emptyList()
            val termStart = Calendar.getInstance().apply { time = parsed }
            val today = Calendar.getInstance()
            val diffDays = ((today.timeInMillis - termStart.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
            val currentWeek = ((diffDays / 7) + 1).coerceAtLeast(1)
            val dayOfWeek = today.get(Calendar.DAY_OF_WEEK)
            val todayDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1

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
                currentWeek in c.weekBegin..c.weekEnd && c.weekDay == todayDay &&
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
            if (trigger <= now) return@forEachIndexed

            val requestCode = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) * 100 + index + 10
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
        private const val EXTRA_COURSE_NAME = "course_name"
        private const val EXTRA_COURSE_DETAIL = "course_detail"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
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

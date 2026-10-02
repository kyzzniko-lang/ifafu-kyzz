package com.ifafu.kyzz.service

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CourseReminderReceiverTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE).edit()
            .putBoolean("isLogin", true).putString("account", "student-a").commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("notify_course", true).commit()
    }

    private fun reminder(account: String, date: LocalDate): Intent =
        Intent(context, CourseReminderReceiver::class.java).apply {
            putExtra("course_name", "高等数学")
            putExtra("course_detail", "15分钟后")
            putExtra("reminder_account", account)
            putExtra("reminder_date", date.toString())
            putExtra("reminder_session", 0L)
        }

    @Test
    fun oldAccountAndDelayedYesterdayAlarmsNeverNotify() {
        val receiver = CourseReminderReceiver()
        receiver.onReceive(context, reminder("student-b", LocalDate.now()))
        receiver.onReceive(context, reminder("student-a", LocalDate.now().minusDays(1)))
        receiver.onReceive(context, reminder("student-a", LocalDate.now()).putExtra("reminder_session", 1L))
        val notifications = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        assertTrue(notifications.allNotifications.isEmpty())
        receiver.onReceive(context, reminder("student-a", LocalDate.now()))
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun disablingReminderCancelsDailyChainAndEnablingRearmsIt() {
        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        CourseReminderReceiver.ensureScheduled(context)
        assertEquals(1, alarms.scheduledAlarms.size)
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("notify_course", false).commit()
        CourseReminderReceiver.ensureScheduled(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("notify_course", true).commit()
        CourseReminderReceiver.ensureScheduled(context)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun logoutCannotRearmTheDailyChain() {
        context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE).edit()
            .putBoolean("isLogin", false).commit()
        CourseReminderReceiver.ensureScheduled(context)
        assertTrue(shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .scheduledAlarms.isEmpty())
    }
}

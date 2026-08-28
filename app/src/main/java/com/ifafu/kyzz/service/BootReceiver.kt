package com.ifafu.kyzz.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // 接受开机完成和应用更新完成两种广播。
        // MY_PACKAGE_REPLACED：App 升级后闹钟会被清除，需重新注册，否则每日提醒静默失效。
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("isLogin", false)) return

        // 每日 7:30 课表摘要 / 12:00 成绩检查。挂链逻辑统一在各 Receiver 的
        // ensureScheduled（含 Android 14+ 无精确闹钟权限的降级路径），
        // 与 MainViewModel 登录态重挂共用同一实现。
        try {
            CourseReminderReceiver.ensureScheduled(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "课程提醒闹钟注册失败", e)
        }
        try {
            ScoreCheckReceiver.ensureScheduled(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "成绩检查闹钟注册失败", e)
        }

        try {
            // 每天按课程逐节挂的"临近上课"精确闹钟是一次性的，重启/升级后被清空
            // 就不会补挂（例如 10:00 重启，10:30 的上课提醒直接丢失）。
            // 触发一次 reschedule-only 的课程提醒广播，把今天剩余课程的闹钟补回来。
            val reschedule = Intent(context, CourseReminderReceiver::class.java).apply {
                putExtra(CourseReminderReceiver.EXTRA_RESCHEDULE_ONLY, true)
            }
            context.sendBroadcast(reschedule)
        } catch (e: Exception) {
            Log.w("BootReceiver", "补挂课程提醒失败", e)
        }

        try {
            // Widget 的 30 分钟更新闹钟同样被系统清空：补发一次更新广播，
            // 有小部件时它会自续后续闹钟，没有则链条自然终止
            com.ifafu.kyzz.widget.iFAFUWidget.pokeAfterBoot(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "补挂小部件更新失败", e)
        }
    }
}

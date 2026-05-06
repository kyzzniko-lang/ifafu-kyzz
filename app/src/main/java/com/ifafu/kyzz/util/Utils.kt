package com.ifafu.kyzz.util

import android.content.Context
import android.content.pm.PackageManager
import java.util.Calendar
import java.util.Locale

object DateUtil {

    fun getStudyTime(firstWeekDateStr: String): Array<String> {
        val parts = firstWeekDateStr.split("-")
        if (parts.size != 3) return arrayOf("第1周", "1", "1")

        val cal = Calendar.getInstance(Locale.getDefault())
        cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
        val firstWeekTime = cal.timeInMillis

        val now = System.currentTimeMillis()
        val diffDays = ((now - firstWeekTime) / (1000 * 60 * 60 * 24)).toInt()
        val nowWeek = diffDays / 7 + 1
        val weekDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val adjustedWeekDay = if (weekDay == Calendar.SUNDAY) 7 else weekDay - 1

        val display = String.format(Locale.getDefault(), "第%d周", nowWeek)
        return arrayOf(display, nowWeek.toString(), adjustedWeekDay.toString())
    }
}

object AppUtil {
    fun getLocalVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0.0"
        }
    }

    fun getLocalVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            1
        }
    }
}

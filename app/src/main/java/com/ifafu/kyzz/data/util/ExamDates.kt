package com.ifafu.kyzz.data.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 考试时间串解析的公共工具。教务 datetime 字段实际出现过这些形态：
 *  - "2026-06-29(周一)09:00-11:00"
 *  - "2026-06-29~2026-06-30(周一)09:00-11:00"（跨天考试）
 *  - "2026年6月29日(周一)09:00-11:00"、"2026/6/29 09:00-11:00"
 *
 * 旧逻辑只取首个日期：跨天考试第一天晚上就被标"已完成"、第二天的冲突检测
 * 不到；日期归一化各用各的 pattern，"2026-06-29"与"2026/6/29"分不到同组。
 */
object ExamDates {

    private val datePatterns = listOf(
        "yyyy-MM-dd", "yyyy/M/d", "yyyy.MM.dd", "yyyy年M月d日", "yyyy年MM月dd日"
    )

    /** 提取 datetime 里的所有日期（跨天考试含起止两天）。解析失败返回空表。 */
    fun allDates(datetime: String): List<Calendar> {
        if (datetime.isEmpty()) return emptyList()
        val raw = datetime.replace("（", "(").replace("）", ")")
            .replace("～", "~").replace("至", "~")
        val result = mutableListOf<Calendar>()
        val candidateRegex = Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日")
        for (candidate in candidateRegex.findAll(raw).map { it.value }.distinct()) {
            for (pattern in datePatterns) {
                try {
                    val fmt = SimpleDateFormat(pattern, Locale.getDefault())
                    fmt.isLenient = false
                    val parsed = fmt.parse(candidate) ?: continue
                    result.add(Calendar.getInstance().apply { time = parsed })
                    break
                } catch (_: Exception) {
                    continue
                }
            }
        }
        return result
    }

    private fun todayZeroed(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /**
     * 所有日期都已早于今天 → 考试结束。跨天考试以最后一天为准；
     * 是否含"今天"由调用方结合当天时段另行判断（参见 [hasDayOn]）。
     */
    fun isAllBeforeToday(datetime: String): Boolean {
        val dates = allDates(datetime)
        if (dates.isEmpty()) return false
        val today = todayZeroed()
        return dates.all { it.before(today) }
    }

    /** datetime 中是否存在落在"今天"的场次。 */
    fun hasDayOn(datetime: String, day: Calendar = todayZeroed()): Boolean =
        allDates(datetime).any { it.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
            it.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR) }

    /** 是否还有"今天或未来"的场次。 */
    fun hasUpcomingDay(datetime: String): Boolean {
        val dates = allDates(datetime)
        if (dates.isEmpty()) return false
        val today = todayZeroed()
        return dates.any { !it.before(today) }
    }

    /** 距离最近一场考试的天数（负数=已过去）。解析失败返回 Int.MAX_VALUE。 */
    fun daysUntilNearest(datetime: String): Int {
        val dates = allDates(datetime)
        if (dates.isEmpty()) return Int.MAX_VALUE
        val today = todayZeroed()
        return dates.minOf { d ->
            Math.floorDiv(d.timeInMillis - today.timeInMillis, 24L * 60 * 60 * 1000).toInt()
        }
    }

    /** 规范化日期 key（统一 yyyy-MM-dd，取首日），供冲突分组使用。 */
    fun canonicalDateKey(datetime: String): String {
        val first = allDates(datetime).firstOrNull() ?: return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(first.time)
    }

    /** 提取首个 "HH:mm-HH:mm" 时段 key（剥离"周一)"等干扰），供冲突分组使用。 */
    fun timeKey(datetime: String): String {
        val m = Regex("(\\d{1,2}:\\d{2})\\s*[-~]\\s*(\\d{1,2}:\\d{2})").find(datetime) ?: return ""
        return "${m.groupValues[1]}-${m.groupValues[2]}"
    }
}

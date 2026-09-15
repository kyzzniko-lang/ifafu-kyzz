package com.ifafu.kyzz.data.util

import java.util.Calendar
import java.util.TimeZone

object TermResolver {

    /**
     * 判"今天"统一取 Asia/Shanghai（与 WeatherApi/CacheManager 同口径）：教务
     * 日历是北京时间，设备时区调整/出国后按本地时区推断会把学期切换点漂移一天。
     */
    private fun shanghaiNow(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))

    data class Term(val year: String, val term: String) {
        fun display(): String {
            val termCn = if (term == "1") "第一学期" else if (term == "2") "第二学期" else "第${term}学期"
            return "${year}学年$termCn"
        }
    }

    data class BreakTransition(val previous: Term, val upcoming: Term)

    fun inferCurrentTerm(now: Calendar = shanghaiNow()): Term {
        val y = now.get(Calendar.YEAR)
        return when {
            now.get(Calendar.MONTH) == Calendar.JANUARY ->
                Term("${y - 1}-$y", "1")
            // 新学年第一学期从"9月1日所在周的周一"开始，与 termFirstDay /
            // estimatedFirstDay 的归一口径一致（教务按整周排课）。2026年9月1日
            // 是周二，该周周一为8月31日：若按自然月等到9月才切换，开学首日仍
            // 被判成上一学年第二学期，旧学期课表会错误套用新学期开学日。
            now.timeInMillis >= academicYearFirstMonday(y) ->
                Term("$y-${y + 1}", "1")
            else ->
                Term("${y - 1}-$y", "2")
        }
    }

    /** 学年第一学期的开学周一（9月1日所在周的周一 00:00）。 */
    private fun academicYearFirstMonday(academicYear: Int): Long {
        return estimatedFirstDay(Term("$academicYear-${academicYear + 1}", "1"))
            ?.timeInMillis
            // 年份非法时按"尚未进入新学年"处理
            ?: Long.MAX_VALUE
    }

    fun pickTerm(
        targetYear: String,
        targetTerm: String,
        availableYears: List<String>,
        availableTerms: List<String>
    ): Term? {
        val yearOk = availableYears.isEmpty() || availableYears.contains(targetYear)
        val termOk = availableTerms.isEmpty() || availableTerms.contains(targetTerm)
        return if (yearOk && termOk) Term(targetYear, targetTerm) else null
    }

    /**
     * 寒暑假不只依赖日期切学期：先探测下一学期是否真的发布了课表。
     * 1-2 月为寒假过渡期，7-8 月为暑假过渡期。
     * 8 月末开学周一（9月1日所在周的周一）之后不再是过渡期：新课表已经是
     * 当前学期，继续按"未开学"处理会把开学第一周的今日课程藏掉。
     */
    fun breakTransition(now: Calendar = shanghaiNow()): BreakTransition? {
        val year = now.get(Calendar.YEAR)
        return when (now.get(Calendar.MONTH)) {
            Calendar.JANUARY, Calendar.FEBRUARY -> BreakTransition(
                previous = Term("${year - 1}-$year", "1"),
                upcoming = Term("${year - 1}-$year", "2")
            )
            Calendar.JULY, Calendar.AUGUST ->
                if (now.timeInMillis >= academicYearFirstMonday(year)) null
                else BreakTransition(
                    previous = Term("${year - 1}-$year", "2"),
                    upcoming = Term("$year-${year + 1}", "1")
                )
            else -> null
        }
    }

    /**
     * 按校历惯例估算某学期的开学周一：第一学期取 9 月 1 日所在周的周一，
     * 第二学期取 3 月 2 日所在周的周一。与 MainViewModel.setDefaultTermFirstDay
     * 的兜底口径一致（学年形如 "2026-2027"，第二学期在学年次年的春季）。
     */
    fun estimatedFirstDay(term: Term): Calendar? {
        val startYear = term.year.substringBefore('-').toIntOrNull() ?: return null
        // 与 inferCurrentTerm 的默认 now 同用 Asia/Shanghai：开学周一按设备时区
        // 推导时，边界比较（timeInMillis）两侧时区错位最多 8 小时，边界日判定漂移。
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        when (term.term) {
            "1" -> cal.set(startYear, Calendar.SEPTEMBER, 1, 0, 0, 0)
            "2" -> cal.set(startYear + 1, Calendar.MARCH, 2, 0, 0, 0)
            else -> return null
        }
        cal.set(Calendar.MILLISECOND, 0)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysToMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_YEAR, -daysToMonday)
        return cal
    }
}

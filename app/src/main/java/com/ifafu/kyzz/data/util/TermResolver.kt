package com.ifafu.kyzz.data.util

import java.util.Calendar

object TermResolver {

    data class Term(val year: String, val term: String) {
        fun display(): String {
            val termCn = if (term == "1") "第一学期" else if (term == "2") "第二学期" else "第${term}学期"
            return "${year}学年$termCn"
        }
    }

    data class BreakTransition(val previous: Term, val upcoming: Term)

    fun inferCurrentTerm(now: Calendar = Calendar.getInstance()): Term {
        val y = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        return when (month) {
            1 -> Term("${y - 1}-$y", "1")
            // 8 月仍处于上一学年暑假，9 月再切换到新学年。
            in 2..8 -> Term("${y - 1}-$y", "2")
            else -> Term("$y-${y + 1}", "1")
        }
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
     */
    fun breakTransition(now: Calendar = Calendar.getInstance()): BreakTransition? {
        val year = now.get(Calendar.YEAR)
        return when (now.get(Calendar.MONTH) + 1) {
            1, 2 -> BreakTransition(
                previous = Term("${year - 1}-$year", "1"),
                upcoming = Term("${year - 1}-$year", "2")
            )
            7, 8 -> BreakTransition(
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
        val cal = Calendar.getInstance()
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

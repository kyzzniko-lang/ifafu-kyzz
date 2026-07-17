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
}

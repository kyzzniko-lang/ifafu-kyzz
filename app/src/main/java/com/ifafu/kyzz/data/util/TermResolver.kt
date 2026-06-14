package com.ifafu.kyzz.data.util

import java.util.Calendar

object TermResolver {

    data class Term(val year: String, val term: String) {
        fun display(): String {
            val termCn = if (term == "1") "第一学期" else if (term == "2") "第二学期" else "第${term}学期"
            return "${year}学年$termCn"
        }
    }

    fun inferCurrentTerm(now: Calendar = Calendar.getInstance()): Term {
        val y = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        return when (month) {
            1 -> Term("${y - 1}-$y", "1")
            in 2..7 -> Term("${y - 1}-$y", "2")
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
}

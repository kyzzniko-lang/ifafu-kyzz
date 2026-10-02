package com.ifafu.kyzz.data.model

data class Course(
    var account: String = "",
    var name: String = "",
    var teacher: String = "",
    var address: String = "",
    var timeString: String = "",
    var weekDay: Int = 0,
    var begin: Int = 0,
    var end: Int = 0,
    var weekBegin: Int = 0,
    var weekEnd: Int = 0,
    /**
     * 完整的上课周次集合，可表达非连续周（如"第1-8,10,12-16周"）。
     * null 表示旧缓存/连续周，按 [weekBegin]..[weekEnd] 区间理解。
     * weekBegin/weekEnd 恒为该集合的 min/max，仅供旧显示与排序路径兜底，
     * 周次判断必须走 [coversWeek]。
     */
    var weeks: List<Int>? = null,
    var oddOrTwice: Int = 0,
    var examDate: String = "",
    var examTime: String = "",
    var examAddress: String = ""
) {
    /** 本周是否有这节课（不含单双周过滤，由调用方叠加 [oddOrTwice] 判断）。 */
    fun coversWeek(week: Int): Boolean =
        week in 1..30 && (weeks?.contains(week) ?: (week in weekBegin..weekEnd))

    /** 课程占用的全部周次（不含单双周过滤）。 */
    fun activeWeeks(): List<Int> =
        weeks?.filter { it in 1..30 }?.distinct()?.sorted()
            ?: (weekBegin.coerceAtLeast(1)..weekEnd.coerceAtMost(30)).toList()
}

/** 是否为非连续周排课（影响日历导出等按区间假设的逻辑）。 */
val Course.hasIrregularWeeks: Boolean
    get() {
        val w = weeks ?: return false
        return w.size < (weekEnd - weekBegin + 1)
    }

data class CourseScheduleIdentity(
    val name: String,
    val teacher: String,
    val address: String,
    val weekDay: Int,
    val begin: Int,
    val end: Int,
    val weekBegin: Int,
    val weekEnd: Int,
    val oddOrTwice: Int,
    val weeks: List<Int> = emptyList()
)

/** A concrete recurring entry; preserves room, teacher, week range and parity changes. */
fun Course.scheduleIdentity() = CourseScheduleIdentity(
    name = name,
    teacher = teacher,
    address = address,
    weekDay = weekDay,
    begin = begin,
    end = end,
    weekBegin = weekBegin,
    weekEnd = weekEnd,
    oddOrTwice = oddOrTwice,
    weeks = activeWeeks().distinct().sorted()
)

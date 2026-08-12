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
    var oddOrTwice: Int = 0,
    var examDate: String = "",
    var examTime: String = "",
    var examAddress: String = ""
)

data class CourseScheduleIdentity(
    val name: String,
    val teacher: String,
    val address: String,
    val weekDay: Int,
    val begin: Int,
    val end: Int,
    val weekBegin: Int,
    val weekEnd: Int,
    val oddOrTwice: Int
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
    oddOrTwice = oddOrTwice
)

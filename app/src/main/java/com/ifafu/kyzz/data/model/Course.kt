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

package com.ifafu.kyzz.data.model

data class SimpleCourse(
    var courseIndex: String = "",
    var name: String = "",
    var code: String = "",
    var teacher: String = "",
    var credits: String = "",
    var weekHours: String = "",
    var examMethod: String = "",
    var college: String = "",
    var direction: String = "",
    var nature: String = "",
    var capacity: String = "",
    var selected: Boolean = false
)

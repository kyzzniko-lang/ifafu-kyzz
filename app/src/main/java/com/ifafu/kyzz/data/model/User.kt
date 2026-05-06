package com.ifafu.kyzz.data.model

data class User(
    var account: String = "",
    var name: String = "",
    var token: String = "",
    var institute: String = "",
    var clas: String = "",
    var enrollment: Int = 0,
    var isLogin: Boolean = false
)

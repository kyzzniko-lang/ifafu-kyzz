package com.ifafu.kyzz.data.model

data class ElectiveCourse(
    var courseIndex: String = "",
    var name: String = "",
    var code: String = "",
    var teacher: String = "",
    var time: String = "",
    var location: String = "",
    var studyScore: Float = 0f,
    var weekStudyTime: String = "",
    var weekTime: String = "",
    var allHave: Int = 0,
    var have: Int = 0,
    var owner: String = "",
    var nature: String = "",
    var campus: String = "",
    var college: String = "",
    var examTime: String = ""
)

data class ElectiveCourseList(
    var courses: MutableList<ElectiveCourse> = mutableListOf(),
    var electived: MutableList<ElectiveCourse> = mutableListOf(),
    var curPage: Int = 1,
    var pageSize: Int = 1,
    var filter: ElectiveFilter = ElectiveFilter(),
    var viewState: String = "",
    var viewStateGenerator: String = ""
)

data class ElectiveFilter(
    var courseNature: MutableList<String> = mutableListOf(),
    var courseNatureIndex: Int = 0,
    var isFree: MutableList<String> = mutableListOf(),
    var isFreeIndex: Int = 0,
    var courseOwner: MutableList<String> = mutableListOf(),
    var courseOwnerIndex: Int = 0,
    var courseCampus: MutableList<String> = mutableListOf(),
    var courseCampusIndex: Int = 0,
    var courseTime: MutableList<String> = mutableListOf(),
    var courseTimeIndex: Int = 0,
    var courseNameFilter: String? = null
)

data class ElectiveTask(
    var courseName: String = "",
    var courseIndex: String? = null,
    var viewState: String? = null,
    var viewStateGenerator: String? = null,
    var natureFilter: String? = null,
    var haveFilter: String? = null,
    var ownerFilter: String? = null,
    var campusFilter: String? = null,
    var timeFilter: String? = null,
    var nameFilter: String? = null,
    var curPage: Int? = null
)

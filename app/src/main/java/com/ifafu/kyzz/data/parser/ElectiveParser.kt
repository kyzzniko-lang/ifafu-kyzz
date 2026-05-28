package com.ifafu.kyzz.data.parser

import android.util.Log
import com.ifafu.kyzz.data.model.ElectiveCourse
import com.ifafu.kyzz.data.model.ElectiveCourseList
import com.ifafu.kyzz.data.model.ElectiveFilter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ElectiveParser @Inject constructor(
    private val htmlParser: HtmlParser
) {

    companion object {
        private const val TAG = "ElectiveParser"
    }

    fun parseCourseList(doc: Document, courseList: ElectiveCourseList) {
        courseList.courses.clear()
        courseList.electived.clear()

        val tables = doc.select("table")
        Log.d(TAG, "Found ${tables.size} tables in document")
        val fullHtml = doc.html()
        var section = 0 // 0 = available, 1 = selected

        // Find the "已选课程"/"已选列表" marker position once
        val selectedPos1 = fullHtml.indexOf("已选课程")
        val selectedPos2 = fullHtml.indexOf("已选列表")
        val markerPos = when {
            selectedPos1 >= 0 && selectedPos2 >= 0 -> minOf(selectedPos1, selectedPos2)
            selectedPos1 >= 0 -> selectedPos1
            selectedPos2 >= 0 -> selectedPos2
            else -> -1
        }

        var searchStart = 0

        for (table in tables) {
            val rows = table.select("tr")
            if (rows.isEmpty()) continue

            val headerCells = rows[0].select("th, td")
            if (headerCells.isEmpty()) continue
            val headerTexts = headerCells.map { it.text().trim() }

            // Skip tables without course-related headers
            if (headerTexts.none { it.contains("课程") }) {
                Log.d(TAG, "Skipping table: headers=$headerTexts")
                continue
            }

            // Check if marker appears before this table using full outerHtml with tracked position
            if (section == 0 && markerPos >= 0) {
                val tableHtml = table.outerHtml()
                val tablePos = fullHtml.indexOf(tableHtml, searchStart)
                if (tablePos >= 0) {
                    searchStart = tablePos + 1
                    if (markerPos < tablePos) {
                        section = 1
                    }
                }
            }

            val hasSelectColumn = headerTexts.any { it.contains("选定") || it.contains("选课") }
            val isAlreadySelected = section == 1 && !hasSelectColumn
            val colMap = if (hasSelectColumn) {
                val shiftedHeaders = headerTexts.drop(1)
                buildColumnMap(shiftedHeaders)
            } else {
                buildColumnMap(headerTexts)
            }

            for (i in 1 until rows.size) {
                val cells = rows[i].select("td")
                if (cells.size < 3) continue

                val course = ElectiveCourse()
                var dataStart = 0

                // If has select checkbox column, extract courseIndex from first cell
                if (hasSelectColumn && cells.isNotEmpty()) {
                    val input = cells[0].select("input").firstOrNull()
                    course.courseIndex = input?.attr("name")?.takeIf { it.isNotBlank() }
                        ?: rows[i].attr("id").takeIf { it.isNotBlank() }
                        ?: "row_$i"
                    dataStart = 1
                }

                val dataCells = cells.subList(dataStart, cells.size)
                if (dataCells.isEmpty()) continue

                if (colMap.isNotEmpty()) {
                    mapByHeaders(dataCells, colMap, course)
                } else {
                    mapByPosition(dataCells, course, isAlreadySelected)
                }

                if (course.name.isNotBlank()) {
                    if (isAlreadySelected) {
                        courseList.electived.add(course)
                    } else {
                        courseList.courses.add(course)
                    }
                }
            }
        }

        // Parse pagination
        val content = doc.html()
        val pagePattern = Regex("第.*?(\\d+).*?页/共.*?(\\d+).*?页")
        val pageMatch = pagePattern.find(content)
        if (pageMatch != null) {
            courseList.curPage = pageMatch.groupValues[1].toIntOrNull() ?: 1
            courseList.pageSize = pageMatch.groupValues[2].toIntOrNull() ?: 1
        }

        Log.d(TAG, "Parsed ${courseList.courses.size} available, ${courseList.electived.size} selected courses")
    }

    private fun buildColumnMap(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        headers.forEachIndexed { index, h ->
            when {
                h.contains("课程名称") -> map["name"] = index
                h.contains("课程代码") -> map["code"] = index
                h.contains("教师") -> map["teacher"] = index
                h.contains("上课时间") || h.contains("时间") -> map["time"] = index
                h.contains("上课地点") || h.contains("地点") -> map["location"] = index
                h.contains("学分") -> map["credits"] = index
                h.contains("周学时") -> map["weekStudyTime"] = index
                h.contains("容量") -> map["capacity"] = index
                h.contains("余量") -> map["remaining"] = index
                h.contains("课程归属") || h.contains("归属") -> map["owner"] = index
                h.contains("课程性质") || h.contains("性质") -> map["nature"] = index
                h.contains("校区") -> map["campus"] = index
                h.contains("开课学院") || h.contains("学院") -> map["college"] = index
                h.contains("考核") || h.contains("考试") -> map["exam"] = index
                h.contains("起始结束周") || h.contains("周次") -> map["weekTime"] = index
                h.contains("专业方向") || h.contains("方向") -> map["direction"] = index
            }
        }
        return map
    }

    private fun mapByHeaders(cells: List<Element>, colMap: Map<String, Int>, course: ElectiveCourse) {
        fun cell(key: String): String {
            val idx = colMap[key] ?: return ""
            return if (idx < cells.size) htmlParser.cleanNbsp(cells[idx].text().trim()) else ""
        }

        course.name = cell("name")
        course.code = cell("code")
        course.teacher = cell("teacher")
        course.time = cell("time")
        course.location = cell("location")
        course.studyScore = cell("credits").toFloatOrNull() ?: 0f
        course.weekStudyTime = cell("weekStudyTime")
        course.weekTime = cell("weekTime")
        course.owner = cell("owner")
        course.nature = cell("nature")
        course.campus = cell("campus")
        course.college = cell("college")
        course.examTime = cell("exam")

        val capacityStr = cell("capacity")
        val remainingStr = cell("remaining")
        course.allHave = capacityStr.toIntOrNull() ?: 0
        course.have = remainingStr.toIntOrNull() ?: 0

        // If no separate remaining column, try parsing "N/M" format in capacity
        if (course.have == 0 && capacityStr.contains("/")) {
            val parts = capacityStr.split("/")
            if (parts.size == 2) {
                course.have = parts[0].trim().toIntOrNull() ?: 0
                course.allHave = parts[1].trim().toIntOrNull() ?: 0
            }
        }
    }

    private fun mapByPosition(cells: List<Element>, course: ElectiveCourse, isSelected: Boolean) {
        fun cell(idx: Int): String {
            return if (idx < cells.size) htmlParser.cleanNbsp(cells[idx].text().trim()) else ""
        }

        if (isSelected) {
            // 已选课程: 课程名称, 教师姓名, 学分, 周学时, 起始结束周, 校区, 上课时间, 上课地点, 教材, 课程归属, 课程性质, 校区代码
            course.name = cell(0)
            course.teacher = cell(1)
            course.studyScore = cell(2).toFloatOrNull() ?: 0f
            course.weekStudyTime = cell(3)
            course.weekTime = cell(4)
            course.campus = cell(5)
            course.time = cell(6)
            course.location = cell(7)
            // cell(8) = 教材
            course.owner = cell(9)
            course.nature = cell(10)
        } else {
            // 可选课程 (typical): 课程名称, 课程代码, 教师姓名, 上课时间, 上课地点, 学分, 周学时, 起始结束周, 容量, 余量, ...
            // or: 课程代码, 课程名称, 学分, 周学时, 考核方式, 开课学院, 专业方向, 课程性质, 容量
            // Heuristic: if cell(0) looks like a code (digits), it's code-first layout
            val firstCell = cell(0)
            if (firstCell.matches(Regex("^[A-Za-z0-9]+$")) && firstCell.length > 3) {
                // Code-first layout (专业选修课 style)
                course.code = cell(0)
                course.name = cell(1)
                course.studyScore = cell(2).toFloatOrNull() ?: 0f
                course.weekStudyTime = cell(3)
                course.college = cell(5)
                course.nature = cell(7)
                course.allHave = cell(8).toIntOrNull() ?: 0
            } else {
                // Name-first layout (个性发展课 style)
                course.name = cell(0)
                course.code = cell(1)
                course.teacher = cell(2)
                course.time = cell(3)
                course.location = cell(4)
                course.studyScore = cell(5).toFloatOrNull() ?: 0f
                course.weekStudyTime = cell(6)
                course.weekTime = cell(7)
                course.allHave = cell(8).toIntOrNull() ?: 0
                course.have = cell(9).toIntOrNull() ?: 0
                course.owner = cell(10)
                course.nature = cell(11)
                course.campus = cell(12)
                course.college = cell(13)
                course.examTime = htmlParser.cleanNbsp(cell(14))
            }
        }
    }

    fun parseFilter(doc: Document, filter: ElectiveFilter) {
        val html = doc.html()
        parseOptions(html, "课程性质：", "有无余量：", filter.courseNature) { filter.courseNatureIndex = it }
        parseOptions(html, "有无余量：", "课程归属：", filter.isFree) { filter.isFreeIndex = it }
        parseOptions(html, "课程归属：", "上课校区：", filter.courseOwner) { filter.courseOwnerIndex = it }
        parseOptions(html, "上课校区：", "上课时间：", filter.courseCampus) { filter.courseCampusIndex = it }
        parseOptions(html, "上课时间：", null, filter.courseTime) { filter.courseTimeIndex = it }
    }

    private fun parseOptions(
        html: String, startTag: String, endTag: String?,
        options: MutableList<String>, setIndex: (Int) -> Unit
    ) {
        options.clear()
        val startIdx = html.indexOf(startTag)
        if (startIdx < 0) return
        val endIdx = if (endTag != null) html.indexOf(endTag) else html.length
        if (endIdx < 0) return

        val pattern = Regex("""<option( selected="selected")? value="([^"]*)">""")
        pattern.findAll(html.substring(startIdx, endIdx)).forEach { match ->
            options.add(match.groupValues[2])
            if (match.groupValues[1].isNotEmpty()) {
                setIndex(options.size - 1)
            }
        }
    }
}

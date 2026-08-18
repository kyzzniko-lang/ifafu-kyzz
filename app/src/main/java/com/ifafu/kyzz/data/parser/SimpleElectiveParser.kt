package com.ifafu.kyzz.data.parser

import com.ifafu.kyzz.data.model.SimpleCourse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimpleElectiveParser @Inject constructor() {

    fun parseCourses(html: String): Pair<List<SimpleCourse>, List<SimpleCourse>> {
        val doc = Jsoup.parse(html)
        val available = mutableListOf<SimpleCourse>()
        val selected = mutableListOf<SimpleCourse>()

        val tables = doc.select("table")
        val fullHtml = doc.html()
        var section = 0 // 0 = available, 1 = selected

        // 计算"已选"标记在文档中的位置（常量，避免在每个表格内重复查找）。
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

            if (headerTexts.none { it.contains("课程") || it.contains("项目") }) continue

            // Check if "已选" marker appears before this table in document order。
            // 旧实现用 tableHtml.take(80) 定位，多个表格的前 80 字符相同会命中第一个表格，
            // 导致"已选课程"分段判定错位；改为用完整 outerHtml + searchStart 追踪真实位置。
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

            val hasSelect = headerTexts.any { it.contains("选定") || it.contains("选课") }
            // 有复选框列时数据单元格从第 2 列开始（offset=1），表头也必须同步去掉
            // 复选框列，否则 name/teacher/credits 等全部读到右侧相邻列的值。
            val colMap = buildColumnMap(if (hasSelect) headerTexts.drop(1) else headerTexts)

            for (i in 1 until rows.size) {
                val cells = rows[i].select("td")
                if (cells.size < 3) continue

                val course = SimpleCourse()
                var offset = 0

                if (hasSelect && cells.isNotEmpty()) {
                    val input = cells[0].select("input").firstOrNull()
                    course.courseIndex = input?.attr("name")?.takeIf { it.isNotBlank() }
                        ?: rows[i].attr("id").takeIf { it.isNotBlank() }
                        ?: ""
                    offset = 1
                }

                val dataCells = cells.subList(offset, cells.size)
                if (dataCells.isEmpty()) continue

                if (colMap.isNotEmpty()) {
                    mapByHeaders(dataCells, colMap, course)
                } else {
                    mapByPosition(dataCells, course, section == 1)
                }

                if (course.name.isNotBlank()) {
                    course.selected = section == 1
                    if (section == 1) selected.add(course) else available.add(course)
                }
            }
        }

        return Pair(available, selected)
    }

    // Keep backward compatibility
    fun parseAvailableCourses(html: String): List<SimpleCourse> = parseCourses(html).first
    fun parseSelectedCourses(html: String): List<SimpleCourse> = parseCourses(html).second

    private fun buildColumnMap(headers: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        headers.forEachIndexed { index, h ->
            when {
                h.contains("课程名称") || h.contains("项目名称") -> map["name"] = index
                h.contains("课程代码") || h.contains("项目代码") -> map["code"] = index
                h.contains("教师") -> map["teacher"] = index
                h.contains("上课时间") || h.contains("时间") -> map["time"] = index
                h.contains("上课地点") || h.contains("地点") -> map["location"] = index
                h.contains("学分") -> map["credits"] = index
                h.contains("周学时") -> map["weekHours"] = index
                h.contains("容量") -> map["capacity"] = index
                h.contains("余量") -> map["remaining"] = index
                h.contains("课程归属") || h.contains("归属") -> map["owner"] = index
                h.contains("课程性质") || h.contains("性质") -> map["nature"] = index
                h.contains("校区") -> map["campus"] = index
                h.contains("开课学院") || h.contains("学院") -> map["college"] = index
                h.contains("考核") -> map["exam"] = index
                h.contains("方向") -> map["direction"] = index
                h.contains("起始结束周") -> map["weekTime"] = index
            }
        }
        return map
    }

    private fun mapByHeaders(cells: List<Element>, colMap: Map<String, Int>, course: SimpleCourse) {
        fun cell(key: String): String {
            val idx = colMap[key] ?: return ""
            return if (idx < cells.size) cells[idx].text().trim() else ""
        }

        course.name = cell("name")
        course.code = cell("code")
        course.teacher = cell("teacher")
        course.credits = cell("credits")
        course.weekHours = cell("weekHours")
        course.examMethod = cell("exam")
        course.college = cell("college")
        course.direction = cell("direction")
        course.nature = cell("nature")
        course.capacity = cell("capacity")
    }

    private fun mapByPosition(cells: List<Element>, course: SimpleCourse, isSelected: Boolean) {
        fun cell(idx: Int): String {
            return if (idx < cells.size) cells[idx].text().trim() else ""
        }

        if (isSelected) {
            // Typical selected: 课程名称, 教师, 学分, 周学时, ...
            // or: 课程代码, 课程名称, 学分, 周学时, 考核, 学院, 方向, 性质, 教师
            val first = cell(0)
            if (first.matches(Regex("^[A-Za-z0-9]+$")) && first.length > 3) {
                course.code = cell(0)
                course.name = cell(1)
                course.credits = cell(2)
                course.weekHours = cell(3)
                course.teacher = if (cells.size >= 9) cell(8) else ""
            } else {
                course.name = cell(0)
                course.teacher = cell(1)
                course.credits = cell(2)
                course.weekHours = cell(3)
            }
        } else {
            // Typical available: 课程代码, 课程名称, 学分, 周学时, 考核, 学院, 方向, 性质, 容量
            // or: 课程名称, 课程代码, 教师, 时间, 地点, 学分, 周学时, ...
            val first = cell(0)
            if (first.matches(Regex("^[A-Za-z0-9]+$")) && first.length > 3) {
                course.code = cell(0)
                course.name = cell(1)
                course.credits = cell(2)
                course.weekHours = cell(3)
                course.examMethod = cell(4)
                course.college = cell(5)
                course.direction = cell(6)
                course.nature = cell(7)
                course.capacity = cell(8)
            } else {
                course.name = cell(0)
                course.code = cell(1)
                course.teacher = cell(2)
                course.credits = if (cells.size >= 6) cell(5) else cell(3)
                course.weekHours = if (cells.size >= 7) cell(6) else cell(4)
                course.capacity = if (cells.size >= 9) cell(8) else ""
            }
        }
    }

    fun isNotOpen(html: String): Boolean {
        val text = Jsoup.parse(html).text()
        return text.contains("未到选课时间") || text.contains("未开放选课") || text.contains("不在选课时间内")
                || text.contains("没有数据")
    }
}

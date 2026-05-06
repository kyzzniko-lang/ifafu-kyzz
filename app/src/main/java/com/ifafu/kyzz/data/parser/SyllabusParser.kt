package com.ifafu.kyzz.data.parser

import android.util.Log
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.model.Syllabus
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyllabusParser @Inject constructor(
    private val htmlParser: HtmlParser
) {

    fun parseSyllabus(doc: Document, account: String): Syllabus {
        val syllabus = Syllabus()
        val html = doc.html()

        val yearOptions = htmlParser.parseSearchOptions(doc, "id=\"xnd\"", "学年第")
        syllabus.searchYearOptions = yearOptions.options.toMutableList()
        syllabus.selectedYearOption = yearOptions.selectedIndex

        val termStart = html.indexOf("学年第")
        if (termStart >= 0) {
            val termOptions = htmlParser.parseSearchOptions(doc, "学年第", "id=\"xqd\"")
            syllabus.searchTermOptions = termOptions.options.toMutableList()
            syllabus.selectedTermOption = termOptions.selectedIndex
        }

        syllabus.courses = parseCourses(doc, account, syllabus)
        return syllabus
    }

    private fun parseCourses(doc: Document, account: String, syllabus: Syllabus): List<Course> {
        val courses = mutableListOf<Course>()

        val table = doc.select("table#kbtable").first()
            ?: doc.select("table[id=xskb_form]").first()
            ?: doc.select("table").firstOrNull { it.html().contains("周") && it.html().contains("节") }

        Log.d("SyllabusParser", "Table found: ${table != null}")
        if (table != null) {
            Log.d("SyllabusParser", "Table id: ${table.id()}, tagName: ${table.tagName()}")
            val rows = table.select("tr")
            Log.d("SyllabusParser", "Rows found: ${rows.size}")
            for (row in rows) {
                val cells = row.select("td[align=Center], td[align=center]")
                Log.d("SyllabusParser", "  Cells with align=Center: ${cells.size}")
                for (cell in cells) {
                    val cellHtml = cell.html()
                    Log.d("SyllabusParser", "  Cell HTML: ${cellHtml.take(200)}")
                    parseCourseFromCell(cell, account, syllabus, courses)
                }
            }
        }

        Log.d("SyllabusParser", "Courses from table: ${courses.size}")
        if (courses.isEmpty()) {
            Log.d("SyllabusParser", "Trying regex fallback...")
            parseCoursesFromRegex(doc.html(), account, syllabus, courses)
            Log.d("SyllabusParser", "Courses from regex: ${courses.size}")
        }

        Log.d("SyllabusParser", "Total courses: ${courses.size}")
        for (c in courses) {
            Log.d("SyllabusParser", "  Course: ${c.name}, weekDay=${c.weekDay}, begin=${c.begin}, end=${c.end}, week=${c.weekBegin}-${c.weekEnd}")
        }
        return courses
    }

    private fun parseCourseFromCell(cell: Element, account: String, syllabus: Syllabus, courses: MutableList<Course>) {
        val html = cell.html()
        Log.d("SyllabusParser", "  Cell HTML: ${html.take(500)}")

        val blocks = html.split(Regex("<br\\s*/?>\\s*<br\\s*/?>"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "&nbsp;" }

        Log.d("SyllabusParser", "  Cell blocks: ${blocks.size}")

        for (block in blocks) {
            val parts = block.split(Regex("<br\\s*/?>"))
                .map { it.replace(Regex("<[^>]+>"), "").trim() }
                .filter { it.isNotEmpty() && it != "&nbsp;" }

            if (parts.size < 2) continue

            Log.d("SyllabusParser", "  Parts (${parts.size}): $parts")

            val course = Course()
            course.account = account
            course.name = parts[0]

            // 找到时间字符串的索引（包含"周"和"节"）
            var timeIndex = -1
            for (i in parts.indices) {
                if (parts[i].contains("周") && parts[i].contains("节")) {
                    timeIndex = i
                    parseCourseTime(course, parts[i])
                    break
                }
            }

            if (timeIndex < 0) {
                Log.w("SyllabusParser", "  No time string found in parts: $parts")
                continue
            }

            val afterTimeParts = parts.filterIndexed { i, _ -> i > timeIndex }
            val beforeTimeParts = parts.filterIndexed { i, _ -> i in 1 until timeIndex }

            when {
                afterTimeParts.size >= 2 -> {
                    course.teacher = afterTimeParts[0]
                    course.address = afterTimeParts[1]
                }
                afterTimeParts.size == 1 -> {
                    course.address = afterTimeParts[0]
                    if (beforeTimeParts.isNotEmpty()) {
                        course.teacher = beforeTimeParts[0]
                    }
                }
                afterTimeParts.isEmpty() && beforeTimeParts.isNotEmpty() -> {
                    course.teacher = beforeTimeParts[0]
                }
            }

            Log.d("SyllabusParser", "  Course: ${course.name}, weekDay=${course.weekDay}, begin=${course.begin}, end=${course.end}, teacher=${course.teacher}, address=${course.address}")

            if (course.name.isNotEmpty() && course.weekDay > 0) {
                if (syllabus.campus == 0 && course.address.contains("旗教")) {
                    syllabus.campus = 1
                }
                courses.add(course)
            }
        }
    }

    private fun parseCoursesFromRegex(html: String, account: String, syllabus: Syllabus, courses: MutableList<Course>) {
        val pattern = Regex(
            "(<br>|<td( class=\"noprint\")? align=\"Center\"" +
            "( rowspan=\"\\d+\")?( width=\"\\d+%\")?>)(((?!td).)*?)<br>" +
            "(((?!td).)*?)<br>(((?!td).)*?)<br>(((?!td).)*?)(<br>(((?!td).)*?)年(.*?)月(.*?)日(.*?)<br>(.*?))?(<br>|</td>)"
        )

        pattern.findAll(html).forEach { match ->
            val course = Course()
            course.account = account
            course.name = match.groupValues[5].trim()
            course.teacher = match.groupValues[9].trim()
            course.address = match.groupValues[11].trim()
            if (syllabus.campus == 0 && course.address.contains("旗教")) {
                syllabus.campus = 1
            }
            parseCourseTime(course, match.groupValues[7])
            if (course.name.isNotEmpty() && course.weekDay > 0) {
                courses.add(course)
            }
        }
    }

    private fun parseCourseTime(course: Course, timeString: String) {
        val weekMap = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "日" to 7
        )
        val pattern = Regex("周(.)(第?)(\\d+)([,，\\-](\\d+))?节\\{第(\\d+)-(\\d+)周(\\|(.)周)?\\}")
        val match = pattern.find(timeString)
        if (match == null) {
            Log.d("SyllabusParser", "    -> parseCourseTime NO MATCH for: $timeString")
            return
        }
        Log.d("SyllabusParser", "    -> parseCourseTime MATCH: groups=${match.groupValues}")
        course.timeString = timeString
        course.weekDay = weekMap[match.groupValues[1]] ?: 1

        course.begin = match.groupValues[3].toIntOrNull() ?: 1
        course.end = match.groupValues[5].toIntOrNull() ?: course.begin

        course.weekBegin = match.groupValues[6].toIntOrNull() ?: 1
        course.weekEnd = match.groupValues[7].toIntOrNull() ?: 1
        if (match.groupValues[9].isNotEmpty()) {
            course.oddOrTwice = when (match.groupValues[9]) {
                "单" -> 1
                "双" -> 2
                else -> 0
            }
        }
    }
}

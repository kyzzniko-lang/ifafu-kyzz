package com.ifafu.kyzz.data.parser

import android.util.Log
import com.ifafu.kyzz.data.model.AdjustCourse
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.model.InternshipCourse
import com.ifafu.kyzz.data.model.PracticeCourse
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.model.UnscheduledCourse
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

        val yearOptions = htmlParser.parseSearchOptions(doc, "id=\"xnd\"", "</select>")
        syllabus.searchYearOptions = yearOptions.options.toMutableList()
        syllabus.selectedYearOption = yearOptions.selectedIndex

        val termOptions = htmlParser.parseSearchOptions(doc, "id=\"xqd\"", "</select>").excludeTerms("3")
        syllabus.searchTermOptions = termOptions.options.toMutableList()
        syllabus.selectedTermOption = termOptions.selectedIndex

        syllabus.currentWeek = parseCurrentWeek(html)

        syllabus.courses = parseCourses(doc, account, syllabus)
        syllabus.adjustCourses = parseAdjustCourses(doc)
        syllabus.practiceCourses = parsePracticeCourses(doc)
        syllabus.internshipCourses = parseInternshipCourses(doc)
        syllabus.unscheduledCourses = parseUnscheduledCourses(doc)

        for (course in syllabus.courses) {
            course.teacher = cleanDuplicateName(course.teacher)
        }

        return syllabus
    }

    private fun cleanDuplicateName(name: String): String {
        val trimmed = name.trim()
        val match = Regex("^(.+?)[(（]\\1[)）]$").find(trimmed)
        return match?.groupValues?.get(1) ?: trimmed
    }

    private fun parseCurrentWeek(html: String): Int {
        val patterns = listOf(
            Regex("第(\\d+)教学周"),
            Regex("当前.*?第(\\d+)周"),
            Regex("现在.*?第(\\d+)周"),
            Regex("本周.*?第(\\d+)周"),
            Regex(">第(\\d+)周<"),
            Regex("dsyl\">第(\\d+)周")
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val week = match.groupValues[1].toIntOrNull()
                if (week != null && week in 1..30) {
                    Log.d("SyllabusParser", "Parsed currentWeek=$week")
                    return week
                }
            }
        }
        Log.d("SyllabusParser", "Could not parse currentWeek from HTML")
        return 0
    }

    private fun parseCourses(doc: Document, account: String, syllabus: Syllabus): List<Course> {
        val courses = mutableListOf<Course>()

        val table = doc.select("table#kbtable").firstOrNull()
            ?: doc.select("table#Table1").firstOrNull()
            ?: doc.select("table[id=xskb_form]").firstOrNull()
            ?: doc.select("table").firstOrNull {
                val t = it.text()
                t.contains("星期一") && t.contains("星期日") && t.contains("第1节")
            }

        Log.d("SyllabusParser", "Table found: ${table != null}")
        if (table != null) {
            Log.d("SyllabusParser", "Table id: ${table.id()}, tagName: ${table.tagName()}")
            val rows = table.select("tr")
            Log.d("SyllabusParser", "Rows found: ${rows.size}")

            var currentSection = 0

            // Track rowspan occupancy: rowspanOccupied[col] = remaining rows occupied
            var rowspanOccupied = IntArray(7) { 0 }

            for (row in rows) {
                // Decrement all occupied counters
                rowspanOccupied = rowspanOccupied.map { (it - 1).coerceAtLeast(0) }.toIntArray()

                // Collect all td elements in order, including section-label tds
                val allTds = row.select("td")
                for (td in allTds) {
                    val m = Regex("第(\\d+)节").find(td.text())
                    if (m != null) {
                        currentSection = m.groupValues[1].toIntOrNull() ?: currentSection
                    }
                }

                val dataCells = row.select("td[align=Center], td[align=center]")
                if (dataCells.isEmpty()) continue

                val courseCells = dataCells.filter { cell ->
                    val text = cell.text().trim()
                    !Regex("第\\d+节").containsMatchIn(text) &&
                        !Regex("^(上午|下午|晚上|早晨)").containsMatchIn(text)
                }

                var colIndex = 0
                for (cell in courseCells) {
                    // Skip columns occupied by rowspan from previous rows
                    while (colIndex < 7 && rowspanOccupied[colIndex] > 0) {
                        colIndex++
                    }
                    if (colIndex >= 7) break

                    val weekDay = colIndex + 1
                    val rowspan = cell.attr("rowspan").toIntOrNull() ?: 1
                    if (rowspan > 1) {
                        rowspanOccupied[colIndex] = rowspan - 1
                    }
                    colIndex++

                    Log.d("SyllabusParser", "  Cell weekDay=$weekDay, section=$currentSection")
                    parseCourseFromCell(cell, account, syllabus, courses, weekDay, currentSection)
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

    private fun parseCourseFromCell(cell: Element, account: String, syllabus: Syllabus, courses: MutableList<Course>, cellWeekDay: Int, cellSection: Int) {
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

            var timeIndex = -1
            for (i in parts.indices) {
                if (parts[i].contains("周") && parts[i].contains("节")) {
                    val parsed = parseCourseTime(course, parts[i])
                    if (parsed) {
                        timeIndex = i
                        break
                    }
                }
            }

            if (timeIndex < 0) {
                val weekOnlyParsed = parseWeekOnly(course, parts)
                if (weekOnlyParsed) {
                    course.weekDay = cellWeekDay
                    if (course.begin == 0) course.begin = cellSection
                    if (course.end == 0) course.end = cellSection

                    val sectionsPerWeek = Regex("(\\d+)节/周").find(course.timeString)?.groupValues?.get(1)?.toIntOrNull()
                    if (sectionsPerWeek != null && sectionsPerWeek > 1) {
                        course.end = course.begin + sectionsPerWeek - 1
                    }

                    val remainingParts = parts.filterIndexed { i, part ->
                        i > 0 && !part.contains(Regex("^\\{第"))
                    }
                    if (remainingParts.size >= 2) {
                        course.teacher = remainingParts[0]
                        course.address = remainingParts[1]
                    } else if (remainingParts.size == 1) {
                        course.teacher = remainingParts[0]
                    }

                    Log.d("SyllabusParser", "  Course (pos fallback): ${course.name}, weekDay=${course.weekDay}, begin=${course.begin}, end=${course.end}")

                    if (course.name.isNotEmpty() && course.weekDay > 0) {
                        courses.add(course)
                    }
                    continue
                }

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

            val examRegex = Regex("(\\d{4})年(\\d{2})月(\\d{2})日\\((.*?)\\)")
            for (part in parts) {
                val examMatch = examRegex.find(part)
                if (examMatch != null) {
                    course.examDate = "${examMatch.groupValues[1]}-${examMatch.groupValues[2]}-${examMatch.groupValues[3]}"
                    course.examTime = examMatch.groupValues[4]
                    val examAddrIndex = parts.indexOf(part) + 1
                    if (examAddrIndex < parts.size) {
                        course.examAddress = parts[examAddrIndex]
                    }
                    break
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

    private fun parseWeekOnly(course: Course, parts: List<String>): Boolean {
        for (part in parts) {
            val pattern = Regex("\\{第(\\d+)-(\\d+)周(\\|(\\d+)节/周)?\\}")
            val match = pattern.find(part)
            if (match != null) {
                course.timeString = part
                course.weekBegin = match.groupValues[1].toIntOrNull() ?: 1
                course.weekEnd = match.groupValues[2].toIntOrNull() ?: 1
                return true
            }
        }
        return false
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

    private fun parseCourseTime(course: Course, timeString: String): Boolean {
        val weekMap = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "日" to 7
        )
        val pattern = Regex("周(.)(上午|下午|晚上)?第(\\d+)(([,，\\-]\\d+)*)节\\{第(\\d+)-(\\d+)周(\\|(.)周)?\\}")
        val match = pattern.find(timeString)
        if (match == null) {
            Log.d("SyllabusParser", "    -> parseCourseTime NO MATCH for: $timeString")
            return false
        }
        Log.d("SyllabusParser", "    -> parseCourseTime MATCH: groups=${match.groupValues}")
        course.timeString = timeString
        course.weekDay = weekMap[match.groupValues[1]] ?: 1

        course.begin = match.groupValues[3].toIntOrNull() ?: 1
        val extraSections = match.groupValues[4]
        if (extraSections.isNotEmpty()) {
            val allNums = Regex("\\d+").findAll(extraSections).map { it.value.toInt() }.toList()
            course.end = allNums.maxOrNull() ?: course.begin
        } else {
            course.end = course.begin
        }

        course.weekBegin = match.groupValues[6].toIntOrNull() ?: 1
        course.weekEnd = match.groupValues[7].toIntOrNull() ?: 1
        if (match.groupValues[9].isNotEmpty()) {
            course.oddOrTwice = when (match.groupValues[9]) {
                "单" -> 1
                "双" -> 2
                else -> 0
            }
        }
        return true
    }

    private fun parseAdjustCourses(doc: Document): List<AdjustCourse> {
        val result = mutableListOf<AdjustCourse>()
        val html = doc.html()

        val sectionStart = html.indexOf("调、停（补）课信息")
        if (sectionStart < 0) return result

        val sectionHtml = html.substring(sectionStart)
        val tables = doc.select("table")
        for (table in tables) {
            val tableHtml = table.html()
            if (!tableHtml.contains("原上课时间地点教师") && !tableHtml.contains("现上课时间地点教师")) continue
            if (!sectionHtml.contains(tableHtml.take(80))) continue

            val rows = table.select("tr")
            for (i in 1 until rows.size) {
                val tds = rows[i].select("td")
                if (tds.size >= 5) {
                    result.add(AdjustCourse(
                        id = tds[0].text().trim(),
                        name = tds[1].text().trim(),
                        original = tds[2].text().trim(),
                        adjusted = tds[3].text().trim(),
                        applyTime = tds[4].text().trim()
                    ))
                }
            }
            break
        }
        return result
    }

    private fun parsePracticeCourses(doc: Document): List<PracticeCourse> {
        val result = mutableListOf<PracticeCourse>()
        val html = doc.html()

        val sectionStart = html.indexOf("实践课(或无上课时间)信息")
        if (sectionStart < 0) return result

        val sectionHtml = html.substring(sectionStart)
        val tables = doc.select("table")
        for (table in tables) {
            val tableHtml = table.html()
            if (!tableHtml.contains("课程名称") || !tableHtml.contains("起止周")) continue
            if (tableHtml.contains("原上课时间地点教师")) continue
            if (!sectionHtml.contains(tableHtml.take(80))) continue

            val rows = table.select("tr")
            for (i in 1 until rows.size) {
                val tds = rows[i].select("td")
                if (tds.size >= 4) {
                    result.add(PracticeCourse(
                        name = tds[0].text().trim(),
                        teacher = tds[1].text().trim(),
                        credit = tds[2].text().trim(),
                        weeks = tds.getOrNull(3)?.text()?.trim() ?: "",
                        time = tds.getOrNull(4)?.text()?.trim() ?: "",
                        location = tds.getOrNull(5)?.text()?.trim() ?: ""
                    ))
                }
            }
            break
        }
        return result
    }

    private fun parseInternshipCourses(doc: Document): List<InternshipCourse> {
        val result = mutableListOf<InternshipCourse>()
        val html = doc.html()

        val sectionStart = html.indexOf("实习课信息")
        if (sectionStart < 0) return result

        val sectionHtml = html.substring(sectionStart)
        val tables = doc.select("table")
        for (table in tables) {
            val tableHtml = table.html()
            if (!tableHtml.contains("实习时间") && !tableHtml.contains("模块代号")) continue
            if (tableHtml.contains("原上课时间地点教师") || tableHtml.contains("起止周")) continue
            if (!sectionHtml.contains(tableHtml.take(80))) continue

            val rows = table.select("tr")
            for (i in 1 until rows.size) {
                val tds = rows[i].select("td")
                if (tds.size >= 3) {
                    result.add(InternshipCourse(
                        year = tds.getOrNull(0)?.text()?.trim() ?: "",
                        term = tds.getOrNull(1)?.text()?.trim() ?: "",
                        name = tds.getOrNull(2)?.text()?.trim() ?: "",
                        time = tds.getOrNull(3)?.text()?.trim() ?: "",
                        moduleCode = tds.getOrNull(4)?.text()?.trim() ?: "",
                        prerequisite = tds.getOrNull(5)?.text()?.trim() ?: "",
                        internshipId = tds.getOrNull(6)?.text()?.trim() ?: ""
                    ))
                }
            }
            break
        }
        return result
    }

    private fun parseUnscheduledCourses(doc: Document): List<UnscheduledCourse> {
        val result = mutableListOf<UnscheduledCourse>()
        val html = doc.html()

        val sectionStart = html.indexOf("未安排上课时间的课程")
        if (sectionStart < 0) return result

        val sectionHtml = html.substring(sectionStart)
        val tables = doc.select("table")

        val unscheduledTable = tables.lastOrNull { table ->
            val t = table.text()
            t.contains("学年") && t.contains("学期") && t.contains("学分") &&
                !table.html().contains("原上课时间地点教师") &&
                !table.html().contains("起止周") &&
                !table.html().contains("实习时间") &&
                !table.html().contains("模块代号")
        } ?: return result

        val rows = unscheduledTable.select("tr")
        for (i in 1 until rows.size) {
            val tds = rows[i].select("td")
            if (tds.size >= 5) {
                result.add(UnscheduledCourse(
                    year = tds[0].text().trim(),
                    term = tds[1].text().trim(),
                    name = tds[2].text().trim(),
                    teacher = tds[3].text().trim(),
                    credit = tds[4].text().trim()
                ))
            }
        }
        return result
    }
}

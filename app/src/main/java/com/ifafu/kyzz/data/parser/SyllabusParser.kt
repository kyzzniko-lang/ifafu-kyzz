package com.ifafu.kyzz.data.parser

import android.util.Log
import com.ifafu.kyzz.data.model.AdjustCourse
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.model.InternshipCourse
import com.ifafu.kyzz.data.model.PracticeCourse
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.model.UnscheduledCourse
import org.jsoup.Jsoup
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
            val rows = table.select("tr").toList().filter { it.closest("table") === table }
            Log.d("SyllabusParser", "Rows found: ${rows.size}")

            var currentSection = 0

            // 列容量按该表实际几何推导(th+td 均计入,表头“星期一~星期日”行常用
            // th),不满足时至少保底 7 天;rowspan 占位下标越界处再动态扩容,
            // 避免 9 节以上或异常多列的课表把周日课程整列静默丢弃。
            val geometryColumns = rows.maxOfOrNull { it.select("th, td").size } ?: 0
            var rowspanOccupied = IntArray(maxOf(geometryColumns, 8)) { 0 }

            for (row in rows) {
                // Decrement all occupied counters
                rowspanOccupied = rowspanOccupied.map { (it - 1).coerceAtLeast(0) }.toIntArray()

                // Collect all td elements in order, including section-label tds
                // 显式标注 List<Element>: Elements 自带的 filter(NodeFilter) SAM
                // 重载会抢先于 kotlin stdlib 扩展解析,导致布尔 lambda 无法使用
                val allTds: List<Element> = row.children().toList().filter { it.tagName() == "td" }
                for (td in allTds) {
                    val m = Regex("第(\\d+)节").matchEntire(td.text().trim())
                    if (m != null) {
                        currentSection = m.groupValues[1].toIntOrNull() ?: currentSection
                    }
                }

                // 对齐属性大小写不敏感取值过滤：选择器 td[align=center] 漏配
                // align="CENTER" 等变体时该行所有课程都会被丢弃。
                val dataCells = allTds.filter { "center" == it.attr("align").lowercase() }
                if (dataCells.isEmpty()) continue

                // 过滤条件只有两类标签单元格:节次标签(整格文本恰为"第N节")与上下午分隔。
                // 节次判定必须整格锚定:单节次课程格的时间串"周三第5节{第1-16周}"
                // 同样含"第N节"子串,未锚定的包含匹配会把整格当标签列丢弃,且该格
                // 不推进 colIndex,同行右侧课程整体左移一天(多节次"第1,2节"因
                // 逗号隔断不受影响,故只有单节次课程漏网)。
                // 注意"&nbsp;"空占位格绝不能在此剔除——无课时段也是格子,它们
                // 推进下方 colIndex 才能让后续课程落进正确的星期列;一旦把空格
                // 滤掉,该行剩余课程全部左移串天。多课程堆叠的长文本格同理,
                // 内容再长也必须保留参与占位。
                val courseCells = dataCells.filter { cell ->
                    val text = cell.text().trim()
                    !Regex("第\\d+节").matches(text) &&
                        !Regex("^(上午|下午|晚上|早晨)").containsMatchIn(text)
                }

                var colIndex = 0
                for (cell in courseCells) {
                    // Skip columns occupied by rowspan from previous rows
                    while (colIndex < rowspanOccupied.size && rowspanOccupied[colIndex] > 0) {
                        colIndex++
                    }
                    if (colIndex >= rowspanOccupied.size) {
                        // 动态扩容代替 break:新槽位默认无占用
                        if (rowspanOccupied.size >= 14) {
                            Log.w("SyllabusParser", "课表列数超过安全上限(${rowspanOccupied.size}),剩余单元格放弃")
                            break
                        }
                        rowspanOccupied = rowspanOccupied.copyOf(rowspanOccupied.size + 4)
                    }

                    val weekDay = colIndex + 1
                    val rowspan = cell.attr("rowspan").toIntOrNull() ?: 1
                    if (rowspan > 1) {
                        // 计数器在每行开头统一减 1，因此这里必须存完整 rowspan：
                        // 若存 rowspan-1，下一行开头的减法会立即清零，rowspan=2 的课
                        // 实际上一行也不占用，导致下方课程整体左移一天。
                        rowspanOccupied[colIndex] = rowspan
                    }
                    colIndex++

                    if (weekDay > 7) {
                        // 超出星期日:占位仍要推进(保持本行后续映射),但不把
                        // 非法星期写库。旧行为是整行 break——那会连带丢弃周日课程。
                        Log.w("SyllabusParser", "单元格映射到周$weekDay,超出星期范围已跳过")
                        continue
                    }

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
                .map { htmlParser.cleanNbsp(Jsoup.parseBodyFragment(it).text()) }
                .filter { it.isNotEmpty() && it != "&nbsp;" }

            if (parts.size < 2) continue

            Log.d("SyllabusParser", "  Parts (${parts.size}): $parts")

            val course = Course()
            course.account = account
            course.name = parts[0]
            parseCourseExam(course, parts)

            var timeIndex = -1
            for (i in parts.indices) {
                if (parts[i].contains("周") && parts[i].contains("节")) {
                    val parsed = parseCourseTime(course, parts[i], cellWeekDay)
                    if (parsed) {
                        timeIndex = i
                        break
                    }
                }
            }

            if (timeIndex < 0) {
                // 明确写有节次却非法时不能再按格位兜底，防止坏数据变成另一场课。
                if (parts.any { Regex("周[一二三四五六日天].*第.*节").containsMatchIn(it) }) continue
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

                    if (course.name.isNotEmpty() && course.weekDay in 1..7 &&
                        course.begin in 1..12 && course.end in course.begin..12) {
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

            Log.d("SyllabusParser", "  Course: ${course.name}, weekDay=${course.weekDay}, begin=${course.begin}, end=${course.end}, teacher=${course.teacher}, address=${course.address}")

            if (course.name.isNotEmpty() && course.weekDay > 0) {
                // 去重：同名同天同节次同周集合同单双周的课程只保留一条。必须比
                // oddOrTwice（以及地点）：同一格拆成"第1-16周|单周 张三"和
                // "第1-16周|双周 李四"是单双周换教室/教师的常见排法，不比的话
                // 第二段会被误判重复丢弃，单周或双周的场次从此消失。
                // end 与 weeks 也参与比较：同名同起始节但时长/周次不同的两段
                // 是不同排课，只比 begin 会误丢后一段。
                val isDuplicate = courses.any {
                    it.name == course.name && it.weekDay == course.weekDay &&
                        it.begin == course.begin && it.end == course.end &&
                        it.weeks == course.weeks && it.weekBegin == course.weekBegin &&
                        it.weekEnd == course.weekEnd && it.oddOrTwice == course.oddOrTwice &&
                        it.address == course.address && it.teacher == course.teacher
                }
                if (!isDuplicate) {
                    if (syllabus.campus == 0 && course.address.contains("旗教")) {
                        syllabus.campus = 1
                    }
                    courses.add(course)
                }
            }
        }
    }

    private fun parseCourseExam(course: Course, parts: List<String>) {
        val pattern = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日[（(](.*?)[）)]")
        for ((index, part) in parts.withIndex()) {
            val match = pattern.find(part) ?: continue
            course.examDate = "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
            course.examTime = match.groupValues[4]
            course.examAddress = parts.getOrNull(index + 1).orEmpty()
            return
        }
    }

    private fun parseWeekOnly(course: Course, parts: List<String>): Boolean {
        for (part in parts) {
            // 捕获完整大括号内容后统一走 parseWeekList：兼容"第1-8,10,12-16周"
            // （逗号分隔的非连续周，旧正则不匹配导致整门课被静默丢弃）与
            // "第1-8周,第10周,第12-16周"（旧正则只取首段、其余周次丢失）。
            val pattern = Regex("\\{第([^}]*)\\}")
            val match = pattern.find(part)
            if (match != null) {
                val parsed = parseWeekList(match.groupValues[1])
                if (parsed == null) {
                    // 非周次格式（如 "{实践}"），继续尝试下一个 part
                    continue
                }
                course.timeString = part
                applyWeeks(course, parsed.first)
                val suffix = parsed.second
                course.oddOrTwice = when {
                    suffix.contains("单") -> 1
                    suffix.contains("双") -> 2
                    else -> 0
                }
                return true
            }
        }
        return false
    }

    /**
     * 解析大括号内的周次串。支持三种实际形态：
     * - "1-16"（连续，兼容旧行为）
     * - "1-8,10,12-16周"（逗号分隔，"周"只在末尾）
     * - "1-8周,第10周,第12-16周"（每段带"周"）
     * 返回 (周次集合, 尾缀)：尾缀含"单/双/上/N节/周"等附加文本，供单双周判断。
     * 无法解析返回 null。
     */
    private fun parseWeekList(content: String): Pair<List<Int>, String>? {
        val segmentRegex = Regex("^第?(\\d+)(?:[-~](\\d+))?周?")
        val weeks = mutableListOf<Int>()
        var rest = content.trim()
        while (true) {
            rest = rest.trimStart(',', '，', '、', ' ')
            if (rest.isEmpty()) break
            val m = segmentRegex.find(rest) ?: break
            val begin = m.groupValues[1].toIntOrNull() ?: return null
            val end = m.groupValues[2].toIntOrNull() ?: begin
            if (begin <= 0 || end < begin || end > 30) return null
            weeks.addAll(begin..end)
            rest = rest.substring(m.value.length)
        }
        if (weeks.isEmpty()) return null
        return weeks.distinct().sorted() to rest
    }

    /** 写入周次集合，weekBegin/weekEnd 恒为 min/max 以兼容旧显示与排序路径。 */
    private fun applyWeeks(course: Course, weeks: List<Int>) {
        course.weeks = weeks
        course.weekBegin = weeks.min()
        course.weekEnd = weeks.max()
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
            // 单元格段落顺序与 jsoup 路径一致：课程名<br>时间<br>教师<br>教室<br>[考试信息]。
            // 分组编号：g5=名称 g6=时间 g7=教师 g8=教室，g9~g14 是可选的考试时间块
            // （g9=整块、g11=年、g12=月、g13=日+节次、g14=考场）。
            // 旧代码误用 g9/g11（考试块/年份数字）且把教师段当时间解析，
            // 导致回退路径解析不出任何课程。
            if (parseCourseTime(course, match.groupValues[6])) {
                course.teacher = match.groupValues[7].trim()
                course.address = match.groupValues[8].trim()
            } else if (parseCourseTime(course, match.groupValues[7])) {
                // 个别学校顺序为：课程名<br>教师<br>时间<br>教室
                course.teacher = match.groupValues[6].trim()
                course.address = match.groupValues[8].trim()
            } else {
                return@forEach
            }
            val examMatch = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\((.*?)\\)").find(match.groupValues[9])
            if (examMatch != null) {
                course.examDate = "${examMatch.groupValues[1]}-${examMatch.groupValues[2]}-${examMatch.groupValues[3]}"
                course.examTime = examMatch.groupValues[4]
                course.examAddress = match.groupValues[14].trim()
            }
            if (syllabus.campus == 0 && course.address.contains("旗教")) {
                syllabus.campus = 1
            }
            if (course.name.isNotEmpty() && course.weekDay > 0) {
                courses.add(course)
            }
        }
    }

    private fun parseCourseTime(course: Course, timeString: String, cellWeekDay: Int = 0): Boolean {
        val weekMap = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "日" to 7, "天" to 7
        )
        // 节次分隔额外容忍"~"；周次部分捕获完整大括号内容后统一走 parseWeekList
        // （支持"第1-8,10,12-16周"非连续周与"第1-8周,第10周,第12-16周"分段格式；
        // 旧正则只认单一连续区间，非连续周整门课被静默丢弃或周次被截断），
        // 单双周改从尾缀中提取
        val pattern = Regex("周(.)(上午|下午|晚上)?第(\\d+)(([,，\\-~]\\d+)*)节\\{第([^}]*)\\}")
        val match = pattern.find(timeString)
        if (match == null) {
            Log.d("SyllabusParser", "    -> parseCourseTime NO MATCH for: $timeString")
            return false
        }
        Log.d("SyllabusParser", "    -> parseCourseTime MATCH: groups=${match.groupValues}")
        course.timeString = timeString
        val parsedWeekDay = weekMap[match.groupValues[1]]
        if (parsedWeekDay != null) {
            course.weekDay = parsedWeekDay
        } else if (cellWeekDay > 0) {
            // 正则匹配到的字符不在映射中，使用列位置确定的星期
            Log.w("SyllabusParser", "Unrecognized weekday char '${match.groupValues[1]}', falling back to cellWeekDay=$cellWeekDay")
            course.weekDay = cellWeekDay
        } else {
            // 无法确定星期，跳过此课程
            Log.w("SyllabusParser", "Unrecognized weekday char '${match.groupValues[1]}' and no cellWeekDay, skipping course")
            return false
        }

        course.begin = match.groupValues[3].toIntOrNull() ?: 1
        val extraSections = match.groupValues[4]
        if (extraSections.isNotEmpty()) {
            val allNums = Regex("\\d+").findAll(extraSections).mapNotNull { it.value.toIntOrNull() }.toList()
            course.end = allNums.maxOrNull() ?: course.begin
        } else {
            course.end = course.begin
        }
        if (course.begin !in 1..12 || course.end !in course.begin..12) return false

        // 周次内容是第 6 组：第 5 组是节次部分 (([,，\-~]\d+)*) 的内层重复组。
        // 写错成 [5] 时单节次课拿到空串被丢弃、多节次课把",2"解析成"第2周上课"。
        val weekContent = match.groupValues[6]
        val weekParsed = parseWeekList(weekContent)
        if (weekParsed == null) {
            Log.w("SyllabusParser", "Cannot parse week list '$weekContent' in: $timeString")
            return false
        }
        applyWeeks(course, weekParsed.first)
        val weekSuffix = weekParsed.second
        if (weekSuffix.isNotEmpty()) {
            course.oddOrTwice = when {
                weekSuffix.contains("单") -> 1
                weekSuffix.contains("双") -> 2
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

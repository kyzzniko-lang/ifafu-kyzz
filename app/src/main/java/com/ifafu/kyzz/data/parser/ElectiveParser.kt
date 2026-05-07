package com.ifafu.kyzz.data.parser

import com.ifafu.kyzz.data.model.ElectiveCourse
import com.ifafu.kyzz.data.model.ElectiveCourseList
import com.ifafu.kyzz.data.model.ElectiveFilter
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ElectiveParser @Inject constructor(
    private val htmlParser: HtmlParser
) {

    fun parseCourseList(doc: Document, courseList: ElectiveCourseList) {
        courseList.courses.clear()
        val content = doc.html().replace("\r", "").replace("\n", "")
        val courseListIndex = content.indexOf("kcmcGrid__ctl2_xk")
        if (courseListIndex < 0) return

        val electiveListIndex = content.indexOf("已选课程")
        if (electiveListIndex < 0) return
        val courseListContent = content.substring(courseListIndex, electiveListIndex)

        val pattern = Regex(
            "name=\"(.*?)\".*?window\\.open\\('(.*?)'\\)\">(.*?)" +
            "</a></td><td>(.*?)<.*?window\\.open\\('(.*?)'\\)\">(.*?)</a></td><td( title=\"(.*?)\")?.*?<td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)" +
            "</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td>",
            RegexOption.DOT_MATCHES_ALL
        )

        pattern.findAll(courseListContent).forEach { match ->
            courseList.courses.add(ElectiveCourse(
                courseIndex = match.groupValues[1],
                name = match.groupValues[3],
                code = match.groupValues[4],
                teacher = match.groupValues[6],
                time = htmlParser.cleanNbsp(match.groupValues[8]),
                location = htmlParser.cleanNbsp(match.groupValues[9]),
                studyScore = match.groupValues[10].toFloatOrNull() ?: 0f,
                weekStudyTime = match.groupValues[11],
                weekTime = match.groupValues[12],
                allHave = match.groupValues[13].toIntOrNull() ?: 0,
                have = match.groupValues[14].toIntOrNull() ?: 0,
                owner = match.groupValues[15],
                nature = match.groupValues[16],
                campus = match.groupValues[17],
                college = match.groupValues[18],
                examTime = htmlParser.cleanNbsp(match.groupValues[19])
            ))
        }

        val pagePattern = Regex("第.*?(\\d+).*?页/共.*?(\\d+).*?页")
        val pageMatch = pagePattern.find(content)
        if (pageMatch != null) {
            courseList.curPage = pageMatch.groupValues[1].toIntOrNull() ?: 1
            courseList.pageSize = pageMatch.groupValues[2].toIntOrNull() ?: 1
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

package com.ifafu.kyzz.data.parser

import com.ifafu.kyzz.data.model.CreditSummary
import com.ifafu.kyzz.data.model.GradeExam
import com.ifafu.kyzz.data.model.TrainingCourse
import com.ifafu.kyzz.data.model.TrainingPlan
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrainingPlanParser @Inject constructor(
    private val htmlParser: HtmlParser
) {

    fun parse(doc: Document): TrainingPlan {
        val plan = TrainingPlan()
        val html = doc.html()

        plan.college = extractFieldValue(html, "学院") { s -> s }
        plan.major = extractFieldValue(html, "专业") { s -> s }

        plan.courses = parseCourses(doc)
        plan.creditSummary = parseCreditSummary(doc)
        return plan
    }

    private fun <T> extractFieldValue(html: String, label: String, transform: (String) -> T): T {
        val idx = html.indexOf(label)
        if (idx >= 0) {
            val after = html.substring(idx + label.length)
            val match = Regex(">([^<]+)<").find(after)
            if (match != null) {
                return transform(htmlParser.cleanNbsp(match.groupValues[1]))
            }
        }
        return transform("")
    }

    private fun parseCourses(doc: Document): List<TrainingCourse> {
        val courses = mutableListOf<TrainingCourse>()
        val tables = doc.select("table")

        for (table in tables) {
            val headerText = table.text()
            if (!headerText.contains("课程代码") || !headerText.contains("建议修读学期")) continue
            if (headerText.contains("毕业学分要求")) continue

            val rows = table.select("tr")
            for (i in 1 until rows.size) {
                val tds = rows[i].select("td")
                if (tds.size >= 8) {
                    courses.add(TrainingCourse(
                        code = tds[0].text().trim(),
                        name = tds[1].text().trim(),
                        credit = tds[2].text().trim(),
                        weeklyHours = tds[3].text().trim(),
                        examType = tds[4].text().trim(),
                        courseNature = tds[5].text().trim(),
                        courseCategory = tds[6].text().trim(),
                        suggestTerm = tds[7].text().trim(),
                        weeks = tds.getOrNull(13)?.text()?.trim() ?: tds.getOrNull(10)?.text()?.trim() ?: ""
                    ))
                }
            }
            break
        }
        return courses
    }

    private fun parseCreditSummary(doc: Document): List<CreditSummary> {
        val result = mutableListOf<CreditSummary>()
        val tables = doc.select("table")

        for (table in tables) {
            val headerText = table.text()
            if (!headerText.contains("毕业学分要求")) continue

            val rows = table.select("tr")
            for (row in rows) {
                val tds = row.select("td")
                if (tds.size >= 2) {
                    val label = tds[0].text().trim()
                    val credit = tds[1].text().trim()
                    if (label.isNotEmpty() && credit.isNotEmpty() && label != "毕业学分要求" && label != "公选课学分要求") {
                        result.add(CreditSummary(label, credit))
                    }
                }
            }
            break
        }
        return result
    }

    fun parseGradeExams(doc: Document): List<GradeExam> {
        val exams = mutableListOf<GradeExam>()
        val tables = doc.select("table")

        for (table in tables) {
            val headerText = table.text()
            if (!headerText.contains("等级考试名称") || !headerText.contains("准考证号")) continue

            val rows = table.select("tr")
            for (i in 1 until rows.size) {
                val tds = rows[i].select("td")
                if (tds.size >= 5) {
                    exams.add(GradeExam(
                        year = tds.getOrNull(0)?.text()?.trim() ?: "",
                        term = tds.getOrNull(1)?.text()?.trim() ?: "",
                        name = tds.getOrNull(2)?.text()?.trim() ?: "",
                        ticketNumber = tds.getOrNull(3)?.text()?.trim() ?: "",
                        date = tds.getOrNull(4)?.text()?.trim() ?: "",
                        score = tds.getOrNull(5)?.text()?.trim() ?: "",
                        listeningScore = tds.getOrNull(6)?.text()?.trim() ?: "",
                        readingScore = tds.getOrNull(7)?.text()?.trim() ?: "",
                        writingScore = tds.getOrNull(8)?.text()?.trim() ?: "",
                        comprehensiveScore = tds.getOrNull(9)?.text()?.trim() ?: ""
                    ))
                }
            }
            break
        }
        return exams
    }
}

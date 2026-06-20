package com.ifafu.kyzz.data.parser

import com.ifafu.kyzz.data.model.Exam
import com.ifafu.kyzz.data.model.ExamTable
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExamParser @Inject constructor(
    private val htmlParser: HtmlParser
) {

    fun parseExamTable(doc: Document): ExamTable {
        val examTable = ExamTable()
        val html = doc.html()

        val yearOptions = htmlParser.parseSearchOptions(doc, "id=\"xnd\"", "</select>")
        examTable.searchYearOptions = yearOptions.options.toMutableList()
        examTable.selectedYearOption = yearOptions.selectedIndex

        val termOptions = htmlParser.parseSearchOptions(doc, "id=\"xqd\"", "</select>").excludeTerms("3")
        examTable.searchTermOptions = termOptions.options.toMutableList()
        examTable.selectedTermOption = termOptions.selectedIndex

        examTable.exams = parseExams(doc)
        return examTable
    }

    private fun parseExams(doc: Document): List<Exam> {
        val exams = mutableListOf<Exam>()

        val table = doc.select("table#DataGrid1").firstOrNull()
            ?: doc.select("table#Datagrid1").firstOrNull()
            ?: doc.select("table[id~=(?i)^datagrid1$]").firstOrNull()
            ?: doc.select("table").firstOrNull { it.html().contains("校区") && it.html().contains("考试时间") }

        if (table != null) {
            val rows = table.select("tr")
            for (row in rows) {
                if (row.select("th").isNotEmpty() || row.hasClass("datelisthead")) continue
                val cells = row.select("td")
                if (cells.isNotEmpty() && cells[0].text().contains("选课课号")) continue
                if (cells.size >= 8) {
                    exams.add(Exam(
                        id = htmlParser.cleanNbsp(cells[0].text()),
                        name = htmlParser.cleanNbsp(cells[1].text()),
                        datetime = htmlParser.cleanNbsp(cells[3].text()),
                        address = htmlParser.cleanNbsp(cells[4].text()),
                        seatNumber = htmlParser.cleanNbsp(cells[6].text()),
                        campus = htmlParser.cleanNbsp(cells[7].text())
                    ))
                }
            }
        }

        if (exams.isEmpty()) {
            parseExamsFromRegex(doc.html(), exams)
        }

        return exams
    }

    private fun parseExamsFromRegex(html: String, exams: MutableList<Exam>) {
        val tableStart = html.indexOf("校区")
        if (tableStart < 0) return

        val tableContent = html.substring(tableStart)
        val pattern = Regex(
            "<td>(.*?)</td><td>(.*?)</td><td>(.*?)</td>" +
            "<td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td>"
        )

        pattern.findAll(tableContent).forEach { match ->
            exams.add(Exam(
                id = htmlParser.cleanNbsp(match.groupValues[1]),
                name = htmlParser.cleanNbsp(match.groupValues[2]),
                datetime = htmlParser.cleanNbsp(match.groupValues[4]),
                address = htmlParser.cleanNbsp(match.groupValues[5]),
                seatNumber = htmlParser.cleanNbsp(match.groupValues[7]),
                campus = htmlParser.cleanNbsp(match.groupValues[8])
            ))
        }
    }
}

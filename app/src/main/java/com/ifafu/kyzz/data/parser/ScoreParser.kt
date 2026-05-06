package com.ifafu.kyzz.data.parser

import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.model.ScoreTable
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoreParser @Inject constructor(
    private val htmlParser: HtmlParser
) {

    fun parseScoreTable(doc: Document): ScoreTable {
        val scoreTable = ScoreTable()
        val html = doc.html()

        val yearLabel = if (html.contains("学年：")) "学年：" else "学年:"
        val termLabel = if (html.contains("学期：")) "学期：" else "学期:"

        val yearOptions = htmlParser.parseSearchOptions(doc, yearLabel, termLabel)
        scoreTable.searchYearOptions = yearOptions.options.toMutableList()
        scoreTable.defaultSelectedYear = yearOptions.selectedIndex

        val termStart = html.indexOf(termLabel)
        if (termStart >= 0) {
            val termOptions = htmlParser.parseSearchOptions(doc, termLabel, "footbox")
            scoreTable.searchTermOptions = termOptions.options.toMutableList()
            scoreTable.defaultSelectedTerm = termOptions.selectedIndex
        }

        scoreTable.scores = parseScores(doc)
        return scoreTable
    }

    fun parseScores(doc: Document): List<Score> {
        val scores = mutableListOf<Score>()

        val table = doc.select("table#Datagrid1").first()
            ?: doc.select("table").firstOrNull { it.html().contains("补考备注") }

        if (table != null) {
            val rows = table.select("tr")
            for (row in rows) {
                val cells = row.select("td")
                if (cells.size >= 14) {
                    scores.add(Score(
                        year = htmlParser.cleanNbsp(cells[0].text()),
                        term = htmlParser.cleanNbsp(cells[1].text()),
                        courseCode = htmlParser.cleanNbsp(cells[2].text()),
                        courseName = htmlParser.cleanNbsp(cells[3].text()),
                        courseType = htmlParser.cleanNbsp(cells[4].text()),
                        courseOwner = htmlParser.cleanNbsp(cells[5].text()),
                        studyScore = cells[6].text().toFloatOrNull() ?: 0f,
                        score = cells[7].text().toFloatOrNull() ?: 0f,
                        makeupScore = htmlParser.cleanNbspFloat(cells[8].text()),
                        isRestudy = cells[9].text() != "是",
                        institute = htmlParser.cleanNbsp(cells[10].text()),
                        scorePoint = htmlParser.cleanNbspFloat(cells[11].text()),
                        comment = htmlParser.cleanNbsp(cells[12].text()),
                        makeupComment = htmlParser.cleanNbsp(cells[13].text())
                    ))
                }
            }
        }

        if (scores.isEmpty()) {
            parseScoresFromRegex(doc.html(), scores)
        }

        return scores
    }

    fun parseScores(html: String): List<Score> {
        val scores = mutableListOf<Score>()
        parseScoresFromRegex(html, scores)
        return scores
    }

    private fun parseScoresFromRegex(html: String, scores: MutableList<Score>) {
        val tableBegin = html.indexOf("补考备注")
        val tableEnd = html.indexOf("footbox")
        if (tableBegin < 0 || tableEnd < 0) return

        val tableContent = html.substring(tableBegin, tableEnd)
        val pattern = Regex(
            "<td>(.*?)</td><td>(.*?)</td><td>(.*?)</td>" +
            "<td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td>" +
            "<td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td>"
        )

        pattern.findAll(tableContent).forEach { match ->
            scores.add(Score(
                year = match.groupValues[1],
                term = match.groupValues[2],
                courseCode = match.groupValues[3],
                courseName = match.groupValues[4],
                courseType = match.groupValues[5],
                courseOwner = htmlParser.cleanNbsp(match.groupValues[6]),
                studyScore = match.groupValues[7].toFloatOrNull() ?: 0f,
                score = match.groupValues[8].toFloatOrNull() ?: 0f,
                makeupScore = htmlParser.cleanNbspFloat(match.groupValues[9]),
                isRestudy = match.groupValues[10] != "&nbsp;",
                institute = match.groupValues[11],
                scorePoint = htmlParser.cleanNbspFloat(match.groupValues[12]),
                comment = htmlParser.cleanNbsp(match.groupValues[13]),
                makeupComment = htmlParser.cleanNbsp(match.groupValues[14])
            ))
        }
    }

    fun parseElectiveTargetScore(doc: Document): Map<String, Float> {
        val result = mutableMapOf<String, Float>()
        val pattern = Regex("<td>(.*?)</td><td>(.*?)</td>")
        pattern.findAll(doc.html()).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            result[key] = if (value == "&nbsp;") 0f else value.toFloatOrNull() ?: 0f
        }
        return result
    }
}

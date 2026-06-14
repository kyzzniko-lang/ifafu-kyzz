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

        val table = doc.select("table#Datagrid1").firstOrNull()
            ?: doc.select("table").firstOrNull { it.html().contains("补考备注") }

        if (table != null) {
            val rows = table.select("tr")
            for (row in rows) {
                if (row.select("th").isNotEmpty()) continue
                val cells = row.select("td")
                if (cells.size >= 14) {
                    val scoreText = cells[7].text().trim()
                    scores.add(Score(
                        year = htmlParser.cleanNbsp(cells[0].text()),
                        term = htmlParser.cleanNbsp(cells[1].text()),
                        courseCode = htmlParser.cleanNbsp(cells[2].text()),
                        courseName = htmlParser.cleanNbsp(cells[3].text()),
                        courseType = htmlParser.cleanNbsp(cells[4].text()),
                        courseOwner = htmlParser.cleanNbsp(cells[5].text()),
                        studyScore = cells[6].text().toFloatOrNull() ?: 0f,
                        score = scoreText.toFloatOrNull() ?: textGradeToScore(scoreText),
                        makeupScore = htmlParser.cleanNbspFloat(cells[8].text()),
                        isRestudy = cells[9].text() == "是",
                        isDelayExam = cells[12].text().contains("缓考") || cells[13].text().contains("缓考"),
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
            val scoreText = match.groupValues[8].trim()
            scores.add(Score(
                year = match.groupValues[1],
                term = match.groupValues[2],
                courseCode = match.groupValues[3],
                courseName = match.groupValues[4],
                courseType = match.groupValues[5],
                courseOwner = htmlParser.cleanNbsp(match.groupValues[6]),
                studyScore = match.groupValues[7].toFloatOrNull() ?: 0f,
                score = scoreText.toFloatOrNull() ?: textGradeToScore(scoreText),
                makeupScore = htmlParser.cleanNbspFloat(match.groupValues[9]),
                isRestudy = match.groupValues[10] == "是",
                isDelayExam = match.groupValues[13].contains("缓考") || match.groupValues[14].contains("缓考"),
                institute = match.groupValues[11],
                scorePoint = htmlParser.cleanNbspFloat(match.groupValues[12]),
                comment = htmlParser.cleanNbsp(match.groupValues[13]),
                makeupComment = htmlParser.cleanNbsp(match.groupValues[14])
            ))
        }
    }

    private fun textGradeToScore(grade: String): Float {
        return when (grade) {
            "优秀", "优" -> 95f
            "良好", "良" -> 85f
            "中等", "中" -> 75f
            "及格", "合格" -> 65f
            "不及格", "差", "不合格" -> 50f
            "免修", "免考" -> -1f
            else -> {
                android.util.Log.w("ScoreParser", "Unknown grade: '$grade', defaulting to 0")
                0f
            }
        }
    }

    fun parseElectiveTargetScore(doc: Document): Map<String, Float> {
        val result = mutableMapOf<String, Float>()
        val html = doc.html()
        val tableStart = html.indexOf("选修课").let { idx ->
            if (idx >= 0) idx else html.indexOf("学分要求")
        }
        if (tableStart < 0) {
            val tables = doc.select("table")
            for (table in tables) {
                val tHtml = table.html()
                if (tHtml.contains("学分") && tHtml.length < 2000) {
                    val pattern = Regex("<td>(.*?)</td><td>(.*?)</td>")
                    pattern.findAll(tHtml).forEach { match ->
                        val key = match.groupValues[1]
                        val value = match.groupValues[2]
                        result[key] = if (value == "&nbsp;") 0f else value.toFloatOrNull() ?: 0f
                    }
                    if (result.isNotEmpty()) return result
                }
            }
            return result
        }
        val tableEnd = html.indexOf("</table>", tableStart).let { if (it < 0) html.length else it + 8 }
        val tableContent = html.substring(tableStart, tableEnd)
        val pattern = Regex("<td>(.*?)</td><td>(.*?)</td>")
        pattern.findAll(tableContent).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            result[key] = if (value == "&nbsp;") 0f else value.toFloatOrNull() ?: 0f
        }
        return result
    }
}

package com.ifafu.kyzz.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class ScoreParserTest {
    private val parser = ScoreParser(HtmlParser())

    @Test
    fun regexFallbackMapsScorePointAndBothCommentColumns() {
        val html = """
            补考备注
            <td>2025-2026</td><td>1</td><td>C001</td><td>测试课程</td>
            <td>必修</td><td>专业课</td><td>2.0</td><td>88</td>
            <td>90</td><td>否</td><td>计算机学院</td><td>3.7</td>
            <td>正常备注</td><td>补考缓考备注</td>
            footbox
        """.trimIndent().replace("\n", "")

        val score = parser.parseScores(html).single()

        assertEquals(3.7f, score.scorePoint)
        assertEquals("正常备注", score.comment)
        assertEquals("补考缓考备注", score.makeupComment)
        assertTrue(score.isDelayExam)
    }

    @Test
    fun postResponseWithAttributesAndNoFooterUsesSameParserAsGet() {
        val html = """
            <table id="DataGrid1">
              <tr><td>学年</td><td>学期</td><td>课程代码</td><td>课程名称</td>
                <td>课程性质</td><td>课程归属</td><td>学分</td><td>成绩</td>
                <td>补考成绩</td><td>重修</td><td>学院</td><td>绩点</td><td>备注</td><td>补考备注</td></tr>
              <tr><td align="center">2025-2026</td><td>1</td><td>C001</td><td><span>测试课程</span></td>
                <td>必修</td><td>专业课</td><td>&nbsp;2.0&nbsp;</td><td>88</td>
                <td>合格</td><td>否</td><td>计算机学院</td><td>3.7</td><td></td><td>缓考</td></tr>
            </table>
        """.trimIndent()
        val score = parser.parseScores(html).single()
        assertEquals(parser.parseScores(Jsoup.parse(html)), parser.parseScores(html))
        assertEquals("测试课程", score.courseName)
        assertEquals(2f, score.studyScore)
        assertEquals(65f, score.makeupScore)
        assertTrue(score.isDelayExam)
    }

    @Test
    fun tableWithOnlyTdHeadersHasNoScores() {
        val cells = listOf("学年", "学期", "课程代码", "课程名称", "课程性质", "课程归属", "学分",
            "成绩", "补考成绩", "重修", "学院", "绩点", "备注", "补考备注")
        val html = "<table id='DataGrid1'><tr>" + cells.joinToString("") { "<td>$it</td>" } + "</tr></table>"
        assertTrue(parser.parseScores(html).isEmpty())
    }
}

package com.ifafu.kyzz.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

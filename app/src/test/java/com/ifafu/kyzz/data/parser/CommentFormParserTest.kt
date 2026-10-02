package com.ifafu.kyzz.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFormParserTest {
    @Test
    fun usesSubmittedNamesAndPreservesBothTableKeys() {
        val html = """
            <select id="DataGrid1__ctl2_JS1" name="DataGrid1:_ctl2:JS1"></select>
            <select name="dgPjc__ctl2_jc1"></select>
            <script>var field = 'DataGrid3:_ctl7:JS1';</script>
        """.trimIndent()
        val fields = CommentFormParser.ratingFields(html)
        assertEquals(listOf("DataGrid1:_ctl2:JS1", "dgPjc__ctl2_jc1"), fields.map { it.name })
        assertEquals(listOf("DataGrid1:2", "dgPjc:2"), fields.map { it.key })
    }

    @Test
    fun selectedCourseIsReadOnlyFromCourseSelector() {
        val html = """
            <select name="year"><option selected value="2026">2026</option></select>
            <select name="pjkc"><option value="A">A</option><option selected value="B&amp;C">B</option></select>
        """.trimIndent()
        assertEquals("B&C", CommentFormParser.selectedCourse(html))
        assertEquals("A", CommentFormParser.selectedCourse("<select name='pjkc'><option value='A'>A</option></select>"))
    }

    @Test
    fun forbiddenAlertAndExpiredSessionNeverReportSuccess() {
        val html = "<select name='DataGrid1:_ctl2:JS1'></select>"
        assertFalse(CommentFormParser.isSaveSuccessful(html, "禁止使用此功能", false))
        assertFalse(CommentFormParser.isSaveSuccessful(html, null, true))
        assertTrue(CommentFormParser.isSaveSuccessful(html, "保存成功", false))
        assertTrue(CommentFormParser.isSaveSuccessful(html, null, false))
        assertFalse(CommentFormParser.isSaveSuccessful("<title>现代教学管理信息系统</title>", null, false))
    }

    @Test
    fun incompleteEvaluationDoesNotCountAsFinalSuccess() {
        assertFalse(CommentFormParser.isFinalSuccessful("尚未完成评价，请先评价全部课程"))
        assertFalse(CommentFormParser.isFinalSuccessful("完成评价失败，请重试"))
        assertTrue(CommentFormParser.isFinalSuccessful("你已完成评价"))
    }
}

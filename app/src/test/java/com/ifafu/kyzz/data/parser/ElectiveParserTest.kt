package com.ifafu.kyzz.data.parser

import com.ifafu.kyzz.data.model.ElectiveCourseList
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElectiveParserTest {
    private val html = """
        <table><tr><td>选课课号</td><td>课程名称</td><td>学分</td><td>余量</td><td>选定</td></tr>
          <tr><td>A001</td><td>可选课</td><td>2</td><td>3</td><td><input type="checkbox" name="grid:_ctl2:choose"></td></tr></table>
        <h3>已选课程</h3>
        <table><tr><td>选课课号</td><td>课程名称</td><td>学分</td><td>教师姓名</td></tr>
          <tr><td>B001</td><td>已选课</td><td>3</td><td>张老师</td></tr></table>
    """.trimIndent()

    @Test
    fun courseNumberHeaderDoesNotTurnSelectedRowsIntoAvailableCourses() {
        val list = ElectiveCourseList()
        ElectiveParser(HtmlParser()).parseCourseList(Jsoup.parse(html), list)
        assertEquals("可选课", list.courses.single().name)
        assertEquals("grid:_ctl2:choose", list.courses.single().courseIndex)
        assertEquals("已选课", list.electived.single().name)
        assertTrue(list.electived.single().courseIndex.isEmpty())
    }

    @Test
    fun simpleElectiveReadsCheckboxOutsideTheFirstColumn() {
        val (available, selected) = SimpleElectiveParser().parseCourses(html)
        assertEquals("可选课", available.single().name)
        assertEquals("grid:_ctl2:choose", available.single().courseIndex)
        assertEquals("已选课", selected.single().name)
        assertTrue(selected.single().selected)
    }

    @Test
    fun missingCheckboxDoesNotInventASubmitField() {
        val list = ElectiveCourseList(curPage = 5, pageSize = 8)
        ElectiveParser(HtmlParser()).parseCourseList(Jsoup.parse(html.replace("<input type=\"checkbox\" name=\"grid:_ctl2:choose\">", "")), list)
        assertTrue(list.courses.single().courseIndex.isEmpty())
        assertEquals(1, list.curPage)
        assertEquals(1, list.pageSize)
    }
}

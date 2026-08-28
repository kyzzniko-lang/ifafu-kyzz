package com.ifafu.kyzz.data.parser

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表时间串解析回归测试。
 *
 * 背景：2.6.0 曾把 parseCourseTime 正则的周次组号写错（[5] 是节次内层组，
 * 周次大括号内容实际是第 6 组），导致：
 * - "周一第1,2节{第1-16周}" 被解析成"只在第 2 周上课"；
 * - 无逗号节次的课拿到空串、整门被丢弃。
 * 这里用真实形态的时间串锁住解析结果，防止再次回归。
 */
class SyllabusParserTest {

    private val parser = SyllabusParser(HtmlParser())

    /** 构造一个单行课表：节次标签列 + 一格课程。 */
    private fun parseCell(cellHtml: String): List<com.ifafu.kyzz.data.model.Course> {
        val html = """
            <html><body>
            <table id="kbtable">
              <tr>
                <td>第1节</td>
                <td align="Center">$cellHtml</td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()
        val syllabus = parser.parseSyllabus(Jsoup.parse(html), "123")
        return syllabus.courses
    }

    @Test
    fun multiSectionContiguousWeeks() {
        val courses = parseCell("高等数学<br>周一第1,2节{第1-16周}<br>张三<br>教1-101")
        assertEquals(1, courses.size)
        val c = courses[0]
        assertEquals("高等数学", c.name)
        assertEquals(1, c.weekDay)
        assertEquals(1, c.begin)
        assertEquals(2, c.end)
        assertEquals(1, c.weekBegin)
        assertEquals(16, c.weekEnd)
        assertEquals(0, c.oddOrTwice)
        // 周次集合必须覆盖 1..16（回归：曾被错解析成只剩第 2 周）
        assertEquals((1..16).toList(), c.weeks)
        assertTrue(c.coversWeek(1) && c.coversWeek(16))
    }

    @Test
    fun irregularCommaWeeks() {
        // "第1-8,10,12-16周"：逗号分隔的非连续周，旧正则整门课被静默丢弃
        val courses = parseCell("数据结构<br>周二第3,4节{第1-8,10,12-16周}<br>李四<br>教2-201")
        assertEquals(1, courses.size)
        val c = courses[0]
        assertEquals(2, c.weekDay)
        assertEquals(3, c.begin)
        assertEquals(4, c.end)
        assertEquals((1..8).toList() + 10 + (12..16).toList(), c.weeks)
        assertEquals(1, c.weekBegin)
        assertEquals(16, c.weekEnd)
        assertTrue(c.coversWeek(10))
        assertTrue(!c.coversWeek(9))
        assertTrue(!c.coversWeek(11))
    }

    @Test
    fun irregularSegmentedWeeks() {
        // "第1-8周,第10周,第12-16周"：每段带"周"的分段格式，旧正则只取首段
        val courses = parseCell("操作系统<br>周三第5,6节{第1-8周,第10周,第12-16周}<br>王五<br>教3-301")
        assertEquals(1, courses.size)
        val c = courses[0]
        assertEquals((1..8).toList() + 10 + (12..16).toList(), c.weeks)
        assertEquals(5, c.begin)
        assertEquals(6, c.end)
    }

    @Test
    fun oddWeekSuffix() {
        val courses = parseCell("大学英语<br>周四第1,2节{第1-16周|单周}<br>赵六<br>教4-401")
        assertEquals(1, courses.size)
        assertEquals(1, courses[0].oddOrTwice)
        assertEquals((1..16).toList(), courses[0].weeks)
    }

    @Test
    fun evenWeekSuffix() {
        val courses = parseCell("体育<br>周五第1,2节{第2-16周双}<br>钱七<br>操场")
        assertEquals(1, courses.size)
        assertEquals(2, courses[0].oddOrTwice)
        assertEquals((2..16).toList(), courses[0].weeks)
    }

    @Test
    fun teacherAndAddressAssigned() {
        val courses = parseCell("高等数学<br>周一第1,2节{第1-16周}<br>张三<br>教1-101")
        val c = courses[0]
        assertEquals("张三", c.teacher)
        assertEquals("教1-101", c.address)
    }

    /** 构造自定义课表 HTML 并解析。 */
    private fun parseTable(tableHtml: String): List<com.ifafu.kyzz.data.model.Course> {
        val html = """
            <html><body>
            <table id="kbtable">$tableHtml</table>
            </body></html>
        """.trimIndent()
        return parser.parseSyllabus(Jsoup.parse(html), "123").courses
    }

    /**
     * 回归：无课时段的空占位格(<td align="Center"></td>)是列计数的位置锚点。
     * 曾被误当版式噪声过滤,导致其后所有课程按格位左移串天;课程时间文本又
     * 缺少"周X"(走格子位置回退)时,整门课被映射到错误的星期。
     */
    @Test
    fun emptyPlaceholderCellPreservesColumnIndex() {
        // 行内依次为:空占位格(周一)、真实课程格(应落在周二=colIndex 1)
        val courses = parseTable(
            """
            <tr>
              <td>第1节</td>
              <td align="Center"></td>
              <td align="Center">高等数学<br>@周第1,2节&#123;第1-16周&#125;<br>张三<br>教1-101</td>
              <td align="Center">&nbsp;</td>
            </tr>
            """.trimIndent()
        )
        assertEquals(1, courses.size)
        // "@周"中的字符不可识别为星期名,必须回退到格子位置 => 周二
        assertEquals(2, courses[0].weekDay)
    }

    /**
     * 回归：同一时段多门课堆叠的长文本单元格曾被 ">80 字符" 上限整格丢弃,
     * 三门课同时消失(少课)。任何长度都必须照常参与占位与解析。
     */
    @Test
    fun stackedMultiCourseLongCellFullyParsed() {
        val b1 = "大学英语III（含听说）<br>周一第1,2节{第1-16周}<br>王小明、李小红<br>文科楼B-301"
        val b2 = "专业英语<br>周二第1,2节{第1-16周}<br>陈英美<br>文科楼B-302"
        val b3 = "线性代数<br>周三第1,2节{第1-16周}<br>赵数分、钱极限、孙概率、李统计<br>理学院A-105"
        assertTrue("用例本身需超长(>${b1.length + b2.length + b3.length}字)",
            (b1 + b2 + b3).length > 80)
        val courses = parseTable(
            """
            <tr>
              <td>第1节</td>
              <td align="Center">$b1<br><br>$b2<br><br>$b3</td>
            </tr>
            """.trimIndent()
        )
        assertEquals(3, courses.size)
        assertEquals(listOf("大学英语III（含听说）", "专业英语", "线性代数"),
            courses.map { it.name })
        assertEquals(listOf(1, 2, 3), courses.map { it.weekDay })
    }
}

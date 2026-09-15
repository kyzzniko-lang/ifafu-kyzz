package com.ifafu.kyzz.data.api

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentInfoApiTest {
    @Test
    fun resolvesStudentInfoLinkFromSchoolMenu() {
        val menuUrl = "http://jwgl.example/(token)/xsleft.aspx?xh=123"
        val fallback = "http://jwgl.example/(token)/xsgrxx.aspx?fallback=true"
        val html = """<a href="xsgrxx.aspx?xh=123&amp;gnmkdm=N121599">个人信息</a>"""

        assertEquals(
            "http://jwgl.example/(token)/xsgrxx.aspx?xh=123&gnmkdm=N121599",
            StudentInfoApi.resolveStudentInfoUrl(html, menuUrl, fallback)
        )
    }

    @Test
    fun recognizesStudentInfoPageEvenWhenItContainsAnAlertScript() {
        val doc = Jsoup.parse(
            """<span id="lbxsgrxx_xm">姓名：</span><td>张三</td>
                <script>alert('对不起,禁止使用此功能')</script>"""
        )

        assertTrue(StudentInfoApi.isStudentInfoPage(doc))
    }
}

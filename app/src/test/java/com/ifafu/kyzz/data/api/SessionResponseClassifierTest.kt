package com.ifafu.kyzz.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResponseClassifierTest {
    @Test
    fun loginFormIsExpiredSession() {
        val html = """<input id="txtUserName"><input id="TextBox2">"""
        assertTrue(SessionResponseClassifier.isSessionExpired(html))
    }

    @Test
    fun singleQuotedLoginControlsAreExpiredSession() {
        val html = """<input id='txtUserName'><input id='TextBox2'>"""
        assertTrue(SessionResponseClassifier.isSessionExpired(html))
    }

    @Test
    fun loginRedirectIsExpiredSession() {
        assertTrue(SessionResponseClassifier.isSessionExpired("window.location='default2.aspx'"))
    }

    @Test
    fun unquotedRedirectIsExpiredSession() {
        // 部分正方皮肤不带引号直接赋值
        assertTrue(SessionResponseClassifier.isSessionExpired("location=default2.aspx"))
        assertTrue(SessionResponseClassifier.isSessionExpired("location.href='top:default.aspx'"))
    }

    @Test
    fun replaceRedirectIsExpiredSession() {
        assertTrue(
            SessionResponseClassifier.isSessionExpired("window.location.replace('default2.aspx');")
        )
    }

    @Test
    fun loginTitledPageIsExpiredSession() {
        val html = "<html><head><title>用户登录 - 教务系统</title></head><body></body></html>"
        assertTrue(SessionResponseClassifier.isSessionExpired(html))
    }

    @Test
    fun passwordInputPageIsExpiredSession() {
        val html = """<form><input name="pw" type="password"></form>"""
        assertTrue(SessionResponseClassifier.isSessionExpired(html))
    }

    @Test
    fun plainPasswordWordWithoutInputDoesNotTrigger() {
        // 文本里出现"password"字样但没有密码输入框,不能误判(如脚本文案)
        val html = "<p>请勿在 password 字段使用中文</p>"
        assertFalse(SessionResponseClassifier.isSessionExpired(html))
    }

    @Test
    fun scoreBusinessPageIsNotExpired() {
        // 真实成绩数据页特征:DataGrid 表格+学号姓名栏,不得被新增启发式误判
        val html = """
            <html><head><title>学生成绩查询</title></head><body>
            <table id="DataGrid1"><tr id="gridlines"><td id="xhxm">学号:20220001 姓名:张三</td></tr></table>
            </body></html>
        """.trimIndent()
        assertFalse(SessionResponseClassifier.isSessionExpired(html))
        assertTrue(SessionResponseClassifier.looksLikeBusinessPage(html))
    }

    @Test
    fun syllabusBusinessPageLooksLikeBusiness() {
        val html = """<table id="kbtable"><tr><td>高等数学</td></tr></table>"""
        assertTrue(SessionResponseClassifier.looksLikeBusinessPage(html))
        assertFalse(SessionResponseClassifier.isSessionExpired(html))
    }

    @Test
    fun busyPageIsTransientButNotExpired() {
        val html = "<title>ERROR - 出错啦！</title>系统正忙"
        assertTrue(SessionResponseClassifier.isTransientServerError(html))
        assertFalse(SessionResponseClassifier.isSessionExpired(html))
    }

    @Test
    fun tokenAloneDoesNotProveLoginSuccess() {
        assertFalse(
            SessionResponseClassifier.isAuthenticatedLoginResponse(
                "http://jwgl.example/(token)/default2.aspx",
                "<html>unexpected response</html>"
            )
        )
    }

    @Test
    fun mainPageRedirectProvesLoginSuccess() {
        assertTrue(
            SessionResponseClassifier.isAuthenticatedLoginResponse(
                "http://jwgl.example/(token)/xs_main.aspx?xh=1",
                ""
            )
        )
    }
}

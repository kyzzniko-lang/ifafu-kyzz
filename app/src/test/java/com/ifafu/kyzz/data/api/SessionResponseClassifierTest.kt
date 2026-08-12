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
    fun loginRedirectIsExpiredSession() {
        assertTrue(SessionResponseClassifier.isSessionExpired("window.location='default2.aspx'"))
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

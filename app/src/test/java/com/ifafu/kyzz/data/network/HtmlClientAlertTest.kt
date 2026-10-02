package com.ifafu.kyzz.data.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlClientAlertTest {
    private val client = HtmlClient(OkHttpClient())

    @Test
    fun longFunctionAlertsAreNotTreatedAsPageErrors() {
        val longFunctionBody = "var untouched = 1;".repeat(1_100)
        val html = """
            <html><head><script>
            function validate() {
                alert('请输入内容');
                $longFunctionBody
                alert('对不起,禁止使用此功能');
            }
            </script></head><body><span id="lbxsgrxx_xm">姓名：</span></body></html>
        """.trimIndent()

        assertNull(client.checkAlert(html))
    }

    @Test
    fun topLevelAlertIsStillTreatedAsPageError() {
        val html = """<html><script>alert('对不起,禁止使用此功能')</script></html>"""

        assertEquals("对不起,禁止使用此功能", client.checkAlert(html)?.message)
    }

    @Test
    fun bracesInValidationStringsDoNotMakeFunctionAlertTopLevel() {
        assertNull(client.checkAlert("""<script>function validate() { var value = "}"; alert('校验提示'); }</script>"""))
    }

    @Test
    fun nestedFunctionDoesNotHideOuterValidationScope() {
        assertNull(client.checkAlert("""<script>function outer() { function inner() {} alert('校验提示'); }</script>"""))
    }

    @Test
    fun alertTextInStringAndCommentIsIgnored() {
        val html = """<script>var example = "alert('示例文本')"; /* alert('注释文本') */ alert('实际提示');</script>"""
        assertEquals("实际提示", client.checkAlert(html)?.message)
    }

    @Test
    fun bracesInRegularExpressionDoNotEndFunctionScope() {
        assertNull(client.checkAlert("""<script>function validate() { var regex = /}/; alert('校验提示'); }</script>"""))
    }

    @Test
    fun arrowValidationFunctionDoesNotHideLaterPageAlert() {
        val html = """<script>var validate = () => { alert('校验提示'); }; alert('实际提示');</script>"""
        assertEquals("实际提示", client.checkAlert(html)?.message)
    }
}

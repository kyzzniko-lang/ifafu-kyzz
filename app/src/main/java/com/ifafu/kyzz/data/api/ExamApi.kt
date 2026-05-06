package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.ExamTable
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.ExamParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExamApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val examParser: ExamParser
) {

    suspend fun getExamTable(host: String, token: String, number: String, name: String): ExamTable? {
        val accessUrl = "${host}/(${token})/xskscx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121604"
        val doc = htmlClient.get(accessUrl)
        val html = doc.html()

        if (html.contains("账号或密码") || html.contains("请登录") || html.contains("default.aspx") || html.contains("default2.aspx")) {
            return null
        }

        return examParser.parseExamTable(doc)
    }
}

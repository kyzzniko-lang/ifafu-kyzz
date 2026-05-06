package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.SyllabusParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyllabusApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val syllabusParser: SyllabusParser
) {

    suspend fun getSyllabus(host: String, token: String, number: String, name: String): Syllabus? {
        val accessUrl = "${host}/(${token})/xskbcx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121603"
        val doc = htmlClient.get(accessUrl)
        val html = doc.html()
        if (html.contains("账号或密码") || html.contains("请登录") || html.contains("default.aspx") || html.contains("default2.aspx")) {
            return null
        }
        return syllabusParser.parseSyllabus(doc, number)
    }
}

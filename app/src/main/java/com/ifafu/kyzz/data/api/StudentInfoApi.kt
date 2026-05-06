package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.StudentInfo
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.StudentInfoParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentInfoApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val studentInfoParser: StudentInfoParser
) {

    suspend fun getStudentInfo(host: String, token: String, number: String, name: String): StudentInfo? {
        val accessUrl = "${host}/(${token})/xsgrxx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121501"
        val doc = htmlClient.get(accessUrl)
        val html = doc.html()

        if (html.contains("账号或密码") || html.contains("请登录") || html.contains("default.aspx") || html.contains("default2.aspx")) {
            return null
        }

        return studentInfoParser.parseStudentInfo(doc, number)
    }
}

package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.GradeExam
import com.ifafu.kyzz.data.model.TrainingPlan
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.TrainingPlanParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrainingPlanApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val parser: TrainingPlanParser
) {

    suspend fun getTrainingPlan(host: String, token: String, number: String, name: String): TrainingPlan? {
        val url = "${host}/(${token})/pyjh.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121607"
        val doc = htmlClient.get(url)
        val html = doc.html()
        if (html.contains("账号或密码") || html.contains("请登录") || html.contains("default.aspx")) return null
        return parser.parse(doc)
    }

    suspend fun getGradeExams(host: String, token: String, number: String, name: String): List<GradeExam> {
        val url = "${host}/(${token})/xsdjkscx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121606"
        val doc = htmlClient.get(url)
        val html = doc.html()
        if (html.contains("账号或密码") || html.contains("请登录") || html.contains("default.aspx")) return emptyList()
        return parser.parseGradeExams(doc)
    }
}

package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.model.ScoreTable
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.ScoreParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoreApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val scoreParser: ScoreParser
) {

    suspend fun getScoreTable(host: String, token: String, number: String, name: String): ScoreTable? {
        val accessUrl = "${host}/(${token})/xskscx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121604"
        val doc = htmlClient.get(accessUrl)
        val html = doc.html()

        if (html.contains("账号或密码") || html.contains("请登录") || html.contains("default.aspx") || html.contains("default2.aspx")) {
            return null
        }

        if (html.contains("教学质量评价")) {
            return null
        }

        return scoreParser.parseScoreTable(doc)
    }

    suspend fun updateScoreTable(
        host: String, token: String, number: String, name: String,
        year: String, term: String
    ): List<Score> {
        val accessUrl = "${host}/(${token})/xskscx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121604"

        val formBody = htmlClient.buildViewStateFormBody()
            .add("__EVENTTARGET", "")
            .add("__EVENTARGUMENT", "")
            .add("ddlxn", year)
            .add("ddlxq", term)
            .add("btnCx", "查  询")
            .build()

        val html = htmlClient.postString(accessUrl, formBody)
        return scoreParser.parseScores(html)
    }

    suspend fun getElectiveTargetScore(host: String, token: String, number: String, name: String): Map<String, Float> {
        val accessUrl = "${host}/(${token})/pyjh.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121607"
        val doc = htmlClient.get(accessUrl)
        return scoreParser.parseElectiveTargetScore(doc)
    }
}

package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.SimpleCourse
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.SimpleElectiveParser
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimpleElectiveApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val simpleElectiveParser: SimpleElectiveParser,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository
) {
    companion object {
        private const val TAG = "SimpleElectiveApi"
        const val TYPE_PROFESSIONAL = "professional"
        const val TYPE_SPORTS = "sports"
        const val TYPE_RETAKE = "retake"
        const val TYPE_MINOR = "minor"

        private val URL_MAP = mapOf(
            TYPE_PROFESSIONAL to Pair("xf_xszyyxkc.aspx", "N121201"),
            TYPE_SPORTS to Pair("xf_xstyxk.aspx", "N121205"),
            TYPE_RETAKE to Pair("xscxbm.aspx", "N121206"),
            TYPE_MINOR to Pair("xsxk_fxxk.aspx", "N121208")
        )
    }

    suspend fun getCourses(
        type: String, host: String, token: String, number: String, name: String
    ): Result {
        val (page, gnmkdm) = URL_MAP[type] ?: return Result(false, "未知选课类型")
        return try {
            getCoursesInternal(page, gnmkdm, host, token, number, name)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to get courses for type=$type", e)
            Result(false, "网络异常")
        }
    }

    private suspend fun getCoursesInternal(
        page: String, gnmkdm: String,
        host: String, token: String, number: String, name: String,
        depth: Int = 0
    ): Result {
        val url = "${host}/(${token})/${page}?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=$gnmkdm"
        val html = htmlClient.getString(url)
        if (html.isBlank()) return Result(false, "网络异常")

        if (userApi.isSessionExpired(html)) {
            if (depth >= 2) return Result(false, "会话已过期，请重新登录")
            val reloginResp = reloginHelper.relogin()
            if (!reloginResp.success) return Result(false, reloginResp.message)
            val user = userRepository.getUser()
            return getCoursesInternal(page, gnmkdm, host, user.token, user.account, user.name, depth + 1)
        }

        if (simpleElectiveParser.isNotOpen(html)) {
            Log.d(TAG, "isNotOpen=true for page=$page, htmlLen=${html.length}")
            return Result(true, "暂未开放选课", notOpen = true)
        }

        val (available, selected) = simpleElectiveParser.parseCourses(html)
        Log.d(TAG, "page=$page: ${available.size} available, ${selected.size} selected, htmlLen=${html.length}")

        htmlClient.extractViewState(html)
        return Result(true, "获取成功", available = available, selected = selected)
    }

    data class Result(
        val success: Boolean,
        val message: String,
        val available: List<SimpleCourse> = emptyList(),
        val selected: List<SimpleCourse> = emptyList(),
        val notOpen: Boolean = false
    )
}

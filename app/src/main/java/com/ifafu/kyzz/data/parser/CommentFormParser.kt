package com.ifafu.kyzz.data.parser

import org.jsoup.Jsoup

/** 只读取实际可提交控件的 name，避免把 DOM id 或脚本中的字符串当字段。 */
internal object CommentFormParser {
    data class RatingField(val name: String, val table: String, val index: Int) {
        val key: String get() = "$table:$index"
    }

    private val ratingName = Regex("^(.+?)[_:$]+ctl(\\d+)[_:$]+(?:JS1|jc1)$")

    fun ratingFields(html: String): List<RatingField> =
        Jsoup.parse(html).select("select[name], input[name]").mapNotNull { control ->
            val name = control.attr("name")
            val match = ratingName.matchEntire(name) ?: return@mapNotNull null
            val index = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            RatingField(name, match.groupValues[1], index)
        }.distinctBy { it.name }

    fun selectedCourse(html: String): String? {
        val select = Jsoup.parse(html).select("select[name=pjkc], select#pjkc").firstOrNull()
            ?: return null
        val options = select.select("option")
        return (options.firstOrNull { it.hasAttr("selected") } ?: options.firstOrNull())?.attr("value")
    }

    fun isSaveSuccessful(html: String, alertMessage: String?, sessionExpired: Boolean): Boolean {
        if (sessionExpired || html.isBlank()) return false
        val successWords = listOf("保存成功", "提交成功", "评价成功")
        if (alertMessage != null) return successWords.any { alertMessage.contains(it) } &&
            !alertMessage.contains("未成功") && !alertMessage.contains("失败")
        // 正方部分版本保存成功返回同一评价表单，不发送成功提示。
        return successWords.any { Jsoup.parse(html).text().contains(it) } ||
            ratingFields(html).isNotEmpty()
    }

    fun isFinalSuccessful(alertMessage: String?): Boolean =
        alertMessage != null && alertMessage.contains("完成评价") &&
            listOf("未", "尚未", "没有", "失败", "不能").none { alertMessage.contains(it) }
}

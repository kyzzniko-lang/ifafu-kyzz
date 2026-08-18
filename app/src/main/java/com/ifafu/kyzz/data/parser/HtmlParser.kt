package com.ifafu.kyzz.data.parser

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HtmlParser @Inject constructor() {

    fun cleanNbsp(str: String?): String =
        if (str.isNullOrBlank()) "" else str.replace("&nbsp;", " ").replace("\u00A0", " ").trim()

    fun cleanNbspFloat(str: String?): Float =
        if (str.isNullOrBlank()) 0f
        else str.replace("&nbsp;", "").replace("\u00A0", "").trim().toFloatOrNull() ?: 0f

    /**
     * 解析 <option> 标签列表为 (value, isSelected)。
     * 旧正则要求 selected 出现在 value 之前且 > 紧跟 value，遇到
     * <option value="x" selected> 或带其他属性时整条丢失，导致默认学年/学期错乱。
     * 这里先匹配整个标签再分别提取属性，兼容任意属性顺序。
     */
    private fun parseOptionTags(html: String): List<Pair<String, Boolean>> {
        val tagRegex = Regex("""<option\b([^>]*)>""")
        val valueRegex = Regex("""value\s*=\s*"([^"]*)"""")
        val selectedRegex = Regex("""\bselected\b""", RegexOption.IGNORE_CASE)
        val rawOptions = mutableListOf<Pair<String, Boolean>>()
        tagRegex.findAll(html).forEach { match ->
            val attrs = match.groupValues[1]
            val value = valueRegex.find(attrs)?.groupValues?.get(1) ?: return@forEach
            rawOptions.add(value to selectedRegex.containsMatchIn(attrs))
        }
        return rawOptions
    }

    private fun buildParsedOptions(rawOptions: List<Pair<String, Boolean>>): ParsedOptions {
        val options = rawOptions.map { it.first }.filter { it.isNotEmpty() }.toMutableList()
        var selectedIndex = 0
        var filteredIndex = 0
        for ((value, isSelected) in rawOptions) {
            if (value.isEmpty()) continue
            if (isSelected) {
                selectedIndex = filteredIndex
            }
            filteredIndex++
        }
        return ParsedOptions(options, selectedIndex)
    }

    fun parseSearchOptions(doc: Document, startTag: String, endTag: String): ParsedOptions {
        val html = doc.html()
        val startIdx = html.indexOf(startTag)
        if (startIdx < 0) return ParsedOptions()
        val endIdx = html.indexOf(endTag, startIdx + startTag.length)
        if (endIdx < 0 || startIdx >= endIdx) return ParsedOptions()

        return buildParsedOptions(parseOptionTags(html.substring(startIdx, endIdx)))
    }

    fun parseOptionsByTags(element: Element, startTag: String, endTag: String?): ParsedOptions {
        val html = element.html()
        val startIdx = html.indexOf(startTag)
        if (startIdx < 0) return ParsedOptions()
        val endIdx = if (endTag != null) html.indexOf(endTag, startIdx + startTag.length) else html.length
        if (endIdx < 0 || startIdx >= endIdx) return ParsedOptions()

        return buildParsedOptions(parseOptionTags(html.substring(startIdx, endIdx)))
    }

    data class ParsedOptions(
        val options: List<String> = emptyList(),
        val selectedIndex: Int = 0
    ) {
        fun excludeTerms(vararg terms: String): ParsedOptions {
            val selectedValue = options.getOrElse(selectedIndex) { "" }
            val filtered = options.filter { it !in terms }
            val newIndex = if (selectedValue in terms) {
                filtered.indexOfFirst { it.isNotEmpty() }.coerceAtLeast(0)
            } else {
                filtered.indexOf(selectedValue).coerceAtLeast(0)
            }
            return ParsedOptions(filtered, newIndex)
        }
    }
}

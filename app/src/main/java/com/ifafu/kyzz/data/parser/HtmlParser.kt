package com.ifafu.kyzz.data.parser

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HtmlParser @Inject constructor() {

    fun cleanNbsp(str: String?): String = if (str == "&nbsp;" || str.isNullOrBlank()) "" else str.trim()

    fun cleanNbspFloat(str: String?): Float = if (str == "&nbsp;" || str.isNullOrBlank()) 0f else str.trim().toFloatOrNull() ?: 0f

    fun parseSearchOptions(doc: Document, startTag: String, endTag: String): ParsedOptions {
        val html = doc.html()
        val startIdx = html.indexOf(startTag)
        val endIdx = html.indexOf(endTag)
        if (startIdx < 0 || endIdx < 0 || startIdx >= endIdx) return ParsedOptions()

        val options = mutableListOf<String>()
        var selectedIndex = 0

        val optionRegex = Regex("""<option( selected="selected")? value="([^"]*)">""")
        optionRegex.findAll(html.substring(startIdx, endIdx)).forEach { match ->
            options.add(match.groupValues[2])
            if (match.groupValues[1].isNotEmpty()) {
                selectedIndex = options.size - 1
            }
        }

        return ParsedOptions(options, selectedIndex)
    }

    fun parseOptionsByTags(element: Element, startTag: String, endTag: String?): ParsedOptions {
        val html = element.html()
        val startIdx = html.indexOf(startTag)
        if (startIdx < 0) return ParsedOptions()
        val endIdx = if (endTag != null) html.indexOf(endTag) else html.length
        if (endIdx < 0 || startIdx >= endIdx) return ParsedOptions()

        val options = mutableListOf<String>()
        var selectedIndex = 0

        val optionRegex = Regex("""<option( selected="selected")? value="([^"]*)">""")
        optionRegex.findAll(html.substring(startIdx, endIdx)).forEach { match ->
            options.add(match.groupValues[2])
            if (match.groupValues[1].isNotEmpty()) {
                selectedIndex = options.size - 1
            }
        }

        return ParsedOptions(options, selectedIndex)
    }

    data class ParsedOptions(
        val options: List<String> = emptyList(),
        val selectedIndex: Int = 0
    )
}

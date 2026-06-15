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
        if (startIdx < 0) return ParsedOptions()
        val endIdx = html.indexOf(endTag, startIdx + startTag.length)
        if (endIdx < 0 || startIdx >= endIdx) return ParsedOptions()

        val optionRegex = Regex("""<option(\s+[Ss][Ee][Ll][Ee][Cc][Tt][Ee][Dd](?:="[^"]*")?)?\s+value="([^"]*)">""")
        val rawOptions = mutableListOf<Pair<String, Boolean>>()
        optionRegex.findAll(html.substring(startIdx, endIdx)).forEach { match ->
            val value = match.groupValues[2]
            val isSelected = match.groupValues[1].isNotEmpty()
            rawOptions.add(value to isSelected)
        }

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

    fun parseOptionsByTags(element: Element, startTag: String, endTag: String?): ParsedOptions {
        val html = element.html()
        val startIdx = html.indexOf(startTag)
        if (startIdx < 0) return ParsedOptions()
        val endIdx = if (endTag != null) html.indexOf(endTag, startIdx + startTag.length) else html.length
        if (endIdx < 0 || startIdx >= endIdx) return ParsedOptions()

        val optionRegex = Regex("""<option(\s+[Ss][Ee][Ll][Ee][Cc][Tt][Ee][Dd](?:="[^"]*")?)?\s+value="([^"]*)">""")
        val rawOptions = mutableListOf<Pair<String, Boolean>>()
        optionRegex.findAll(html.substring(startIdx, endIdx)).forEach { match ->
            val value = match.groupValues[2]
            val isSelected = match.groupValues[1].isNotEmpty()
            rawOptions.add(value to isSelected)
        }

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

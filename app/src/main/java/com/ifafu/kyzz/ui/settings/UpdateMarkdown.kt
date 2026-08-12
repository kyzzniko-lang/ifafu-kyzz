package com.ifafu.kyzz.ui.settings

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import androidx.core.content.ContextCompat
import com.ifafu.kyzz.R

/** Lightweight GitHub-release Markdown renderer for update UI. */
object UpdateMarkdown {
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.+)$")
    private val ordered = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
    private val duplicateReleaseTitle = Regex(
        "^#{1,3}\\s+iFAFU\\s+v?\\d+(?:\\.\\d+){1,3}\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val inline = Regex(
        """\[([^]]+)]\((https?://[^)\s]+)\)|\*\*([^*]+)\*\*|`([^`]+)`|\*([^*]+)\*"""
    )

    fun render(context: Context, markdown: String?, footer: String? = null): CharSequence {
        val content = normalized(markdown)
        val out = SpannableStringBuilder()
        val accent = ContextCompat.getColor(context, R.color.claude_terracotta)
        val secondary = ContextCompat.getColor(context, R.color.claude_text_secondary)

        content.lineSequence().forEach { source ->
            val line = source.trimEnd()
            if (line.isBlank()) {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                return@forEach
            }

            heading.matchEntire(line)?.let { match ->
                val level = match.groupValues[1].length
                val start = out.length
                appendInline(out, match.groupValues[2])
                val end = out.length
                out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(
                    RelativeSizeSpan(if (level <= 2) 1.22f else 1.08f),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                out.append('\n')
                return@forEach
            }

            bullet.matchEntire(line)?.let { match ->
                val start = out.length
                appendInline(out, match.groupValues[1])
                val end = out.length
                out.append('\n')
                out.setSpan(BulletSpan(18, accent), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                return@forEach
            }

            ordered.matchEntire(line)?.let { match ->
                out.append(match.groupValues[1]).append(". ")
                appendInline(out, match.groupValues[2])
                out.append('\n')
                return@forEach
            }

            val quote = line.removePrefix("> ")
            val start = out.length
            appendInline(out, quote)
            val end = out.length
            if (quote != line) {
                out.setSpan(ForegroundColorSpan(secondary), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            out.append('\n')
        }

        while (out.isNotEmpty() && out.last().isWhitespace()) out.delete(out.length - 1, out.length)
        if (!footer.isNullOrBlank()) {
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length
            out.append(footer)
            out.setSpan(ForegroundColorSpan(secondary), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    fun summary(markdown: String?, maxLength: Int = 180): String {
        val lines = normalized(markdown).lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("^#{1,6}\\s+"), "")
                    .replace(Regex("^[-*+]\\s+"), "• ")
                    .replace(Regex("^\\d+[.)]\\s+"), "")
                    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                    .replace("**", "")
                    .replace("`", "")
            }
            .filter { it.isNotBlank() && it != "更新内容" }
            .toList()
        val text = lines.joinToString("  ")
        return if (text.length <= maxLength) text else text.take(maxLength).trimEnd() + "…"
    }

    private fun normalized(markdown: String?): String {
        val source = markdown?.trim().orEmpty().ifBlank { "修复已知问题并优化体验" }
        return source.lineSequence()
            .filterNot { duplicateReleaseTitle.matches(it.trim()) }
            .joinToString("\n")
            .trim()
    }

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var cursor = 0
        inline.findAll(text).forEach { match ->
            if (match.range.first > cursor) out.append(text.substring(cursor, match.range.first))
            val start = out.length
            when {
                match.groupValues[1].isNotEmpty() -> {
                    out.append(match.groupValues[1])
                    out.setSpan(
                        URLSpan(match.groupValues[2]),
                        start,
                        out.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                match.groupValues[3].isNotEmpty() -> {
                    out.append(match.groupValues[3])
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                match.groupValues[4].isNotEmpty() -> {
                    out.append(match.groupValues[4])
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    out.append(match.groupValues[5])
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) out.append(text.substring(cursor))
    }
}

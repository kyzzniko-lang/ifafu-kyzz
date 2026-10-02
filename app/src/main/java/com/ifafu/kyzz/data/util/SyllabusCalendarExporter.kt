package com.ifafu.kyzz.data.util

import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.model.scheduleIdentity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** RFC 5545 output; course times always refer to the campus time zone. */
object SyllabusCalendarExporter {
    private val campusZone = ZoneId.of("Asia/Shanghai")
    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val starts = listOf("08:00", "08:50", "09:55", "10:45", "11:35", "14:00",
        "14:50", "15:50", "16:40", "18:25", "19:15", "20:05")
    private val ends = listOf("08:45", "09:35", "10:40", "11:30", "12:20", "14:45",
        "15:35", "16:35", "17:25", "19:10", "20:00", "20:50")

    fun export(syllabus: Syllabus, termFirstDay: String, now: Instant = Instant.now()): String {
        val start = LocalDate.parse(termFirstDay.substringBefore(' '))
        require(start.dayOfWeek.value == 1) { "学期首日必须为周一" }
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//iFAFU//KYZZ//CN")
        var eventCount = 0
        for (course in syllabus.courses.distinctBy { it.scheduleIdentity() }) {
            if (course.weekDay !in 1..7 || course.begin !in 1..12 ||
                course.end !in course.begin..12 || course.weekBegin !in 1..30 ||
                course.weekEnd !in course.weekBegin..30) continue
            val weeks = course.activeWeeks().distinct().sorted().filter { week ->
                week in 1..30 && (course.oddOrTwice == 0 ||
                    course.oddOrTwice == 1 && week % 2 == 1 ||
                    course.oddOrTwice == 2 && week % 2 == 0)
            }
            for (week in weeks) {
                val date = start.plusWeeks((week - 1).toLong()).plusDays((course.weekDay - 1).toLong())
                val begin = date.atTime(LocalTime.parse(starts[course.begin - 1])).atZone(campusZone).toInstant()
                val end = date.atTime(LocalTime.parse(ends[course.end - 1])).atZone(campusZone).toInstant()
                // Stable across re-exports, including room/teacher/week changes.
                val identity = "${course.account}|$start|${course.scheduleIdentity()}|$week"
                val uid = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))
                lines += listOf("BEGIN:VEVENT", "UID:$uid@ifafu", "DTSTAMP:${utcFormat.format(now)}",
                    "DTSTART:${utcFormat.format(begin)}", "DTEND:${utcFormat.format(end)}",
                    "SUMMARY:${escape(course.name)}", "LOCATION:${escape(course.address)}",
                    "DESCRIPTION:${escape(course.teacher)}", "END:VEVENT")
                eventCount++
            }
        }
        require(eventCount > 0) { "暂无可导出的有效课程" }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace(";", "\\;")
        .replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /** Fold at 75 UTF-8 octets without splitting a Unicode code point. */
    private fun fold(line: String): String {
        val result = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < line.length) {
            val codePoint = line.codePointAt(offset)
            val character = String(Character.toChars(codePoint))
            val size = character.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 75) {
                result.append("\r\n ")
                bytes = 1
            }
            result.append(character)
            bytes += size
            offset += Character.charCount(codePoint)
        }
        return result.toString()
    }
}

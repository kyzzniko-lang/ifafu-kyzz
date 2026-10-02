package com.ifafu.kyzz.data.util

import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.model.Syllabus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Locale

class SyllabusCalendarExporterTest {
    private fun course() = Course(account = "student", name = "课程", weekDay = 1,
        begin = 1, end = 2, weekBegin = 1, weekEnd = 3)

    @Test fun `campus times are UTC and required event fields use CRLF`() {
        val output = SyllabusCalendarExporter.export(Syllabus(courses = listOf(course())),
            "2026-08-31 00:00:00", Instant.parse("2026-10-02T12:00:00Z"))
        assertTrue(output.contains("DTSTART:20260831T000000Z\r\n"))
        assertTrue(output.contains("DTEND:20260831T013500Z\r\n"))
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(output).count())
        assertEquals(3, Regex("UID:").findAll(output).count())
        assertEquals(3, Regex("DTSTAMP:20261002T120000Z").findAll(output).count())
        assertFalse(output.replace("\r\n", "").contains('\n'))
    }

    @Test fun `irregular and odd weeks preserve only actual occurrences`() {
        val course = course().copy(weekEnd = 5, weeks = listOf(1, 2, 5), oddOrTwice = 1)
        val output = SyllabusCalendarExporter.export(Syllabus(courses = listOf(course, course)), "2026-08-31")
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(output).count())
        assertTrue(output.contains("DTSTART:20260928T000000Z"))
        assertFalse(output.contains("DTSTART:20260907T000000Z"))
    }

    @Test fun `unicode lines fold within 75 bytes and escape injected fields`() {
        val course = course().copy(name = "课😀".repeat(60) + ",;\nLOCATION:假的")
        val output = SyllabusCalendarExporter.export(Syllabus(courses = listOf(course)), "2026-08-31")
        output.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        val unfolded = output.replace("\r\n ", "")
        assertTrue(unfolded.contains("\\,\\;\\nLOCATION:假的"))
        assertFalse(unfolded.contains("\r\nLOCATION:假的"))
    }

    @Test fun `UID stays stable and dates stay ASCII across locales`() {
        val previous = Locale.getDefault()
        try {
            val syllabus = Syllabus(courses = listOf(course()))
            val first = SyllabusCalendarExporter.export(syllabus, "2026-08-31")
            Locale.setDefault(Locale.forLanguageTag("ar"))
            val second = SyllabusCalendarExporter.export(syllabus, "2026-08-31")
            assertEquals(first.lineSequence().filter { it.startsWith("UID:") }.toList(),
                second.lineSequence().filter { it.startsWith("UID:") }.toList())
            assertTrue(second.contains("DTSTART:20260831T000000Z"))
        } finally { Locale.setDefault(previous) }
    }

    @Test(expected = java.time.DateTimeException::class)
    fun `invalid semester date fails predictably`() {
        SyllabusCalendarExporter.export(Syllabus(courses = listOf(course())), "2026-02-30")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid course cannot export invented period one`() {
        SyllabusCalendarExporter.export(Syllabus(courses = listOf(course().copy(begin = 0))), "2026-08-31")
    }
}

package com.ifafu.kyzz.data.util

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

class CountdownDatesTest {
    @Test fun `DST spring and autumn boundaries count calendar days`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(1L, CountdownDates.daysUntil("2026-03-09", LocalDate.parse("2026-03-08")))
            assertEquals(-1L, CountdownDates.daysUntil("2026-03-08", LocalDate.parse("2026-03-09")))
            assertEquals(1L, CountdownDates.daysUntil("2026-11-02", LocalDate.parse("2026-11-01")))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun `impossible or trailing dates are rejected`() {
        assertNull(CountdownDates.daysUntil("2026-02-30"))
        assertNull(CountdownDates.daysUntil("2026-03-09garbage"))
    }

    @Test fun `damaged storage keeps valid events without null entries`() {
        assertTrue(CountdownDates.readEvents("null").isEmpty())
        assertTrue(CountdownDates.readEvents("{}").isEmpty())
        val json = """[null,{"id":"bad","name":null,"date":"2026-10-02"},
            {"id":"ok","name":"考试","date":"2026-10-02"}]"""
        assertEquals(listOf("ok"), CountdownDates.readEvents(json).map { it.id })
    }
}

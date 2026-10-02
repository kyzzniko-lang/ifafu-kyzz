package com.ifafu.kyzz.data.util

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ifafu.kyzz.data.model.CountdownEvent
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Calendar-day arithmetic stays correct across 23/25-hour daylight-saving days. */
object CountdownDates {
    fun daysUntil(date: String, today: LocalDate = LocalDate.now()): Long? = try {
        ChronoUnit.DAYS.between(today, LocalDate.parse(date))
    } catch (_: java.time.DateTimeException) {
        null
    }

    fun readEvents(json: String?): List<CountdownEvent> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyList()
            val gson = Gson()
            array.mapNotNull { element ->
                try {
                    if (!element.isJsonObject) return@mapNotNull null
                    val event = gson.fromJson(element, CountdownEvent::class.java)
                    if (event.id.isNullOrBlank() || event.name.isNullOrBlank() ||
                        event.date.isNullOrBlank()) null else event
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }
}

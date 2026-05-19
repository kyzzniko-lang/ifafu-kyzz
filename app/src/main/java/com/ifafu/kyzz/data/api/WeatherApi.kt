package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.DailyWeather
import com.ifafu.kyzz.data.model.HourlyWeather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherApi @Inject constructor() {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    suspend fun fetchWeather(lat: Double, lng: Double): DailyWeather? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lng" +
                "&hourly=temperature_2m,weather_code,precipitation_probability" +
                "&timezone=Asia/Shanghai&forecast_days=1"

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(body)
            val hourly = json.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val codes = hourly.getJSONArray("weather_code")
            val precip = hourly.getJSONArray("precipitation_probability")

            val today = LocalDate.now().format(dateFormatter)
            val list = mutableListOf<HourlyWeather>()
            for (i in 0 until times.length()) {
                val timeStr = times.getString(i)
                if (!timeStr.startsWith(today)) continue
                val hour = timeStr.substring(11, 13).toIntOrNull() ?: continue
                list.add(HourlyWeather(
                    hour = hour,
                    temp = temps.getDouble(i).toInt(),
                    weatherCode = codes.getInt(i),
                    precipProb = precip.getInt(i)
                ))
            }

            if (list.isEmpty()) null else DailyWeather(date = today, hourly = list)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

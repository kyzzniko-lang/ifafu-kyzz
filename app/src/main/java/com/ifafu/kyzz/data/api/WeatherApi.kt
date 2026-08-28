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
import java.time.ZoneId
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
            response.use {
                val body = it.body?.string() ?: return@withContext null
                if (!it.isSuccessful) return@withContext null

                val json = JSONObject(body)
                val hourly = json.getJSONObject("hourly")
                val times = hourly.getJSONArray("time")
                val temps = hourly.getJSONArray("temperature_2m")
                val codes = hourly.getJSONArray("weather_code")
                val precip = hourly.getJSONArray("precipitation_probability")

                // 接口按 Asia/Shanghai 返回时间标签，"今天"必须用同一时区计算；
                // 否则设备在非 UTC+8 时区时过滤不出任何小时，天气卡片静默消失。
                val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(dateFormatter)
                val list = mutableListOf<HourlyWeather>()
                for (i in 0 until times.length()) {
                    val timeStr = times.getString(i)
                    if (!timeStr.startsWith(today)) continue
                    // ISO 补零格式最短 "yyyy-MM-ddTHH:mm";长度不足的脏数据直接
                    // 跳过该小时,不让 substring 越界炸掉整天天气
                    if (timeStr.length < 13) continue
                    val hour = timeStr.substring(11, 13).toIntOrNull() ?: continue
                    // Open-Meteo 对缺测时段会输出 JSON null：getDouble/getInt 抛
                    // JSONException 被外层捕获后整个白天数据全丢，天气卡片"随机
                    // 消失"。单小时缺测跳过即可，其余小时照常展示。
                    val temp = temps.optDouble(i, Double.NaN)
                    if (temp.isNaN()) continue
                    val code = if (codes.isNull(i)) 0 else codes.getInt(i)
                    val precipProb = if (precip.isNull(i)) 0 else precip.getInt(i)
                    list.add(HourlyWeather(
                        hour = hour,
                        temp = temp.toInt(),
                        weatherCode = code,
                        precipProb = precipProb
                    ))
                }

                if (list.isEmpty()) null else DailyWeather(date = today, hourly = list)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

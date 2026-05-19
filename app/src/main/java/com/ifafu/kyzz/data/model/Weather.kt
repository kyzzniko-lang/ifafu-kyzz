package com.ifafu.kyzz.data.model

/** 校区天气配置，坐标与 CampusMapActivity 保持一致 */
enum class CampusWeather(val key: String, val displayName: String, val lat: Double, val lng: Double) {
    JINSHAN("jinshan", "金山校区", 26.085206, 119.239669),
    QISHAN("qishan", "旗山校区", 26.056579, 119.180505),
    ANXI("anxi", "安溪校区", 25.079766, 118.234897),
    NANPING("nanping", "南平校区", 26.555664, 118.119161);

    companion object {
        fun fromKey(key: String): CampusWeather = entries.find { it.key == key } ?: JINSHAN
    }
}

data class HourlyWeather(
    val hour: Int,          // 0-23
    val temp: Int,          // °C
    val weatherCode: Int,   // WMO code
    val precipProb: Int     // 0-100
)

data class DailyWeather(
    val date: String,
    val hourly: List<HourlyWeather>,
    val cachedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // WMO weather code → emoji + description
        private val codeMap = mapOf(
            0 to Pair("☀️", "晴"),
            1 to Pair("🌤️", "少云"),
            2 to Pair("⛅", "多云"),
            3 to Pair("☁️", "阴"),
            45 to Pair("🌫️", "雾"),
            48 to Pair("🌫️", "雾凇"),
            51 to Pair("🌦️", "小雨"),
            53 to Pair("🌦️", "中雨"),
            55 to Pair("🌦️", "大雨"),
            61 to Pair("🌧️", "小雨"),
            63 to Pair("🌧️", "中雨"),
            65 to Pair("🌧️", "大雨"),
            71 to Pair("🌨️", "小雪"),
            73 to Pair("🌨️", "中雪"),
            75 to Pair("🌨️", "大雪"),
            80 to Pair("🌧️", "阵雨"),
            81 to Pair("🌧️", "中阵雨"),
            82 to Pair("🌧️", "大阵雨"),
            95 to Pair("⛈️", "雷暴"),
            96 to Pair("⛈️", "雷暴+冰雹"),
            99 to Pair("⛈️", "强雷暴")
        )

        fun weatherEmoji(code: Int): String = codeMap[code]?.first ?: "🌈"
        fun weatherDesc(code: Int): String = codeMap[code]?.second ?: "未知"
    }

    /** 获取最接近指定小时(如8点对应第1节课)的天气 */
    fun getWeatherForHour(targetHour: Int): HourlyWeather? {
        return hourly.minByOrNull { kotlin.math.abs(it.hour - targetHour) }
    }

    /** 一天的天气摘要 */
    val summary: String
        get() {
            val avgTemp = hourly.map { it.temp }.average().toInt()
            val mostCommonCode = hourly.groupBy { it.weatherCode }
                .maxByOrNull { it.value.size }?.key ?: 0
            val maxPrecip = hourly.maxOfOrNull { it.precipProb } ?: 0
            return "${weatherEmoji(mostCommonCode)} ${weatherDesc(mostCommonCode)}  ${avgTemp}°C" +
                if (maxPrecip > 30) "  🌂${maxPrecip}%" else ""
        }
}

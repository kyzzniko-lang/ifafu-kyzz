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
        // WMO weather code → description
        private val codeMap = mapOf(
            0 to "晴",
            1 to "少云",
            2 to "多云",
            3 to "阴",
            45 to "雾",
            48 to "雾凇",
            51 to "小雨",
            53 to "中雨",
            55 to "大雨",
            61 to "小雨",
            63 to "中雨",
            65 to "大雨",
            71 to "小雪",
            73 to "中雪",
            75 to "大雪",
            80 to "阵雨",
            81 to "中阵雨",
            82 to "大阵雨",
            95 to "雷暴",
            96 to "雷暴+冰雹",
            99 to "强雷暴"
        )

        fun weatherDesc(code: Int): String = codeMap[code] ?: "未知"
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
            return "${weatherDesc(mostCommonCode)} · ${avgTemp}°C" +
                if (maxPrecip > 30) " · 降雨${maxPrecip}%" else ""
        }
}

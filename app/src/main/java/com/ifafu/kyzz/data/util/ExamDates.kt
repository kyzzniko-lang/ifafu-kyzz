package com.ifafu.kyzz.data.util

import com.ifafu.kyzz.data.model.Exam
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale

/**
 * 考试时间串解析的公共工具。教务 datetime 字段实际出现过这些形态：
 *  - "2026-06-29(周一)09:00-11:00"
 *  - "2026-06-29~2026-06-30(周一)09:00-11:00"（跨天考试）
 *  - "2026年6月29日(周一)09:00-11:00"、"2026/6/29 09:00-11:00"
 *
 * 旧逻辑只取首个日期：跨天考试第一天晚上就被标"已完成"、第二天的冲突检测
 * 不到；日期归一化各用各的 pattern，"2026-06-29"与"2026/6/29"分不到同组。
 *
 * 天数差一律用 LocalDate 计算：跨夏令时边界的两个"本地零点"间隔是 23/25
 * 小时，直接对毫秒做 floorDiv(24h) 会差一天。
 */
object ExamDates {

    private val datePatterns = listOf(
        "yyyy-MM-dd", "yyyy/M/d", "yyyy.MM.dd", "yyyy年M月d日", "yyyy年MM月dd日"
    )

    /** 提取 datetime 里的所有日期（跨天考试含起止两天）。解析失败返回空表。 */
    fun allDates(datetime: String): List<Calendar> {
        if (datetime.isEmpty()) return emptyList()
        val raw = datetime.replace("（", "(").replace("）", ")")
            .replace("～", "~").replace("至", "~")
        val result = mutableListOf<Calendar>()
        val candidateRegex = Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日")
        for (candidate in candidateRegex.findAll(raw).map { it.value }.distinct()) {
            for (pattern in datePatterns) {
                try {
                    val fmt = SimpleDateFormat(pattern, Locale.getDefault())
                    fmt.isLenient = false
                    val parsed = fmt.parse(candidate) ?: continue
                    result.add(Calendar.getInstance().apply { time = parsed })
                    break
                } catch (_: Exception) {
                    continue
                }
            }
        }
        return result
    }

    private fun todayZeroed(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun Calendar.toLocalDate(): LocalDate =
        toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * 所有日期都已早于今天 → 考试结束。跨天考试以最后一天为准；
     * 是否含"今天"由调用方结合当天时段另行判断（参见 [hasDayOn]）。
     */
    fun isAllBeforeToday(datetime: String): Boolean {
        val dates = allDates(datetime)
        if (dates.isEmpty()) return false
        val today = todayZeroed()
        return dates.all { it.before(today) }
    }

    /** datetime 中是否存在落在"今天"的场次。 */
    fun hasDayOn(datetime: String, day: Calendar = todayZeroed()): Boolean =
        allDates(datetime).any { it.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
            it.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR) }

    /** 是否还有"今天或未来"的场次。 */
    fun hasUpcomingDay(datetime: String): Boolean {
        val dates = allDates(datetime)
        if (dates.isEmpty()) return false
        val today = todayZeroed()
        return dates.any { !it.before(today) }
    }

    /** 距离最近一场考试的天数（负数=已过去）。解析失败返回 Int.MAX_VALUE。 */
    fun daysUntilNearest(datetime: String): Int {
        val dates = allDates(datetime).map { it.toLocalDate() }
        if (dates.isEmpty()) return Int.MAX_VALUE
        val today = LocalDate.now()
        return dates.minOf { ChronoUnit.DAYS.between(today, it).toInt() }
    }

    // ==================== 详情页倒计时状态 ====================

    /**
     * 考试倒计时状态（详情页/列表统一语义）。跨天考试以"所有场次"为准：
     * 只要还有未来场次就不算结束，修复了"第二天打开详情显示已结束"的错乱。
     */
    sealed class CountdownState {
        /** 全部场次已结束，daysAgo = 距最后一场过了几天。 */
        data class Finished(val daysAgo: Int) : CountdownState()
        /** 今天有场次。 */
        object Today : CountdownState()
        /** 未开始，daysLeft = 距首场天数。 */
        data class Upcoming(val daysLeft: Int) : CountdownState()
        /** 已开始但今天无场次（跨天考试的中间空档日），daysToEnd = 距最后一场还有几天。 */
        data class InProgress(val daysToEnd: Int) : CountdownState()
        /** 日期解析失败。 */
        object Unknown : CountdownState()
    }

    fun countdownState(datetime: String, today: LocalDate = LocalDate.now()): CountdownState {
        val dates = allDates(datetime).map { it.toLocalDate() }.sorted()
        if (dates.isEmpty()) return CountdownState.Unknown
        val first = dates.first()
        val last = dates.last()
        return when {
            today.isAfter(last) -> CountdownState.Finished(
                ChronoUnit.DAYS.between(last, today).toInt()
            )
            today in dates -> CountdownState.Today
            today.isBefore(first) -> CountdownState.Upcoming(
                ChronoUnit.DAYS.between(today, first).toInt()
            )
            else -> CountdownState.InProgress(
                ChronoUnit.DAYS.between(today, last).toInt()
            )
        }
    }

    // ==================== 冲突检测 ====================

    /** 提取首个 "HH:mm-HH:mm" 时段 key（剥离"周一)"等干扰），供同日排序使用。 */
    fun timeKey(datetime: String): String {
        val m = Regex("(\\d{1,2}:\\d{2})\\s*[-~]\\s*(\\d{1,2}:\\d{2})").find(datetime) ?: return ""
        return "${m.groupValues[1]}-${m.groupValues[2]}"
    }

    /** 提取全部 "HH:mm-HH:mm" 时段（分钟数区间），多时段考试全量返回。 */
    fun allPeriods(datetime: String): List<Pair<Int, Int>> {
        if (datetime.isEmpty()) return emptyList()
        return Regex("(\\d{1,2}):(\\d{2})\\s*[-~]\\s*(\\d{1,2}):(\\d{2})")
            .findAll(datetime)
            .mapNotNull { m ->
                val start = m.groupValues[1].toIntOrNull()?.let { h ->
                    h * 60 + (m.groupValues[2].toIntOrNull() ?: return@mapNotNull null)
                } ?: return@mapNotNull null
                val end = m.groupValues[3].toIntOrNull()?.let { h ->
                    h * 60 + (m.groupValues[4].toIntOrNull() ?: return@mapNotNull null)
                } ?: return@mapNotNull null
                if (end > start) start to end else null
            }
            .toList()
    }

    /** 归一化全部日期为 yyyy-MM-dd 集合（跨天考试含每一天）。 */
    private fun normalizedDateSet(datetime: String): Set<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return allDates(datetime).map { fmt.format(it.time) }.toSet()
    }

    /**
     * 两场考试是否冲突：任一日期相同，且任一时段区间相交。
     * 双方都解析不出时段时保守提示冲突（同日未知时间）；只有一方有时段
     * 则不判（与旧版按键分组的漏报行为一致）。
     */
    fun conflictsBetween(a: String, b: String): Boolean {
        val datesA = normalizedDateSet(a)
        val datesB = normalizedDateSet(b)
        if (datesA.isEmpty() || datesB.isEmpty()) return false
        if (datesA.intersect(datesB).isEmpty()) return false
        val periodsA = allPeriods(a)
        val periodsB = allPeriods(b)
        if (periodsA.isEmpty() && periodsB.isEmpty()) return true
        if (periodsA.isEmpty() || periodsB.isEmpty()) return false
        return periodsA.any { pa -> periodsB.any { pb ->
            pa.first < pb.second && pb.first < pa.second
        } }
    }

    /**
     * 冲突分组：两两判定后做连通分量合并——A 撞 B、B 撞 C 时 A/C 同组，
     * 三方冲突能聚合出完整提示（旧版按"日期|时段"字符串分组做不到）。
     */
    fun findConflictGroups(exams: List<Exam>): List<List<Exam>> {
        val list = exams.filter { it.datetime.isNotEmpty() }
        if (list.size < 2) return emptyList()
        // 并查集
        val parent = IntArray(list.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var cur = i
            while (parent[cur] != root) { val next = parent[cur]; parent[cur] = root; cur = next }
            return root
        }
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                if (conflictsBetween(list[i].datetime, list[j].datetime)) {
                    val ri = find(i); val rj = find(j)
                    if (ri != rj) parent[rj] = ri
                }
            }
        }
        return list.indices.groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { idx -> idx.map { list[it] } }
    }
}

package com.ifafu.kyzz.ui.main

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.model.Exam
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.TermResolver
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager,
    private val examApi: com.ifafu.kyzz.data.api.ExamApi,
    private val syllabusApi: com.ifafu.kyzz.data.api.SyllabusApi,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ReloginViewModel() {

    private val _user = MutableLiveData<User>()
    val user: LiveData<User> = _user

    private val _todayCourses = MutableLiveData<List<TodayCourse>>()
    val todayCourses: LiveData<List<TodayCourse>> = _todayCourses

    private val _nextExam = MutableLiveData<ExamCountdown?>()
    val nextExam: LiveData<ExamCountdown?> = _nextExam

    private val _currentWeek = MutableLiveData<Int>()
    val currentWeek: LiveData<Int> = _currentWeek

    init {
        _user.value = userRepository.getUser()
        _currentWeek.value = calculateCurrentWeek()
    }

    val isLoggedIn: Boolean get() = _user.value?.isLogin == true

    fun refreshUser(manualRefresh: Boolean = false) {
        _user.value = userRepository.getUser()
        _currentWeek.value = calculateCurrentWeek()
        if (isLoggedIn) {
            // 登出会拆掉每日提醒闹钟链（cancel），但重新登录后没有 boot 广播可依赖，
            // 必须在这里重挂；ensureScheduled 幂等（固定 requestCode 覆盖同一
            // PendingIntent），频繁调用只会原地刷新，不会叠加闹钟。
            com.ifafu.kyzz.service.CourseReminderReceiver.ensureScheduled(appContext)
            com.ifafu.kyzz.service.ScoreCheckReceiver.ensureScheduled(appContext)
        }
        // 立即从缓存加载今日课程和考试（不等待网络）。同一天已加载过时直接复用
        // 内存数据，首页切页返回瞬时显示；仅用户下拉刷新（manualRefresh）或跨天
        // 时重新计算。
        loadTodayCourses(force = manualRefresh)
        loadNextExam(force = manualRefresh)
        // 并行后台同步学期日期；每个进程只同步一次，下拉刷新时强制重新检查
        if (manualRefresh) hasFetchedTermFirstDay = false
        if (!hasFetchedTermFirstDay) {
            hasFetchedTermFirstDay = true
            fetchTermFirstDayFromGitHub()
        }
    }

    private fun calculateCurrentWeek(): Int {
        val firstDay = userRepository.termFirstDay
        if (firstDay.isEmpty()) return 0
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(firstDay) ?: return 0
            val termStart = Calendar.getInstance().apply {
                time = parsed
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val diffDays = ((today.timeInMillis - termStart.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
            // 开学前（termFirstDay 在未来 1-6 天内）：Kotlin 整数除法向零截断会把
            // diffDays=-6..-1 算成 week=1，把"下周才开的第一周课"顶到首页当今日
            // 课程。与小组件/课程提醒一致地按未开学处理，返回 0。
            if (diffDays < 0) return 0
            val week = (diffDays / 7) + 1
            // 上限 clamp：正常学期最多约 30 周（含小学期）。超过几乎一定是
            // termFirstDay 过期未更新，此时显示"第40周"既荒谬又与今日课程区域
            // （loadTodayCourses 用 maxCourseWeek+4 判假期）自相矛盾。
            // 返回 0 让 chip 隐藏，与"假期中无今日课程"保持一致。
            if (week > 30) 0 else week
        } catch (_: Exception) { 0 }
    }

    // 今日课程/最近考试按"天"缓存：记录数据属于哪一天，同一天内首页切页返回
    // 直接复用内存结果，只在下拉刷新、跨天或学期切换时重新计算。
    private var todayCoursesDayKey = 0
    private var nextExamDayKey = 0

    private fun currentDayKey(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
    }

    private fun loadTodayCourses(force: Boolean = false) {
        viewModelScope.launch {
            val user = userRepository.getUser()
            if (!user.isLogin) { _todayCourses.postValue(emptyList()); return@launch }

            // 同一天已计算过、且课表缓存未被选课/退课标记失效：直接沿用内存结果，
            // 不再读盘或爬取。loadSyllabusTimestamp==0 表示 invalidateSyllabus 刚
            // 清过时间戳，需要重走缓存+后台拉取流程让首页吃到新课表。
            val todayKey = currentDayKey()
            if (!force && _todayCourses.value != null && todayCoursesDayKey == todayKey &&
                cacheManager.loadSyllabusTimestamp(user.account) > 0L
            ) {
                return@launch
            }

            val syllabus = withContext(kotlinx.coroutines.Dispatchers.IO) {
                // schema 迁移（清掉旧版本写入的坏格式课表缓存）。课表页已调用，
                // 首页也调一次保证用户只打开首页也能触发重新拉取。
                cacheManager.migrateSyllabusCacheIfNeeded(user.account)
                cacheManager.loadSyllabus(user.account)
            }
            if (syllabus == null) {
                fetchSyllabusAndShow()
                return@launch
            }
            if (isPublishedUpcomingBreakSyllabus(syllabus)) {
                // 新课表已发布但尚未开学：首页不把它按旧学期周数计算成“今日课程”。
                todayCoursesDayKey = todayKey
                _todayCourses.postValue(emptyList())
                return@launch
            }
            val currentWeek = calculateCurrentWeek()
            val maxCourseWeek = if (syllabus.courses.isNotEmpty()) syllabus.courses.maxOf { it.weekEnd } else 20
            if (currentWeek > maxCourseWeek + 4) {
                // 暑假/寒假期间，课表已结束，不显示课程也不反复请求网络。
                // 缓存过期（30 天）时刷新一次以探测新学期课表是否发布；
                // 缓存学期与推断学期不一致时（寒假后期 2 月起 inferCurrentTerm 已翻到
                // 新学期，而服务器尚未发布新课表、拉回来的仍是上学期）按 6 小时
                // 限频探测——不限频的话每次冷启动都会全量拉取一遍并原样存回。
                val isCacheExpired = cacheManager.isCacheStale(user.account, "syllabus", 30L * 24 * 60 * 60 * 1000)
                val shouldProbeDifferentTerm = belongsToDifferentTerm(syllabus) &&
                    cacheManager.isSyllabusUpcomingProbeStale(user.account, 6L * 60 * 60 * 1000)
                when {
                    isCacheExpired -> fetchSyllabusAndShow()
                    shouldProbeDifferentTerm -> {
                        cacheManager.markSyllabusUpcomingProbe(user.account)
                        fetchSyllabusAndShow()
                    }
                    else -> {
                        todayCoursesDayKey = todayKey
                        _todayCourses.postValue(emptyList())
                    }
                }
                return@launch
            }
            // 学期开始前（termFirstDay在未来），同样不显示课程
            if (currentWeek <= 0) {
                todayCoursesDayKey = todayKey
                _todayCourses.postValue(emptyList())
                return@launch
            }
            computeTodayCourses(syllabus)
            // 学期中的调课/退改选不会反映到"缓存存在即直接用"的逻辑里：缓存超过
            // 7 天时后台刷新一次，页面先用旧缓存渲染；用户下拉刷新（force）时
            // 总是后台拉取最新课表。
            if (force || cacheManager.isCacheStale(user.account, "syllabus", 7L * 24 * 60 * 60 * 1000)) {
                fetchSyllabusAndShow()
            }
        }
    }

    private fun belongsToDifferentTerm(syllabus: com.ifafu.kyzz.data.model.Syllabus): Boolean {
        val target = TermResolver.inferCurrentTerm()
        val cachedYear = syllabus.searchYearOptions.getOrNull(syllabus.selectedYearOption)
        val cachedTerm = syllabus.searchTermOptions.getOrNull(syllabus.selectedTermOption)
        if (isPublishedUpcomingBreakSyllabus(syllabus)) return false
        return !cachedYear.isNullOrEmpty() && !cachedTerm.isNullOrEmpty() &&
            (cachedYear != target.year || cachedTerm != target.term)
    }

    private fun isPublishedUpcomingBreakSyllabus(
        syllabus: com.ifafu.kyzz.data.model.Syllabus
    ): Boolean {
        val upcoming = TermResolver.breakTransition()?.upcoming ?: return false
        val year = syllabus.searchYearOptions.getOrNull(syllabus.selectedYearOption)
        val term = syllabus.searchTermOptions.getOrNull(syllabus.selectedTermOption)
        val hasContent = syllabus.courses.isNotEmpty() ||
            syllabus.practiceCourses.isNotEmpty() ||
            syllabus.internshipCourses.isNotEmpty() ||
            syllabus.unscheduledCourses.isNotEmpty()
        return year == upcoming.year && term == upcoming.term && hasContent
    }

    private fun fetchSyllabusAndShow() {
        viewModelScope.launch {
            try {
                val freshUser = userRepository.getUser()
                if (!freshUser.isLogin) { _todayCourses.value = emptyList(); return@launch }
                val syllabus = syllabusApi.getSyllabus(userRepository.host, freshUser.token, freshUser.account, freshUser.name)
                if (syllabus != null) {
                    cacheManager.saveSyllabus(freshUser.account, syllabus)
                    computeTodayCourses(syllabus)
                } else {
                    // 拉取失败但旧缓存仍在：保留缓存渲染，不把首页今日课程清空
                    // （刷新失败清空会让用户以为今天的课全没了）。
                    val cached = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cacheManager.loadSyllabus(freshUser.account)
                    }
                    if (cached != null) computeTodayCourses(cached)
                    else _todayCourses.value = emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val cached = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cacheManager.loadSyllabus(userRepository.getUser().account)
                }
                if (cached != null) computeTodayCourses(cached)
                else _todayCourses.value = emptyList()
            }
        }
    }

    private fun computeTodayCourses(syllabus: com.ifafu.kyzz.data.model.Syllabus) {
        // 成功算出（或合法地算空）今日课程：标记当天已完成，后续切页不再重算
        todayCoursesDayKey = currentDayKey()
        val firstDay = userRepository.termFirstDay
        if (firstDay.isEmpty()) { _todayCourses.postValue(emptyList()); return }

        try {
            // 复用 calculateCurrentWeek()：它带有 week>30→0 的上限（防止 termFirstDay
            // 过期时算出"第40周"之类荒谬值）。旧实现在此独立重算且只 coerceAtLeast(0)，
            // 会把荒谬周数顶到首页 chip 上，与 calculateCurrentWeek 自相矛盾。
            val currentWeek = calculateCurrentWeek()
            _currentWeek.postValue(currentWeek)
            // 学期开始前或假期中，不显示课程
            if (currentWeek <= 0) {
                _todayCourses.postValue(emptyList())
                return
            }
            val today = Calendar.getInstance()
            val dayOfWeek = today.get(Calendar.DAY_OF_WEEK)
            val todayDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1

            val matched = syllabus.courses.filter { c ->
                c.coversWeek(currentWeek) && c.weekDay == todayDay &&
                    (c.oddOrTwice == 0 || (c.oddOrTwice == 1 && currentWeek % 2 == 1) || (c.oddOrTwice == 2 && currentWeek % 2 == 0))
            }.sortedBy { it.begin }.map { TodayCourse(it.name, it.teacher, it.address, it.begin, it.end) }
            _todayCourses.postValue(matched)
        } catch (_: Exception) {
            _todayCourses.postValue(emptyList())
        }
    }

    private fun loadNextExam(force: Boolean = false) {
        viewModelScope.launch {
            val user = userRepository.getUser()
            if (!user.isLogin) { _nextExam.postValue(null); return@launch }

            // 同一天已计算过：直接沿用内存结果（下拉刷新/跨天除外）
            if (!force && _nextExam.value != null && nextExamDayKey == currentDayKey()) {
                return@launch
            }

            val examTable = withContext(kotlinx.coroutines.Dispatchers.IO) {
                cacheManager.loadExamTable(user.account)
            }
            if (examTable == null) {
                fetchExamsAndShow()
                return@launch
            }
            findNextExam(examTable.exams)
            // 缓存里的考试全部过期后，补考/新学期考试永远不会出现在首页
            // （旧逻辑缓存存在就只用缓存）。全部过期且缓存超 1 天时刷新一次；
            // 用户下拉刷新（force）时总是拉取最新考试安排。
            val hasUpcoming = examTable.exams.any { com.ifafu.kyzz.data.util.ExamDates.hasUpcomingDay(it.datetime) }
            if (force || (!hasUpcoming && cacheManager.isCacheStale(user.account, "exams", 24 * 60 * 60 * 1000L))) {
                fetchExamsAndShow()
            }
        }
    }

    private fun fetchExamsAndShow() {
        viewModelScope.launch {
            try {
                val freshUser = userRepository.getUser()
                val examTable = examApi.getExamTable(userRepository.host, freshUser.token, freshUser.account, freshUser.name)
                if (examTable != null) {
                    cacheManager.saveExamTable(freshUser.account, examTable)
                    findNextExam(examTable.exams)
                } else {
                    _nextExam.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _nextExam.value = null
            }
        }
    }

    private fun findNextExam(exams: List<com.ifafu.kyzz.data.model.Exam>) {
        // 统一走 ExamDates：支持跨天考试（旧逻辑只取首个日期，第二天倒计时显示
        // -1、第三天被过滤；与考试页/冲突检测的口径也不一致）。
        nextExamDayKey = currentDayKey()
        val next = exams.asSequence()
            .filter { it.datetime.isNotEmpty() }
            .mapNotNull { exam ->
                val daysLeft = com.ifafu.kyzz.data.util.ExamDates.daysUntilNearest(exam.datetime)
                // 只显示近期考试（-1 表示考试当天仍可显示），过滤掉远未来的旧学期考试
                if (daysLeft in -1..60) exam to daysLeft else null
            }
            .sortedWith(compareBy({ it.second }, { com.ifafu.kyzz.data.util.ExamDates.timeKey(it.first.datetime) }))
            .firstOrNull()

        _nextExam.value = next?.let { ExamCountdown(it.first, it.second) }
    }

    data class ExamCountdown(
        val exam: Exam,
        val daysLeft: Int
    )

    private var hasFetchedTermFirstDay = false

    private fun fetchTermFirstDay() {
        if (hasFetchedTermFirstDay) return
        hasFetchedTermFirstDay = true
        fetchTermFirstDayFromGitHub()
    }

    private fun fetchTermFirstDayFromGitHub() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = githubClient
                val request = okhttp3.Request.Builder()
                    .url("https://gh-proxy.com/https://api.github.com/repos/kyzzniko-lang/ifafu-kyzz/issues/2")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        fallbackTermFirstDay()
                        return@launch
                    }
                    val body = it.body?.string() ?: run {
                        fallbackTermFirstDay()
                        return@launch
                    }
                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val issueBody = json.get("body")?.asString ?: ""
                    val regex = Regex("""term_first_day:\s*(\d{4}-\d{2}-\d{2})""")
                    val match = regex.find(issueBody)
                    if (match != null) {
                        val dateStr = match.groupValues[1]
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)
                        if (parsed != null) {
                            val newTermFirstDay = normalizeToMonday(sdf.format(parsed))
                            val oldTermFirstDay = userRepository.termFirstDay
                            val semesterChanged = oldTermFirstDay.isNotEmpty() && newTermFirstDay != oldTermFirstDay

                            userRepository.termFirstDay = newTermFirstDay
                            userRepository.termFirstDayManual = false
                            _currentWeek.postValue(calculateCurrentWeek())

                            // 首次确定学期起始日（全新安装/切换账号后为空）也必须重算：
                            // 同步完成前 computeTodayCourses 可能已因 firstDay 为空
                            // 记过一次"今日无课"，不强制重算当天首页会一直空着。
                            val termFirstDayEstablished = oldTermFirstDay.isEmpty()
                            if (semesterChanged || termFirstDayEstablished) {
                                if (semesterChanged) {
                                    val user = userRepository.getUser()
                                    if (user.isLogin) {
                                        cacheManager.clearCache(user.account)
                                        android.util.Log.i("MainViewModel", "Semester changed ($oldTermFirstDay -> $newTermFirstDay), cache cleared")
                                    }
                                }
                                // 绕过"当天已计算"守卫强制重算
                                loadTodayCourses(force = true)
                            }
                            android.util.Log.i("MainViewModel", "Term first day synced from GitHub: $dateStr")
                            return@launch
                        }
                    }
                    fallbackTermFirstDay()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                fallbackTermFirstDay()
            }
        }
    }

    private fun fallbackTermFirstDay() {
        val existing = userRepository.termFirstDay
        if (existing.isEmpty() || !isValidDateFormat(existing) || isTermFirstDayStale(existing)) {
            setDefaultTermFirstDay()
            // Clear stale cache so old semester data isn't shown with new term dates
            val user = userRepository.getUser()
            if (user.isLogin) {
                cacheManager.clearCache(user.account)
            }
        }
        // 缓存可能刚被清理，绕过"当天已计算"守卫强制重算
        loadTodayCourses(force = true)
    }

    private fun isTermFirstDayStale(dateStr: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val parsed = sdf.parse(dateStr) ?: return true
            val termStart = Calendar.getInstance().apply { time = parsed }
            val now = Calendar.getInstance()
            val diffWeeks = ((now.timeInMillis - termStart.timeInMillis) / (7 * 24 * 60 * 60 * 1000L)).toInt()
            // A normal semester is ~20 weeks; anything beyond 24 weeks is definitely a new semester
            diffWeeks > 24
        } catch (_: Exception) { true }
    }

    private fun isValidDateFormat(date: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.isLenient = false
            sdf.parse(date)
            true
        } catch (_: Exception) { false }
    }

    /**
     * Normalize any date to the Monday of its week.
     * termFirstDay must always be a Monday for updateDateRow() to work correctly.
     */
    private fun normalizeToMonday(dateStr: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val cal = Calendar.getInstance()
            cal.time = sdf.parse(dateStr) ?: return dateStr
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            // Sunday=1, Monday=2, ..., Saturday=7
            // Days to go back to Monday: Sunday->6, Monday->0, Tuesday->1, ..., Saturday->5
            val daysToMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
            cal.add(Calendar.DAY_OF_YEAR, -daysToMonday)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            sdf.format(cal.time)
        } catch (_: Exception) { dateStr }
    }

    private fun setDefaultTermFirstDay() {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val termStart = when (month) {
            in Calendar.FEBRUARY..Calendar.JULY ->
                Calendar.getInstance().apply { set(year, Calendar.MARCH, 2, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            Calendar.JANUARY ->
                Calendar.getInstance().apply { set(year - 1, Calendar.SEPTEMBER, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            else ->
                Calendar.getInstance().apply { set(year, Calendar.SEPTEMBER, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        userRepository.termFirstDay = normalizeToMonday(sdf.format(termStart.time))
    }

    data class TodayCourse(
        val name: String,
        val teacher: String,
        val address: String,
        val begin: Int,
        val end: Int
    )

    companion object {
        private val githubClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }
}

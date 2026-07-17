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
    private val syllabusApi: com.ifafu.kyzz.data.api.SyllabusApi
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

    fun refreshUser(force: Boolean = false) {
        _user.value = userRepository.getUser()
        _currentWeek.value = calculateCurrentWeek()
        if (!force && _todayCourses.value != null && _nextExam.value != null) {
            return
        }
        // 立即从缓存加载今日课程和考试（不等待网络）
        loadTodayCourses()
        loadNextExam()
        // 并行后台同步学期日期（force时重新检查）
        if (force) hasFetchedTermFirstDay = false
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
            val week = ((diffDays / 7) + 1).coerceAtLeast(0)
            // 上限 clamp：正常学期最多约 30 周（含小学期）。超过几乎一定是
            // termFirstDay 过期未更新，此时显示"第40周"既荒谬又与今日课程区域
            // （loadTodayCourses 用 maxCourseWeek+4 判假期）自相矛盾。
            // 返回 0 让 chip 隐藏，与"假期中无今日课程"保持一致。
            if (week > 30) 0 else week
        } catch (_: Exception) { 0 }
    }

    fun logout() {
        userRepository.clearUser()
        _user.value = User()
    }

    private fun loadTodayCourses() {
        viewModelScope.launch {
            val user = userRepository.getUser()
            if (!user.isLogin) { _todayCourses.postValue(emptyList()); return@launch }

            val syllabus = withContext(kotlinx.coroutines.Dispatchers.IO) {
                cacheManager.loadSyllabus(user.account)
            }
            if (syllabus == null) {
                fetchSyllabusAndShow()
                return@launch
            }
            if (isPublishedUpcomingBreakSyllabus(syllabus)) {
                // 新课表已发布但尚未开学：首页不把它按旧学期周数计算成“今日课程”。
                _todayCourses.postValue(emptyList())
                return@launch
            }
            val currentWeek = calculateCurrentWeek()
            val maxCourseWeek = if (syllabus.courses.isNotEmpty()) syllabus.courses.maxOf { it.weekEnd } else 20
            if (currentWeek > maxCourseWeek + 4) {
                // 暑假/寒假期间，课表已结束，不显示课程也不反复请求网络
                // 只有在课表缓存过期（30天）时才尝试刷新，以检测新学期课表是否已发布
                // During a normal vacation do not poll repeatedly; only refresh after
                // the long-term cache expires or when the inferred semester changes.
                val isCacheExpired = cacheManager.isCacheStale(user.account, "syllabus", 30L * 24 * 60 * 60 * 1000)
                if (isCacheExpired || belongsToDifferentTerm(syllabus)) {
                    fetchSyllabusAndShow()
                } else {
                    _todayCourses.postValue(emptyList())
                }
                return@launch
            }
            // 学期开始前（termFirstDay在未来），同样不显示课程
            if (currentWeek <= 0) {
                _todayCourses.postValue(emptyList())
                return@launch
            }
            computeTodayCourses(syllabus)
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
                val syllabus = syllabusApi.getSyllabus(userRepository.host, freshUser.token, freshUser.account, freshUser.name)
                if (syllabus != null) {
                    cacheManager.saveSyllabus(freshUser.account, syllabus)
                    computeTodayCourses(syllabus)
                } else {
                    _todayCourses.value = emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _todayCourses.value = emptyList()
            }
        }
    }

    private fun computeTodayCourses(syllabus: com.ifafu.kyzz.data.model.Syllabus) {
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
                currentWeek in c.weekBegin..c.weekEnd && c.weekDay == todayDay &&
                    (c.oddOrTwice == 0 || (c.oddOrTwice == 1 && currentWeek % 2 == 1) || (c.oddOrTwice == 2 && currentWeek % 2 == 0))
            }.sortedBy { it.begin }.map { TodayCourse(it.name, it.teacher, it.address, it.begin, it.end) }
            _todayCourses.postValue(matched)
        } catch (_: Exception) {
            _todayCourses.postValue(emptyList())
        }
    }

    private fun loadNextExam() {
        viewModelScope.launch {
            val user = userRepository.getUser()
            if (!user.isLogin) { _nextExam.postValue(null); return@launch }

            val examTable = withContext(kotlinx.coroutines.Dispatchers.IO) {
                cacheManager.loadExamTable(user.account)
            }
            if (examTable == null) {
                fetchExamsAndShow()
                return@launch
            }
            findNextExam(examTable.exams)
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
        // 每次调用创建新的 SimpleDateFormat 实例，确保线程安全
        fun dateFormats() = listOf(
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("yyyy/M/d", Locale.US),
            SimpleDateFormat("yyyy.MM.dd", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
            SimpleDateFormat("yyyy年M月d日", Locale.US),
            SimpleDateFormat("yyyy年MM月dd日", Locale.US)
        )

        val nowCal = Calendar.getInstance()
        val todayDay = Calendar.getInstance().apply {
            set(nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH), nowCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val next = exams.filter { it.datetime.isNotEmpty() }.mapNotNull { exam ->
            try {
                val raw = exam.datetime
                    .replace("（", "(").replace("）", ")")
                    .replace("～", "~").replace("至", "~")
                val datePart = raw.split("(", "~", " ").first().trim()
                var parsed: java.util.Date? = null
                for (fmt in dateFormats()) {
                    try { parsed = fmt.parse(datePart); break } catch (_: Exception) {}
                }
                if (parsed == null) {
                    android.util.Log.w("MainViewModel", "Cannot parse exam date: '${exam.datetime}' (datePart='$datePart')")
                    return@mapNotNull null
                }
                val parsedTime = parsed.time

                val examCal = Calendar.getInstance().apply { time = parsed }
                val examDay = Calendar.getInstance().apply {
                    set(examCal.get(Calendar.YEAR), examCal.get(Calendar.MONTH), examCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayDiff = ((examDay.timeInMillis - todayDay.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
                // 只显示未来7天内的考试（-1 表示考试当天仍可显示），过滤掉远未来的旧学期考试
                if (dayDiff >= -1 && dayDiff <= 60) Triple(exam, dayDiff, parsedTime) else null
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Error parsing exam: ${e.message}")
                null
            }
        }.sortedBy { it.third }.firstOrNull()

        if (next != null) {
            _nextExam.value = ExamCountdown(next.first, next.second)
        } else {
            _nextExam.value = null
        }
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

                            if (semesterChanged) {
                                val user = userRepository.getUser()
                                if (user.isLogin) {
                                    cacheManager.clearCache(user.account)
                                    android.util.Log.i("MainViewModel", "Semester changed ($oldTermFirstDay -> $newTermFirstDay), cache cleared")
                                }
                                loadTodayCourses()
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
        loadTodayCourses()
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

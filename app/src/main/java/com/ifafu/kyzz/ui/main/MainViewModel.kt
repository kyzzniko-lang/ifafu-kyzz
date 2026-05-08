package com.ifafu.kyzz.ui.main

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.model.Exam
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
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
        fetchTermFirstDay()
    }

    val isLoggedIn: Boolean get() = _user.value?.isLogin == true

    fun refreshUser() {
        _user.value = userRepository.getUser()
        _currentWeek.value = calculateCurrentWeek()
        fetchTermFirstDay()
        loadTodayCourses()
        loadNextExam()
    }

    private fun calculateCurrentWeek(): Int {
        val firstDay = userRepository.termFirstDay
        if (firstDay.isEmpty()) return 0
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val parsed = sdf.parse(firstDay) ?: return 0
            val termStart = Calendar.getInstance().apply { time = parsed }
            val today = Calendar.getInstance()
            val diffDays = ((today.timeInMillis - termStart.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()
            (diffDays / 7) + 1
        } catch (_: Exception) { 0 }
    }

    fun logout() {
        userRepository.clearUser()
        _user.value = User()
    }

    private fun loadTodayCourses() {
        val user = userRepository.getUser()
        if (!user.isLogin) { _todayCourses.value = emptyList(); return }

        val syllabus = cacheManager.loadSyllabus(user.account)
        if (syllabus == null) {
            fetchSyllabusAndShow()
            return
        }
        computeTodayCourses(syllabus)
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
            } catch (_: Exception) {
                _todayCourses.value = emptyList()
            }
        }
    }

    private fun computeTodayCourses(syllabus: com.ifafu.kyzz.data.model.Syllabus) {
        val firstDay = userRepository.termFirstDay
        if (firstDay.isEmpty()) { _todayCourses.value = emptyList(); return }

        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val parsed = sdf.parse(firstDay)
            if (parsed == null) {
                _todayCourses.value = emptyList()
                return
            }
            val termStart = Calendar.getInstance().apply { time = parsed }
            val today = Calendar.getInstance()
            val diffDays = ((today.timeInMillis - termStart.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()
            val currentWeek = (diffDays / 7) + 1
            _currentWeek.postValue(currentWeek)
            val dayOfWeek = today.get(Calendar.DAY_OF_WEEK)
            val todayDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1

            val matched = syllabus.courses.filter { c ->
                currentWeek in c.weekBegin..c.weekEnd && c.weekDay == todayDay &&
                    (c.oddOrTwice == 0 || (c.oddOrTwice == 1 && currentWeek % 2 == 1) || (c.oddOrTwice == 2 && currentWeek % 2 == 0))
            }.sortedBy { it.begin }.map { TodayCourse(it.name, it.teacher, it.address, it.begin, it.end) }
            _todayCourses.value = matched
        } catch (_: Exception) {
            _todayCourses.value = emptyList()
        }
    }

    private fun loadNextExam() {
        val user = userRepository.getUser()
        if (!user.isLogin) { _nextExam.value = null; return }

        val examTable = cacheManager.loadExamTable(user.account)
        if (examTable == null) {
            fetchExamsAndShow()
            return
        }
        findNextExam(examTable.exams)
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
            } catch (_: Exception) {
                _nextExam.value = null
            }
        }
    }

    private fun findNextExam(exams: List<com.ifafu.kyzz.data.model.Exam>) {
        val datePatterns = listOf(
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()),
            SimpleDateFormat("yyyy/M/d", Locale.getDefault()),
            SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
            SimpleDateFormat("yyyy年M月d日", Locale.getDefault()),
            SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())
        )

        val next = exams.filter { it.datetime.isNotEmpty() }.mapNotNull { exam ->
            try {
                val raw = exam.datetime
                    .replace("（", "(").replace("）", ")")
                    .replace("～", "~").replace("至", "~")
                val datePart = raw.split("(", "~", " ").first().trim()
                var parsed: java.util.Date? = null
                for (fmt in datePatterns) {
                    try { parsed = fmt.parse(datePart); break } catch (_: Exception) {}
                }
                if (parsed == null) {
                    android.util.Log.w("MainViewModel", "Cannot parse exam date: '${exam.datetime}' (datePart='$datePart')")
                    return@mapNotNull null
                }
                val parsedTime = parsed.time

                val examCal = Calendar.getInstance().apply { time = parsed }
                val nowCal = Calendar.getInstance()
                val examDay = Calendar.getInstance().apply {
                    set(examCal.get(Calendar.YEAR), examCal.get(Calendar.MONTH), examCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayDay = Calendar.getInstance().apply {
                    set(nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH), nowCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayDiff = ((examDay.timeInMillis - todayDay.timeInMillis) / (24.0 * 60 * 60 * 1000)).toInt()
                if (dayDiff >= -1) Triple(exam, dayDiff, parsedTime) else null
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

    private fun fetchTermFirstDay() {
        if (userRepository.termFirstDayManual) return
        fetchTermFirstDayFromGitHub()
    }

    private fun fetchTermFirstDayFromGitHub() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/kyzzniko-lang/ifafu-kyzz/issues/2")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    fallbackTermFirstDay()
                    return@launch
                }
                val body = response.body?.string() ?: run {
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
                        userRepository.termFirstDay = sdf.format(parsed)
                        _currentWeek.postValue(calculateCurrentWeek())
                        // 重新计算今日课程（用新的学期首日）
                        val user = userRepository.getUser()
                        val syllabus = if (user.isLogin) cacheManager.loadSyllabus(user.account) else null
                        if (syllabus != null) {
                            computeTodayCourses(syllabus)
                        }
                        android.util.Log.i("MainViewModel", "Term first day synced from GitHub: $dateStr")
                        return@launch
                    }
                }
                fallbackTermFirstDay()
            } catch (_: Exception) {
                fallbackTermFirstDay()
            }
        }
    }

    private fun fallbackTermFirstDay() {
        val existing = userRepository.termFirstDay
        if (existing.isEmpty() || !isValidDateFormat(existing)) {
            setDefaultTermFirstDay()
        }
    }

    private fun isValidDateFormat(date: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.isLenient = false
            sdf.parse(date)
            true
        } catch (_: Exception) { false }
    }

    private fun setDefaultTermFirstDay() {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val termStart = if (month in 1..6) {
            Calendar.getInstance().apply { set(year, Calendar.MARCH, 2, 0, 0, 0) }
        } else {
            Calendar.getInstance().apply { set(year, Calendar.SEPTEMBER, 1, 0, 0, 0) }
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        userRepository.termFirstDay = sdf.format(termStart.time)
    }

    data class TodayCourse(
        val name: String,
        val teacher: String,
        val address: String,
        val begin: Int,
        val end: Int
    )
}

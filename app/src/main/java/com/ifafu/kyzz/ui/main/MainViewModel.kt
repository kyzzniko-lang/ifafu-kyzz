package com.ifafu.kyzz.ui.main

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.model.Exam
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.network.awaitSuccessfulText
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

    val isLoggedIn: Boolean get() = userRepository.getUser().isLogin
    private var activeSessionGeneration = userRepository.getAuthSnapshot().sessionGeneration
    private var syllabusFetchJob: kotlinx.coroutines.Job? = null
    private var examFetchJob: kotlinx.coroutines.Job? = null
    private var termSyncJob: kotlinx.coroutines.Job? = null

    fun refreshUser(manualRefresh: Boolean = false) {
        val generation = userRepository.getAuthSnapshot().sessionGeneration
        if (generation != activeSessionGeneration) {
            activeSessionGeneration = generation
            syllabusFetchJob?.cancel()
            examFetchJob?.cancel()
            termSyncJob?.cancel()
            todayCoursesDayKey = 0
            nextExamDayKey = 0
            _todayCourses.value = emptyList()
            _nextExam.value = null
            hasFetchedTermFirstDay = false
        }
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
            // LocalDate 按本地日历日计算，避免"毫秒差整除 24h"在夏令时边界
            // （23/25 小时天）下差一天导致整个冬令时季周次恒差 1（与 ExamDates
            // 修复口径一致）。
            val dateStr = firstDay.substringBefore(' ')
            val termStart = java.time.LocalDate.parse(dateStr)
            val today = java.time.LocalDate.now()
            val diffDays = java.time.temporal.ChronoUnit.DAYS.between(termStart, today).toInt()
            // 开学前（termFirstDay 在未来 1-6 天内）：把"下周才开的第一周课"顶到首页当今日
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
            val snapshot = userRepository.getAuthSnapshot()
            val user = snapshot.user
            if (!user.isLogin) { _todayCourses.value = emptyList(); return@launch }

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
            if (!userRepository.isSessionCurrent(snapshot)) return@launch
            if (syllabus == null) {
                fetchSyllabusAndShow()
                return@launch
            }
            if (isPublishedUpcomingBreakSyllabus(syllabus)) {
                // 新课表已发布但尚未开学：首页不把它按旧学期周数计算成“今日课程”。
                todayCoursesDayKey = todayKey
                _todayCourses.value = emptyList()
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
                        _todayCourses.value = emptyList()
                    }
                }
                return@launch
            }
            // 学期开始前（termFirstDay在未来），同样不显示课程
            if (currentWeek <= 0) {
                todayCoursesDayKey = todayKey
                _todayCourses.value = emptyList()
                return@launch
            }
            // termFirstDay 已按新学期计周（如 8月31日开学），但缓存课表仍属于上一
            // 学期（教务尚未发布新课表）：把旧学期第1周的课冒充"今日课程"会张冠
            // 李戴，宁可显示无课并限频探测，等新课表发布后自动恢复。
            if (belongsToDifferentTerm(syllabus)) {
                if (cacheManager.isSyllabusUpcomingProbeStale(user.account, 6L * 60 * 60 * 1000)) {
                    cacheManager.markSyllabusUpcomingProbe(user.account)
                    fetchSyllabusAndShow()
                } else {
                    _todayCourses.value = emptyList()
                }
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
        syllabusFetchJob?.cancel()
        syllabusFetchJob = viewModelScope.launch {
            val snapshot = userRepository.getAuthSnapshot()
            try {
                val freshUser = snapshot.user
                if (!freshUser.isLogin) { _todayCourses.value = emptyList(); return@launch }
                val syllabus = syllabusApi.getSyllabus(snapshot.host, freshUser.token, freshUser.account, freshUser.name)
                if (!userRepository.isSessionCurrent(snapshot)) return@launch
                if (syllabus != null) {
                    if (!userRepository.runIfSessionCurrent(snapshot) {
                        cacheManager.saveSyllabus(freshUser.account, syllabus)
                    }) return@launch
                    if (belongsToDifferentTerm(syllabus)) {
                        // 教务默认视图仍返回上一学期（新课表未发布）：不能按新学期
                        // 周次展示旧学期课程，显示"今日无课"等待发布。
                        _todayCourses.value = emptyList()
                    } else {
                        computeTodayCourses(syllabus)
                    }
                } else {
                    // 拉取失败但旧缓存仍在：保留缓存渲染，不把首页今日课程清空
                    // （刷新失败清空会让用户以为今天的课全没了）。
                    val cached = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cacheManager.loadSyllabus(freshUser.account)
                    }
                    if (!userRepository.isSessionCurrent(snapshot)) return@launch
                    if (cached != null && !belongsToDifferentTerm(cached)) computeTodayCourses(cached)
                    else _todayCourses.value = emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!userRepository.isSessionCurrent(snapshot)) return@launch
                val cached = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cacheManager.loadSyllabus(snapshot.user.account)
                }
                if (!userRepository.isSessionCurrent(snapshot)) return@launch
                if (cached != null && !belongsToDifferentTerm(cached)) computeTodayCourses(cached)
                else _todayCourses.value = emptyList()
            }
        }
    }

    private fun computeTodayCourses(syllabus: com.ifafu.kyzz.data.model.Syllabus) {
        // 成功算出（或合法地算空）今日课程：标记当天已完成，后续切页不再重算
        todayCoursesDayKey = currentDayKey()
        val firstDay = userRepository.termFirstDay
        if (firstDay.isEmpty()) { _todayCourses.value = emptyList(); return }

        try {
            // 复用 calculateCurrentWeek()：它带有 week>30→0 的上限（防止 termFirstDay
            // 过期时算出"第40周"之类荒谬值）。旧实现在此独立重算且只 coerceAtLeast(0)，
            // 会把荒谬周数顶到首页 chip 上，与 calculateCurrentWeek 自相矛盾。
            val currentWeek = calculateCurrentWeek()
            _currentWeek.value = currentWeek
            // 学期开始前或假期中，不显示课程
            if (currentWeek <= 0) {
                _todayCourses.value = emptyList()
                return
            }
            val today = Calendar.getInstance()
            val dayOfWeek = today.get(Calendar.DAY_OF_WEEK)
            val todayDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1

            val matched = syllabus.courses.filter { c ->
                c.coversWeek(currentWeek) && c.weekDay == todayDay &&
                    (c.oddOrTwice == 0 || (c.oddOrTwice == 1 && currentWeek % 2 == 1) || (c.oddOrTwice == 2 && currentWeek % 2 == 0))
            }.sortedBy { it.begin }.map { TodayCourse(it.name, it.teacher, it.address, it.begin, it.end) }
            _todayCourses.value = matched
        } catch (_: Exception) {
            _todayCourses.value = emptyList()
        }
    }

    private fun loadNextExam(force: Boolean = false) {
        viewModelScope.launch {
            val snapshot = userRepository.getAuthSnapshot()
            val user = snapshot.user
            if (!user.isLogin) { _nextExam.value = null; return@launch }

            // 同一天已计算过：直接沿用内存结果（下拉刷新/跨天除外）
            if (!force && _nextExam.value != null && nextExamDayKey == currentDayKey()) {
                return@launch
            }

            val examTable = withContext(kotlinx.coroutines.Dispatchers.IO) {
                cacheManager.loadExamTable(user.account)
            }
            if (!userRepository.isSessionCurrent(snapshot)) return@launch
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
        examFetchJob?.cancel()
        examFetchJob = viewModelScope.launch {
            val snapshot = userRepository.getAuthSnapshot()
            try {
                val freshUser = snapshot.user
                if (!freshUser.isLogin) return@launch
                val examTable = examApi.getExamTable(snapshot.host, freshUser.token, freshUser.account, freshUser.name)
                if (!userRepository.isSessionCurrent(snapshot)) return@launch
                if (examTable != null) {
                    if (!userRepository.runIfSessionCurrent(snapshot) {
                        cacheManager.saveExamTable(freshUser.account, examTable)
                    }) return@launch
                    findNextExam(examTable.exams)
                } else {
                    // 拉取失败但旧缓存仍在：保留缓存算出的最近考试，不把首页考试
                    // 清空（对照 fetchSyllabusAndShow 的缓存守卫；仅拉取成功才覆盖）。
                    // 置空会让缓存里本有的即将到来的考试消失且当日不再自愈。
                    restoreNextExamFromCache(snapshot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                restoreNextExamFromCache(snapshot)
            }
        }
    }

    private suspend fun restoreNextExamFromCache(snapshot: UserRepository.AuthSnapshot) {
        if (!userRepository.isSessionCurrent(snapshot)) return
        val cached = withContext(kotlinx.coroutines.Dispatchers.IO) {
            cacheManager.loadExamTable(snapshot.user.account)
        }
        if (!userRepository.isSessionCurrent(snapshot)) return
        if (cached != null) findNextExam(cached.exams)
        else _nextExam.value = null
    }

    private fun findNextExam(exams: List<com.ifafu.kyzz.data.model.Exam>) {
        // 统一走 ExamDates：支持跨天考试（旧逻辑只取首个日期，第二天倒计时显示
        // -1、第三天被过滤；与考试页/冲突检测的口径也不一致）。
        nextExamDayKey = currentDayKey()
        val next = exams.asSequence()
            .filter { it.datetime.isNotEmpty() }
            .mapNotNull { exam ->
                val daysLeft = com.ifafu.kyzz.data.util.ExamDates.daysUntilNearest(exam.datetime)
                // 只显示还有未结束场次的近期考试。daysLeft=-1 表示"最近一场是
                // 昨天"，该考试已全部结束——保留它会以最小排序值永远占据首位，
                // 把真正即将到来的考试（以及跨天考试仍在进行的第二天）顶掉。
                if (daysLeft in 0..60) exam to daysLeft else null
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
        termSyncJob?.cancel()
        val snapshot = userRepository.getAuthSnapshot()
        termSyncJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 用户手动指定的开学日优先级最高，远端同步不得覆盖
                // （SettingsFragment 设置 termFirstDayManual=true 后，此前这里的
                // 无条件写入会在首次刷新时静默清掉手动配置）。
                if (userRepository.termFirstDayManual) return@launch
                val client = githubClient
                val request = okhttp3.Request.Builder()
                    .url("https://gh-proxy.com/https://api.github.com/repos/kyzzniko-lang/ifafu-kyzz/issues/2")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                val issueText = client.newCall(request).awaitSuccessfulText()
                run {
                    if (!userRepository.isSessionCurrent(snapshot)) return@launch
                    val body = issueText ?: run {
                        fallbackTermFirstDay(snapshot)
                        return@launch
                    }
                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val issueBody = json.get("body")?.asString ?: ""
                    val regex = Regex("""term_first_day:\s*(\d{4}-\d{2}-\d{2})""")
                    val match = regex.find(issueBody)
                    if (match != null) {
                        val dateStr = match.groupValues[1]
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                            isLenient = false
                        }.parse(dateStr)
                        if (parsed != null) {
                            val newTermFirstDay = normalizeToMonday(sdf.format(parsed))
                            val oldTermFirstDay = userRepository.termFirstDay

                            // 投毒/误配置防护：日期经 gh-proxy.com 转发且无签名校验，
                            // 与校历推断的开学日偏差超过 8 周的值一律不信。
                            if (!isPlausibleTermFirstDay(parsed)) {
                                android.util.Log.w("MainViewModel", "Remote term_first_day $dateStr deviates >8 weeks from estimate, ignored")
                                fallbackTermFirstDay(snapshot)
                                return@launch
                            }

                            // 防振荡：issue 未及时更新时远端是上学期旧日期（stale）。
                            // 若本地日期仍然新鲜，说明兜底逻辑已把本地推进到新学期，
                            // 此时回写远端旧值会触发一次 semesterChanged → clearCache，
                            // 下次同步失败兜底又写回新值……clearCache 天级反复执行。
                            // 规则：远端 stale 时只有本地为空或同样 stale 才接受远端值。
                            if (isTermFirstDayStale(newTermFirstDay) &&
                                oldTermFirstDay.isNotEmpty() && !isTermFirstDayStale(oldTermFirstDay)
                            ) {
                                android.util.Log.i("MainViewModel", "Remote term_first_day $dateStr stale, keep local $oldTermFirstDay")
                                return@launch
                            }

                            val semesterChanged = oldTermFirstDay.isNotEmpty() && newTermFirstDay != oldTermFirstDay

                            userRepository.runIfSessionCurrent(snapshot) {
                                // Recheck after network I/O: a manual setting can change mid-request.
                                if (userRepository.termFirstDayManual) return@runIfSessionCurrent
                                userRepository.termFirstDay = newTermFirstDay
                                viewModelScope.launch {
                                    userRepository.runIfSessionCurrent(snapshot) {
                                        _currentWeek.value = calculateCurrentWeek()
                                    }
                                }

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
                            }
                            return@launch
                        }
                    }
                    fallbackTermFirstDay(snapshot)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                fallbackTermFirstDay(snapshot)
            }
        }
    }

    /** 远端开学日与校历推断值的偏差在 8 周内才可信（提前 1-2 个月发布新学期属正常）。 */
    private fun isPlausibleTermFirstDay(parsed: java.util.Date): Boolean {
        val estimated = TermResolver.estimatedFirstDay(TermResolver.inferCurrentTerm())
            ?: return true
        return kotlin.math.abs(parsed.time - estimated.timeInMillis) <= 8L * 7 * 24 * 60 * 60 * 1000
    }

    private fun fallbackTermFirstDay(snapshot: UserRepository.AuthSnapshot) {
        userRepository.runIfSessionCurrent(snapshot) { fallbackTermFirstDayForCurrentSession() }
    }

    private fun fallbackTermFirstDayForCurrentSession() {
        // 手动指定的开学日不被兜底逻辑覆盖
        if (userRepository.termFirstDayManual) {
            loadTodayCourses(force = true)
            return
        }
        val existing = userRepository.termFirstDay
        if (existing.isEmpty() || !isValidDateFormat(existing) || isTermFirstDayStale(existing)) {
            setDefaultTermFirstDay()
            // 此前这里会对 stale 日期 clearCache，与远端同步成功路径互相反弹：
            // 兜底写新学期日期 → 下次同步成功写回远端旧日期（触发 semesterChanged
            // → clearCache）→ 再下次同步失败兜底又写新日期……clearCache 天级振荡。
            // 兜底值非权威，不再清缓存；真正的学期切换清缓存只由"远端权威日期
            // 变化"分支执行。NEW 基线也已与业务缓存解耦，清不清都不再影响角标。
        }
        // 日期兜底可能刚发生变化，绕过"当天已计算"守卫强制重算
        loadTodayCourses(force = true)
    }

    private fun isTermFirstDayStale(dateStr: String): Boolean {
        return try {
            // 持久化格式固定 Locale.US：写侧若用 getDefault()，在数字字形非拉丁的
            // 系统 Locale（ar/fa 等）下产出非 ASCII 数字，这里解析失败被判 stale。
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
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
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
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
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
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

    /**
     * 兜底开学日改用 [TermResolver.estimatedFirstDay]：对同一推断学期是纯函数、
     * 结果稳定（旧的"按当前月份猜 9/1 或 3/2"口径与 TermResolver 不一致，
     * 且不同月份多次调用可能得到不同值，加剧日期振荡）。
     */
    private fun setDefaultTermFirstDay() {
        val termStart = TermResolver.estimatedFirstDay(TermResolver.inferCurrentTerm()) ?: return
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
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
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }
}

package com.ifafu.kyzz.ui.syllabus

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.SyllabusApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.util.TermResolver
import com.ifafu.kyzz.ui.base.ReloginViewModel
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

@HiltViewModel
class SyllabusViewModel @Inject constructor(
    private val syllabusApi: SyllabusApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<UiState<Syllabus>>()
    val state: LiveData<UiState<Syllabus>> = _state

    private val _availableYears = MutableLiveData<List<String>>()
    val availableYears: LiveData<List<String>> = _availableYears

    private val _availableTerms = MutableLiveData<List<String>>()
    val availableTerms: LiveData<List<String>> = _availableTerms

    var selectedYear: String? = null
        private set
    var selectedTerm: String? = null
        private set
    var isCurrentTerm: Boolean = true
        private set

    // 记录初始 GET 加载的学年/学期，spinner 选择相同时直接复用缓存
    private var initialLoadedYear: String? = null
    private var initialLoadedTerm: String? = null
    private var loadJob: Job? = null
    private var loadGeneration: Long = 0L

    init {
        _state.value = UiState.Idle
    }

    fun loadSyllabus(forceRefresh: Boolean = false) {
        loadSyllabus(null, null, forceRefresh)
    }

    fun loadSyllabus(year: String?, term: String?, forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = UiState.Error("未登录")
            return
        }
        cacheManager.migrateSyllabusCacheIfNeeded(user.account)

        selectedYear = year
        selectedTerm = term
        val generation = ++loadGeneration
        val explicitSelection = year != null || term != null
        if (explicitSelection && loadJob?.isActive == true) {
            // 用户连续切换学年/学期时，后一次选择优先，取消前一次请求。
            loadJob?.cancel()
        }

        // 通过日期推断是否为当前学期（第一学期=9-1月，第二学期=2-7月）
        val isInferredCurrentTerm = year != null && term != null && isInferredCurrentTerm(year, term)
        // isCurrentTerm 只代表日期上的当前学期。假期已发布的新学期虽然是默认课表，
        // 仍应按“未开学课表”计算周数，不能把旧学期的周数带过去。
        isCurrentTerm = (year == null && term == null) || isInferredCurrentTerm

        // 初始默认课表可以复用主缓存；其它显式学期必须使用独立 key，
        // 防止 2025-2026 第二学期和 2026-2027 第一学期互相覆盖。
        val isInitialDefaultTerm = year != null && term != null &&
            year == initialLoadedYear && term == initialLoadedTerm
        val yearTermKey = if (year == null && term == null || isInitialDefaultTerm) {
            ""
        } else {
            "${year}_$term"
        }

        if (!forceRefresh) {
            val cached = cacheManager.loadSyllabus(user.account, yearTermKey)
            if (cached != null) {
                adoptLoadedSelection(cached, year, term)
                updateAvailableOptions(cached)
                _state.value = UiState.Cached(
                    cached,
                    cacheManager.cacheStatus(cacheManager.loadSyllabusTimestamp(user.account, yearTermKey))
                )
                if (!isCacheStale(cached, yearTermKey)) return
            }
        }

        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            if (_state.value !is UiState.Success && _state.value !is UiState.Cached) {
                _state.value = UiState.Loading
            }
            try {
                val freshUser = userRepository.getUser()
                // 用户显式指定了学年/学期时，必须精确拉取该学期，绝不能走默认 getSyllabus。
                // 否则寒暑假"升级为新学期"逻辑会把用户选择的旧学期（恰好等于推断当前学期）
                // 覆盖成已发布的新学期，造成串台。默认 getSyllabus 只用于无显式选择的初始加载。
                val syllabus = if (explicitSelection) {
                    syllabusApi.getSyllabusWithTerm(
                        userRepository.host, freshUser.token, freshUser.account, freshUser.name,
                        year!!, term!!
                    )
                } else {
                    syllabusApi.getSyllabus(
                        userRepository.host, freshUser.token, freshUser.account, freshUser.name
                    )
                }
                if (generation != loadGeneration) return@launch
                if (syllabus != null) {
                    if (year == null && term == null && TermResolver.breakTransition() != null) {
                        cacheManager.markSyllabusUpcomingProbe(freshUser.account)
                    }
                    cacheManager.saveSyllabus(freshUser.account, syllabus, yearTermKey)
                    adoptLoadedSelection(syllabus, year, term)
                    updateAvailableOptions(syllabus)
                    _state.value = UiState.Success(syllabus)
                } else {
                    val cached = cacheManager.loadSyllabus(freshUser.account, yearTermKey)
                    if (cached != null) {
                        _state.value = UiState.Cached(
                            cached,
                            cacheManager.cacheStatus(
                                cacheManager.loadSyllabusTimestamp(freshUser.account, yearTermKey),
                                true
                            )
                        )
                    } else {
                        _state.value = UiState.Error("获取课表失败，请检查网络后重试")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AlertException) {
                if (generation != loadGeneration) return@launch
                val msg = if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "获取课表失败")
                _state.value = UiState.Error(msg)
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                val cached = cacheManager.loadSyllabus(userRepository.getUser().account, yearTermKey)
                if (cached != null) {
                    _state.value = UiState.Cached(
                        cached,
                        cacheManager.cacheStatus(
                            cacheManager.loadSyllabusTimestamp(userRepository.getUser().account, yearTermKey),
                            true
                        )
                    )
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }

    /**
     * 通过当前日期推断 year/term 是否为当前学期。
     * 第一学期：9月~次年1月 → 学年以秋季年份开头
     * 第二学期：2月~7月 → 学年以去年秋季年份开头
     */
    private fun isInferredCurrentTerm(year: String, term: String): Boolean {
        val parts = year.split("-")
        if (parts.size != 2) return false
        val startYear = parts[0].toIntOrNull() ?: return false
        val now = java.util.Calendar.getInstance()
        val month = now.get(java.util.Calendar.MONTH)
        val currentYear = now.get(java.util.Calendar.YEAR)
        return when (term) {
            "1" -> {
                // 第一学期：9-12月属于 startYear 年秋季，1月属于 startYear+1 年冬季
                (month >= java.util.Calendar.SEPTEMBER && currentYear == startYear) ||
                (month == java.util.Calendar.JANUARY && currentYear == startYear + 1)
            }
            "2" -> {
                // 第二学期及暑假：2-8月；9月再切换到下一学年第一学期。
                month in java.util.Calendar.FEBRUARY..java.util.Calendar.AUGUST && currentYear == startYear + 1
            }
            else -> false
        }
    }

    private fun updateAvailableOptions(syllabus: Syllabus) {
        if (syllabus.searchYearOptions.isNotEmpty()) {
            val existing = _availableYears.value ?: emptyList()
            val merged = (existing + syllabus.searchYearOptions).distinct().sortedDescending()
            _availableYears.value = merged
        }
        if (syllabus.searchTermOptions.isNotEmpty()) {
            _availableTerms.value = syllabus.searchTermOptions
        }
        // 记录初始 GET 加载的学年/学期（仅首次设置）
        if (initialLoadedYear == null && syllabus.searchYearOptions.isNotEmpty()
            && syllabus.selectedYearOption < syllabus.searchYearOptions.size) {
            initialLoadedYear = syllabus.searchYearOptions[syllabus.selectedYearOption]
        }
        if (initialLoadedTerm == null && syllabus.searchTermOptions.isNotEmpty()
            && syllabus.selectedTermOption < syllabus.searchTermOptions.size) {
            initialLoadedTerm = syllabus.searchTermOptions[syllabus.selectedTermOption]
        }
    }

    private fun adoptLoadedSelection(syllabus: Syllabus, requestedYear: String?, requestedTerm: String?) {
        if (requestedYear != null || requestedTerm != null) return
        val loadedYear = syllabus.searchYearOptions.getOrNull(syllabus.selectedYearOption)
        val loadedTerm = syllabus.searchTermOptions.getOrNull(syllabus.selectedTermOption)
        if (loadedYear.isNullOrEmpty() || loadedTerm.isNullOrEmpty()) return
        selectedYear = loadedYear
        selectedTerm = loadedTerm
        // 默认课表可能在假期探测后从旧学期升级为新学期，
        // 不能只在 ViewModel 第一次加载时记录，否则旧学期会再次误用主缓存。
        initialLoadedYear = loadedYear
        initialLoadedTerm = loadedTerm
        val inferred = TermResolver.inferCurrentTerm()
        isCurrentTerm = loadedYear == inferred.year && loadedTerm == inferred.term
    }

    /**
     * Detect if cached syllabus belongs to a different semester.
     * Two checks:
     * 1. Cache age > 30 days → stale
     * 2. Current week calculated from termFirstDay exceeds the course range (max week + 4 buffer) → stale (仅对当前学期)
     */
    private fun isCacheStale(cached: Syllabus, yearTermKey: String): Boolean {
        val account = userRepository.getUser().account
        val transition = TermResolver.breakTransition()
        val cachedYear = cached.searchYearOptions.getOrNull(cached.selectedYearOption)
        val cachedTerm = cached.searchTermOptions.getOrNull(cached.selectedTermOption)
        val isPublishedUpcoming = transition != null &&
            cachedYear == transition.upcoming.year &&
            cachedTerm == transition.upcoming.term &&
            (cached.courses.isNotEmpty() ||
                cached.practiceCourses.isNotEmpty() ||
                cached.internshipCourses.isNotEmpty() ||
                cached.unscheduledCourses.isNotEmpty())

        // 普通课表缓存时间不能代表“已经检查过新学期”。升级后的第一次进入，
        // 以及之后每十分钟，都要独立探测一次假期新课表。
        if (transition != null && !isPublishedUpcoming &&
            cacheManager.isSyllabusUpcomingProbeStale(account, UPCOMING_PROBE_MAX_AGE_MS)
        ) {
            return true
        }

        // Check 1: time-based staleness
        if (cacheManager.loadSyllabusTimestamp(account, yearTermKey) == 0L ||
            System.currentTimeMillis() -
            cacheManager.loadSyllabusTimestamp(account, yearTermKey) > CACHE_MAX_AGE_MS
        ) {
            return true
        }
        if (isCurrentTerm) {
            val inferred = TermResolver.inferCurrentTerm()
            if (!isPublishedUpcoming &&
                !cachedYear.isNullOrEmpty() && !cachedTerm.isNullOrEmpty() &&
                (cachedYear != inferred.year || cachedTerm != inferred.term)
            ) {
                return true
            }
        }
        // Check 2: semester boundary — 仅对当前学期检查，历史学期的缓存不应因当前周数判断为过期
        if (!isCurrentTerm) return false
        val firstDay = userRepository.termFirstDay
        if (firstDay.isNotEmpty()) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                val parsed = sdf.parse(firstDay) ?: return false
                val termStart = java.util.Calendar.getInstance().apply {
                    time = parsed
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val today = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val diffDays = ((today.timeInMillis - termStart.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
                val currentWeek = (diffDays / 7) + 1
                val maxCourseWeek = if (cached.courses.isNotEmpty()) cached.courses.maxOf { it.weekEnd } else 20
                // If current week is well beyond the course range, this cache is from a previous semester
                if (currentWeek > maxCourseWeek + 4) {
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    companion object {
        private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L
        private const val UPCOMING_PROBE_MAX_AGE_MS = 10 * 60 * 1000L
    }
}

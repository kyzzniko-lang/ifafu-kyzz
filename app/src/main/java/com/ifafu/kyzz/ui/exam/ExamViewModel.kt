package com.ifafu.kyzz.ui.exam

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ExamApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.ExamTable
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.util.TermResolver
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject
import com.ifafu.kyzz.data.util.ExamDates

@HiltViewModel
class ExamViewModel @Inject constructor(
    private val examApi: ExamApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    companion object {
        private const val TAG = "ExamViewModel"
        private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L
    }

    private val _state = MutableLiveData<UiState<ExamTable>>()
    val state: LiveData<UiState<ExamTable>> = _state
    private var loadJob: Job? = null

    init {
        _state.value = UiState.Idle
    }

    fun getUser() = userRepository.getUser()

    fun loadExams(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = UiState.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val isStale = cacheManager.isCacheStale(user.account, "exams", CACHE_MAX_AGE_MS)
            run {
                val cached = cacheManager.loadExamTable(user.account)
                if (cached != null) {
                    // 检测缓存中的考试是否都已过期（属于旧学期）
                    if (!isAllExamsExpired(cached.exams)) {
                        _state.value = UiState.Cached(
                            cached,
                            cacheManager.cacheStatus(cacheManager.loadExamTableTimestamp(user.account))
                        )
                        if (!isStale &&
                            (cached.exams.isEmpty() || !isAllExamsExpired(cached.exams)) &&
                            !belongsToDifferentTerm(cached)
                        ) return
                    }
                    // 缓存中的考试全部过期，强制刷新
                }
            }
        }

        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            if (_state.value !is UiState.Success && _state.value !is UiState.Cached) {
                _state.value = UiState.Loading
            }
            try {
                val freshUser = userRepository.getUser()
                val examTable = examApi.getExamTable(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (examTable != null) {
                    cacheManager.saveExamTable(freshUser.account, examTable)
                    _state.value = UiState.Success(examTable)
                } else {
                    val cached = cacheManager.loadExamTable(freshUser.account)
                    if (cached != null) {
                        _state.value = UiState.Cached(
                            cached,
                            cacheManager.cacheStatus(cacheManager.loadExamTableTimestamp(freshUser.account), true)
                        )
                    } else {
                        _state.value = UiState.Error("获取考试信息失败，请检查网络后重试")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AlertException) {
                val msg = if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "获取考试信息失败")
                _state.value = UiState.Error(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load exams", e)
                val cached = cacheManager.loadExamTable(userRepository.getUser().account)
                if (cached != null) {
                    _state.value = UiState.Cached(
                        cached,
                        cacheManager.cacheStatus(
                            cacheManager.loadExamTableTimestamp(userRepository.getUser().account),
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
     * 检测考试列表中的所有考试是否都已过期（考试日期都在3天前或更早）。
     * 用于判断缓存是否属于旧学期。跨天考试按全部日期算——只看首日会把
     * "昨天开考、今天还有一场"的考试误判过期。
     * 日期解析失败的条目（daysUntilNearest 返回 Int.MAX_VALUE）不作为
     * "已过期"的证据：一条脏数据就让判定为真，每次进考试页都会强制网络刷新。
     */
    private fun isAllExamsExpired(exams: List<com.ifafu.kyzz.data.model.Exam>): Boolean {
        if (exams.isEmpty()) return true
        var parsedCount = 0
        for (exam in exams) {
            val days = ExamDates.daysUntilNearest(exam.datetime)
            if (days == Int.MAX_VALUE) continue
            parsedCount++
            if (days < 3) return false
        }
        // 一条都解析不出来时无法判断，宁可信任缓存（时间戳限频仍在）
        return parsedCount > 0
    }

    private fun belongsToDifferentTerm(table: ExamTable): Boolean {
        val target = TermResolver.inferCurrentTerm()
        val cachedYear = table.searchYearOptions.getOrNull(table.selectedYearOption)
        val cachedTerm = table.searchTermOptions.getOrNull(table.selectedTermOption)
        return !cachedYear.isNullOrEmpty() && !cachedTerm.isNullOrEmpty() &&
            (cachedYear != target.year || cachedTerm != target.term)
    }
}

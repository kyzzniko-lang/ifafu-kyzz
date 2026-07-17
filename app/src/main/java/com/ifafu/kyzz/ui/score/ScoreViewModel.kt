package com.ifafu.kyzz.ui.score

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class ScoreViewModel @Inject constructor(
    private val scoreApi: ScoreApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<UiState<List<Score>>>()
    val state: LiveData<UiState<List<Score>>> = _state

    private var allScores: List<Score> = emptyList()

    private var currentYear: String? = null
    private var currentTerm: String? = null
    private var loadJob: Job? = null

    init {
        _state.value = UiState.Idle
    }

    fun loadScores(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = UiState.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val isStale = cacheManager.isCacheStale(
                user.account,
                "scores",
                SCORE_CACHE_MAX_AGE_MS
            )

            // Stale-while-revalidate: always render memory/disk cache immediately.
            // Only skip networking while the cache is still within the 10-minute window.
            if (allScores.isNotEmpty()) {
                _state.value = UiState.Success(filterScores())
                if (!isStale) return
            } else {
                val cached = cacheManager.loadScores(user.account)
                if (cached != null) {
                    allScores = cached
                    cacheManager.assignFirstSeenFromCache(user.account, allScores)
                    _state.value = UiState.Cached(
                        filterScores(),
                        cacheManager.cacheStatus(cacheManager.loadScoresTimestamp(user.account))
                    )
                    if (!isStale) return
                }
            }
        }

        // Repeated pull-to-refresh gestures previously queued behind HtmlClient's mutex,
        // making a normal request look like a 30-second refresh.
        // 用户手动下拉刷新(forceRefresh)时，若上次 stale-while-revalidate 加载还没结束，
        // 旧逻辑直接 return 会把这次刷新静默丢弃（用户看到转圈以为刷新了其实没执行）。
        // 改为：强制刷新取消在途任务，重新发起。
        if (loadJob?.isActive == true) {
            if (forceRefresh) loadJob?.cancel() else return
        }
        loadJob = viewModelScope.launch {
            // Pull-to-refresh should keep the existing list visible. Showing the full-page
            // loading view here made a slow network request feel like the page was frozen.
            if (allScores.isEmpty()) {
                _state.value = UiState.Loading
            }
            try {
                val freshUser = userRepository.getUser()
                val scores = withTimeoutOrNull(SCORE_REFRESH_TIMEOUT_MS) {
                    scoreApi.getAllScores(
                        userRepository.host, freshUser.token, freshUser.account, freshUser.name
                    )
                }
                if (scores != null) {
                    allScores = scores
                    cacheManager.saveScores(freshUser.account, scores)
                    // 记录首次见到时间，标记本次新增成绩，用于排序与 NEW 角标
                    cacheManager.mergeAndAssignFirstSeen(freshUser.account, allScores)
                    _state.value = UiState.Success(filterScores())
                } else {
                    val cached = cacheManager.loadScores(freshUser.account)
                    if (cached != null) {
                        allScores = cached
                        cacheManager.assignFirstSeenFromCache(freshUser.account, allScores)
                        _state.value = UiState.Cached(
                            filterScores(),
                            cacheManager.cacheStatus(cacheManager.loadScoresTimestamp(freshUser.account), true)
                        )
                    } else {
                        _state.value = UiState.Error("获取成绩失败，请检查网络后重试")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AlertException) {
                val msg = if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "获取成绩失败")
                _state.value = UiState.Error(msg)
            } catch (e: Exception) {
                val cached = cacheManager.loadScores(userRepository.getUser().account)
                if (cached != null) {
                    allScores = cached
                    cacheManager.assignFirstSeenFromCache(userRepository.getUser().account, allScores)
                    _state.value = UiState.Cached(
                        filterScores(),
                        cacheManager.cacheStatus(
                            cacheManager.loadScoresTimestamp(userRepository.getUser().account),
                            true
                        )
                    )
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }

    fun filter(year: String?, term: String?) {
        currentYear = year
        currentTerm = term
        if (allScores.isNotEmpty()) {
            val current = _state.value
            if (current is UiState.Success || current is UiState.Cached) {
                _state.value = when (current) {
                    is UiState.Success -> UiState.Success(filterScores())
                    is UiState.Cached -> UiState.Cached(filterScores(), current.staleMessage)
                    else -> UiState.Success(filterScores())
                }
            }
        }
    }

    private fun filterScores(): List<Score> {
        var filtered = allScores
        if (currentYear != null) {
            filtered = filtered.filter { it.year == currentYear }
        }
        if (currentTerm != null) {
            filtered = filtered.filter { it.term == currentTerm }
        }
        // 按"出分先后"排序：有本地首次见到时间戳的（最近新出的）排最前，
        // 时间戳相同则同批；无时间戳的历史成绩按学年/学期/课程名兜底，排在后面。
        return filtered.sortedWith(
            compareByDescending<Score> { it.firstSeenTs }
                .thenByDescending { it.year }
                .thenByDescending { it.term }
                .thenBy { it.courseName }
        )
    }

    fun getAvailableYears(): List<String> {
        return allScores.map { it.year }.distinct().sortedDescending()
    }

    fun getLatestTerm(): Pair<String, String>? {
        if (allScores.isEmpty()) return null
        val sortedScores = allScores.sortedWith(compareByDescending<Score> { it.year }.thenByDescending { it.term })
        val latest = sortedScores.firstOrNull() ?: return null
        return Pair(latest.year, latest.term)
    }

    companion object {
        /** Avoid leaving the refresh spinner running through several stacked network timeouts. */
        private const val SCORE_REFRESH_TIMEOUT_MS = 20_000L
        /** Fresh enough for normal page entry; older data stays visible while refreshing. */
        private const val SCORE_CACHE_MAX_AGE_MS = 10 * 60 * 1000L
    }
}

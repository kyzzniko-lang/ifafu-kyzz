package com.ifafu.kyzz.ui.score

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
            val isStale = cacheManager.isCacheStale(user.account, "scores", 6 * 60 * 60 * 1000L) // 6 hours
            if (!isStale && allScores.isNotEmpty()) {
                _state.value = UiState.Success(filterScores())
                return
            }
            val cached = cacheManager.loadScores(user.account)
            if (!isStale && cached != null && cached.isNotEmpty()) {
                allScores = cached
                _state.value = UiState.Success(filterScores())
                return
            }
        }

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val freshUser = userRepository.getUser()
                val scores = scoreApi.getAllScores(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (scores != null) {
                    allScores = scores
                    cacheManager.saveScores(freshUser.account, scores)
                    _state.value = UiState.Success(filterScores())
                } else {
                    val cached = cacheManager.loadScores(freshUser.account)
                    if (cached != null && cached.isNotEmpty()) {
                        allScores = cached
                        _state.value = UiState.Cached(filterScores(), "离线模式 · 显示缓存数据")
                    } else {
                        _state.value = UiState.Error("获取成绩失败，请检查网络后重试")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cached = cacheManager.loadScores(userRepository.getUser().account)
                if (cached != null && cached.isNotEmpty()) {
                    allScores = cached
                    _state.value = UiState.Cached(filterScores(), "离线模式 · 显示缓存数据")
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
        return filtered
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
}

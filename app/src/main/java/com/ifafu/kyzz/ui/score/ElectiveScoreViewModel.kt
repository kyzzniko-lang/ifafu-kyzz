package com.ifafu.kyzz.ui.score

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.GpaCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ElectiveScoreViewModel @Inject constructor(
    private val scoreApi: ScoreApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ViewModel() {

    private val _state = MutableLiveData<State>()
    val state: LiveData<State> = _state

    init {
        _state.value = State.Loading
    }

    fun load(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) { _state.value = State.Error("未登录"); return }

        if (!forceRefresh) {
            val isStale = cacheManager.isCacheStale(user.account, "scores", 10 * 60 * 1000L)
            run {
                val cached = cacheManager.loadScores(user.account)
                if (cached != null) {
                    filterElectives(cached)
                    if (!isStale) return
                }
            }
        }

        viewModelScope.launch {
            if (_state.value !is State.Success) _state.value = State.Loading
            try {
                val scores = scoreApi.getAllScores(
                    userRepository.host, user.token, user.account, user.name
                )
                if (scores != null) {
                    // POST 超时降级可能只返回本学期子集，直接覆盖会清掉其它学期的
                    // 选修记录(并与成绩页/后台监控的缓存口径打架)，缩水时合并。
                    // 合并+落盘走原子方法，与后台成绩检查互斥。
                    val effective = cacheManager.mergeScoresIfShrunkAndSave(user.account, scores)
                    filterElectives(effective)
                } else {
                    _state.value = State.Error("加载失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AlertException) {
                val msg = if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "加载失败")
                _state.value = State.Error(msg)
            } catch (e: Exception) {
                // Fallback to stale cache on network error
                val cached = cacheManager.loadScores(user.account)
                if (cached != null) {
                    filterElectives(cached)
                } else {
                    _state.value = State.Error(e.message ?: "加载失败")
                }
            }
        }
    }

    private fun filterElectives(scores: List<Score>) {
        val electives = scores.filter {
            it.courseType.contains("选修") || it.courseType.contains("公选")
        }
        // 只有及格的选修课才计入"已获选修学分"，45 分的课学分并未到手；
        // 判定复用 GpaCalculator.isPassed——挂科后补考及格（含真实 0 分挂科）
        // 的选修课学分同样已获得，自建 `score >= 60` 会把它们漏掉。
        val totalCredits = electives.filter { GpaCalculator.isPassed(it) }
            .sumOf { it.studyScore.toDouble() }.toFloat()
        _state.value = State.Success(electives, totalCredits)
    }

    sealed class State {
        object Loading : State()
        data class Success(val scores: List<Score>, val totalCredits: Float) : State()
        data class Error(val message: String) : State()
    }
}

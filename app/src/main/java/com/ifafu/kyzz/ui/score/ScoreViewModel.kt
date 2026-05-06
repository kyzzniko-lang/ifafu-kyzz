package com.ifafu.kyzz.ui.score

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScoreViewModel @Inject constructor(
    private val scoreApi: ScoreApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ViewModel() {

    private val _state = MutableLiveData<ScoreState>()
    val state: LiveData<ScoreState> = _state

    private var allScores: List<Score> = emptyList()

    private var currentYear: String? = null
    private var currentTerm: String? = null

    init {
        _state.value = ScoreState.Idle
    }

    fun loadScores(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = ScoreState.Error("未登录")
            return
        }

        if (!forceRefresh && allScores.isEmpty()) {
            val cached = cacheManager.loadScores(user.account)
            if (cached != null && cached.isNotEmpty()) {
                allScores = cached
                _state.value = ScoreState.Success(filterScores())
                return
            }
        }

        viewModelScope.launch {
            _state.value = ScoreState.Loading
            try {
                val scores = scoreApi.getAllScores(
                    userRepository.host, user.token, user.account, user.name
                )
                if (scores != null) {
                    allScores = scores
                    cacheManager.saveScores(user.account, scores)
                    _state.value = ScoreState.Success(filterScores())
                } else {
                    _state.value = ScoreState.Error("请先完成教学质量评价")
                }
            } catch (e: Exception) {
                _state.value = ScoreState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun filter(year: String?, term: String?) {
        currentYear = year
        currentTerm = term
        if (allScores.isNotEmpty()) {
            _state.value = ScoreState.Success(filterScores())
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

    sealed class ScoreState {
        object Idle : ScoreState()
        object Loading : ScoreState()
        data class Success(val scores: List<Score>) : ScoreState()
        data class Error(val message: String) : ScoreState()
    }
}

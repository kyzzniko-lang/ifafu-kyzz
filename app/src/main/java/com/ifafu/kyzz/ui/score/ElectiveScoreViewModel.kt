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

    fun load() {
        val user = userRepository.getUser()
        if (!user.isLogin) { _state.value = State.Error("未登录"); return }

        val cached = cacheManager.loadScores(user.account)
        if (cached != null) {
            filterElectives(cached); return
        }

        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val scores = scoreApi.getAllScores(
                    userRepository.host, user.token, user.account, user.name
                )
                if (scores != null) {
                    cacheManager.saveScores(user.account, scores)
                    filterElectives(scores)
                } else {
                    _state.value = State.Error("加载失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "加载失败")
            }
        }
    }

    private fun filterElectives(scores: List<Score>) {
        val electives = scores.filter {
            it.courseType.contains("选修") || it.courseType.contains("公选")
        }
        val totalCredits = electives.filter { it.score > 0 }.sumOf { it.studyScore.toDouble() }.toFloat()
        _state.value = State.Success(electives, totalCredits)
    }

    sealed class State {
        object Loading : State()
        data class Success(val scores: List<Score>, val totalCredits: Float) : State()
        data class Error(val message: String) : State()
    }
}

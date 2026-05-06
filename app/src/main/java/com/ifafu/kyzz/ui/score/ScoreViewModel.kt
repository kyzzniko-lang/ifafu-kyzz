package com.ifafu.kyzz.ui.score

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.model.ScoreTable
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScoreViewModel @Inject constructor(
    private val scoreApi: ScoreApi,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableLiveData<ScoreState>()
    val state: LiveData<ScoreState> = _state

    init {
        _state.value = ScoreState.Idle
    }

    fun loadScores() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = ScoreState.Error("未登录")
            return
        }
        viewModelScope.launch {
            _state.value = ScoreState.Loading
            try {
                val scoreTable = scoreApi.getScoreTable(
                    userRepository.host, user.token, user.account, user.name
                )
                if (scoreTable != null) {
                    _state.value = ScoreState.Success(scoreTable)
                } else {
                    _state.value = ScoreState.Error("请先完成教学质量评价")
                }
            } catch (e: Exception) {
                _state.value = ScoreState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun updateScores(year: String, term: String) {
        val user = userRepository.getUser()
        viewModelScope.launch {
            try {
                val scores = scoreApi.updateScoreTable(
                    userRepository.host, user.token, user.account, user.name, year, term
                )
                val current = (_state.value as? ScoreState.Success)?.scoreTable
                if (current != null) {
                    _state.value = ScoreState.Success(current.copy(scores = scores))
                }
            } catch (e: Exception) {
                _state.value = ScoreState.Error(e.message ?: "查询失败")
            }
        }
    }

    sealed class ScoreState {
        object Idle : ScoreState()
        object Loading : ScoreState()
        data class Success(val scoreTable: ScoreTable) : ScoreState()
        data class Error(val message: String) : ScoreState()
    }
}

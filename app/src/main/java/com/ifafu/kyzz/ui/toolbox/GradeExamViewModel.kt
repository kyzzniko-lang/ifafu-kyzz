package com.ifafu.kyzz.ui.toolbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.TrainingPlanApi
import com.ifafu.kyzz.data.model.GradeExam
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GradeExamViewModel @Inject constructor(
    private val trainingPlanApi: TrainingPlanApi,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableLiveData<State>()
    val state: LiveData<State> = _state

    fun load() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = State.Error("未登录")
            return
        }
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val exams = trainingPlanApi.getGradeExams(
                    userRepository.host, user.token, user.account, user.name
                )
                _state.value = State.Success(exams)
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "加载失败")
            }
        }
    }

    sealed class State {
        object Loading : State()
        data class Success(val exams: List<GradeExam>) : State()
        data class Error(val message: String) : State()
    }
}

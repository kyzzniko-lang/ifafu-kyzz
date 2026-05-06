package com.ifafu.kyzz.ui.studentinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.StudentInfoApi
import com.ifafu.kyzz.data.model.StudentInfo
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StudentInfoViewModel @Inject constructor(
    private val studentInfoApi: StudentInfoApi,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableLiveData<State>()
    val state: LiveData<State> = _state

    fun loadStudentInfo() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = State.Error("未登录")
            return
        }
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val info = studentInfoApi.getStudentInfo(
                    userRepository.host, user.token, user.account, user.name
                )
                if (info != null) {
                    _state.value = State.Success(info)
                } else {
                    _state.value = State.Error("获取信息失败，请重新登录")
                }
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "加载失败")
            }
        }
    }

    sealed class State {
        object Loading : State()
        data class Success(val info: StudentInfo) : State()
        data class Error(val message: String) : State()
    }
}

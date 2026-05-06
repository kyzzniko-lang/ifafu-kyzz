package com.ifafu.kyzz.ui.elective

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ElectiveCourseApi
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.model.ElectiveCourseList
import com.ifafu.kyzz.data.model.Response
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ElectiveViewModel @Inject constructor(
    private val electiveCourseApi: ElectiveCourseApi,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableLiveData<ElectiveState>()
    val state: LiveData<ElectiveState> = _state

    init {
        _state.value = ElectiveState.Idle
    }

    private val _courseList = MutableLiveData<ElectiveCourseList>().apply { value = ElectiveCourseList() }
    val courseList: LiveData<ElectiveCourseList> = _courseList

    fun loadCourses() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = ElectiveState.Error("未登录")
            return
        }
        viewModelScope.launch {
            _state.value = ElectiveState.Loading
            try {
                val list = ElectiveCourseList()
                val response = electiveCourseApi.getElectiveCourseIndex(
                    userRepository.host, user.token, user.account, user.name, list
                )
                if (response.success) {
                    _courseList.value = list
                    _state.value = ElectiveState.Success(response.message)
                } else {
                    _state.value = ElectiveState.Error(response.message)
                }
            } catch (e: Exception) {
                _state.value = ElectiveState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun searchCourses() {
        val user = userRepository.getUser()
        viewModelScope.launch {
            _state.value = ElectiveState.Loading
            try {
                val response = electiveCourseApi.searchElectiveCourse(
                    userRepository.host, user.token, user.account, user.name, _courseList.value ?: ElectiveCourseList()
                )
                if (response.success) {
                    _state.value = ElectiveState.Success(response.message)
                } else {
                    _state.value = ElectiveState.Error(response.message)
                }
            } catch (e: Exception) {
                _state.value = ElectiveState.Error(e.message ?: "查询失败")
            }
        }
    }

    fun selectCourse(courseIndex: String) {
        val user = userRepository.getUser()
        viewModelScope.launch {
            _state.value = ElectiveState.Loading
            try {
                val response = electiveCourseApi.electiveCourse(
                    userRepository.host, user.token, user.account, user.name,
                    _courseList.value ?: ElectiveCourseList(), courseIndex
                )
                if (response.success) {
                    _state.value = ElectiveState.Success(response.message)
                } else {
                    _state.value = ElectiveState.Error(response.message)
                }
            } catch (e: Exception) {
                _state.value = ElectiveState.Error(e.message ?: "选课失败")
            }
        }
    }

    sealed class ElectiveState {
        object Idle : ElectiveState()
        object Loading : ElectiveState()
        data class Success(val message: String) : ElectiveState()
        data class Error(val message: String) : ElectiveState()
    }
}

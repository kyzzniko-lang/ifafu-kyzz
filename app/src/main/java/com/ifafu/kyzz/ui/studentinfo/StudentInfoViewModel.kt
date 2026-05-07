package com.ifafu.kyzz.ui.studentinfo

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.StudentInfoApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.StudentInfo
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StudentInfoViewModel @Inject constructor(
    private val studentInfoApi: StudentInfoApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<State>()
    val state: LiveData<State> = _state

    fun loadStudentInfo(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = State.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val cached = cacheManager.loadStudentInfo(user.account)
            if (cached != null) {
                _state.value = State.Success(cached)
                return
            }
        }

        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val freshUser = userRepository.getUser()
                val info = studentInfoApi.getStudentInfo(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (info != null) {
                    cacheManager.saveStudentInfo(freshUser.account, info)
                    _state.value = State.Success(info)
                } else {
                    _state.value = State.Error("获取信息失败，请检查网络后重试")
                }
            } catch (e: Exception) {
                _state.value = State.Error("网络异常，请稍后重试")
            }
        }
    }

    sealed class State {
        object Loading : State()
        data class Success(val info: StudentInfo) : State()
        data class Error(val message: String) : State()
    }
}

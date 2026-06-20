package com.ifafu.kyzz.ui.studentinfo

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.StudentInfoApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.StudentInfo
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.ui.base.ReloginViewModel
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StudentInfoViewModel @Inject constructor(
    private val studentInfoApi: StudentInfoApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<UiState<StudentInfo>>()
    val state: LiveData<UiState<StudentInfo>> = _state

    fun loadStudentInfo(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = UiState.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val isStale = cacheManager.isCacheStale(user.account, "student_info", 7 * 24 * 60 * 60 * 1000L) // 7 days
            if (!isStale) {
                val cached = cacheManager.loadStudentInfo(user.account)
                if (cached != null) {
                    _state.value = UiState.Success(cached)
                    return
                }
            }
        }

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val freshUser = userRepository.getUser()
                val info = studentInfoApi.getStudentInfo(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (info != null) {
                    cacheManager.saveStudentInfo(freshUser.account, info)
                    _state.value = UiState.Success(info)
                } else {
                    val cached = cacheManager.loadStudentInfo(freshUser.account)
                    if (cached != null) {
                        _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                    } else {
                        _state.value = UiState.Error("获取信息失败，请检查网络后重试")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AlertException) {
                val msg = if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "获取信息失败")
                _state.value = UiState.Error(msg)
            } catch (e: Exception) {
                val cached = cacheManager.loadStudentInfo(userRepository.getUser().account)
                if (cached != null) {
                    _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }
}

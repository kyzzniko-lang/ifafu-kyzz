package com.ifafu.kyzz.ui.exam

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ExamApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.ExamTable
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
class ExamViewModel @Inject constructor(
    private val examApi: ExamApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    companion object {
        private const val TAG = "ExamViewModel"
    }

    private val _state = MutableLiveData<UiState<ExamTable>>()
    val state: LiveData<UiState<ExamTable>> = _state

    init {
        _state.value = UiState.Idle
    }

    fun getUser() = userRepository.getUser()

    fun loadExams(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = UiState.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val cached = cacheManager.loadExamTable(user.account)
            if (cached != null && cached.exams.isNotEmpty()) {
                _state.value = UiState.Success(cached)
                return
            }
        }

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val freshUser = userRepository.getUser()
                val examTable = examApi.getExamTable(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (examTable != null) {
                    cacheManager.saveExamTable(freshUser.account, examTable)
                    _state.value = UiState.Success(examTable)
                } else {
                    val cached = cacheManager.loadExamTable(freshUser.account)
                    if (cached != null && cached.exams.isNotEmpty()) {
                        _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                    } else {
                        _state.value = UiState.Error("获取考试信息失败，请检查网络后重试")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load exams", e)
                val cached = cacheManager.loadExamTable(userRepository.getUser().account)
                if (cached != null && cached.exams.isNotEmpty()) {
                    _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }
}

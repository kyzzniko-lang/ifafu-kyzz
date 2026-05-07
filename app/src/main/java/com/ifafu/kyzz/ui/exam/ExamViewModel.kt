package com.ifafu.kyzz.ui.exam

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.ExamApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.ExamTable
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
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

    private val _state = MutableLiveData<ExamState>()
    val state: LiveData<ExamState> = _state

    init {
        _state.value = ExamState.Idle
    }

    fun loadExams(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = ExamState.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val cached = cacheManager.loadExamTable(user.account)
            if (cached != null && cached.exams.isNotEmpty()) {
                _state.value = ExamState.Success(cached)
                return
            }
        }

        viewModelScope.launch {
            _state.value = ExamState.Loading
            try {
                val freshUser = userRepository.getUser()
                val examTable = examApi.getExamTable(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (examTable != null) {
                    cacheManager.saveExamTable(freshUser.account, examTable)
                    _state.value = ExamState.Success(examTable)
                } else {
                    _state.value = ExamState.Error("获取考试信息失败，请检查网络后重试")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load exams", e)
                _state.value = ExamState.Error("网络异常，请稍后重试")
            }
        }
    }

    sealed class ExamState {
        object Idle : ExamState()
        object Loading : ExamState()
        data class Success(val examTable: ExamTable) : ExamState()
        data class Error(val message: String) : ExamState()
    }
}

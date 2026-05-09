package com.ifafu.kyzz.ui.toolbox

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.TrainingPlanApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.GradeExam
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
class GradeExamViewModel @Inject constructor(
    private val trainingPlanApi: TrainingPlanApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<UiState<List<GradeExam>>>()
    val state: LiveData<UiState<List<GradeExam>>> = _state

    init {
        _state.value = UiState.Idle
    }

    fun load(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = UiState.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val cached = cacheManager.loadGradeExams(user.account)
            if (cached != null && cached.isNotEmpty()) {
                _state.value = UiState.Success(cached)
                return
            }
        }

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val freshUser = userRepository.getUser()
                val exams = trainingPlanApi.getGradeExams(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                cacheManager.saveGradeExams(freshUser.account, exams)
                _state.value = UiState.Success(exams)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cached = cacheManager.loadGradeExams(userRepository.getUser().account)
                if (cached != null && cached.isNotEmpty()) {
                    _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }
}

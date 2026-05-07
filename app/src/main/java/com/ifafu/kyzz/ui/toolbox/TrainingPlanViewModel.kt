package com.ifafu.kyzz.ui.toolbox

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.TrainingPlanApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.TrainingPlan
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrainingPlanViewModel @Inject constructor(
    private val trainingPlanApi: TrainingPlanApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<State>()
    val state: LiveData<State> = _state

    fun load(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = State.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val cached = cacheManager.loadTrainingPlan(user.account)
            if (cached != null && cached.courses.isNotEmpty()) {
                _state.value = State.Success(cached)
                return
            }
        }

        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val freshUser = userRepository.getUser()
                val plan = trainingPlanApi.getTrainingPlan(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (plan != null) {
                    cacheManager.saveTrainingPlan(freshUser.account, plan)
                    _state.value = State.Success(plan)
                } else {
                    _state.value = State.Error("获取培养计划失败，请检查网络后重试")
                }
            } catch (e: Exception) {
                _state.value = State.Error("网络异常，请稍后重试")
            }
        }
    }

    sealed class State {
        object Loading : State()
        data class Success(val plan: TrainingPlan) : State()
        data class Error(val message: String) : State()
    }
}

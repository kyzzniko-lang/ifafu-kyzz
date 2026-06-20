package com.ifafu.kyzz.ui.toolbox

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.TrainingPlanApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.TrainingPlan
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
class TrainingPlanViewModel @Inject constructor(
    private val trainingPlanApi: TrainingPlanApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<UiState<TrainingPlan>>()
    val state: LiveData<UiState<TrainingPlan>> = _state

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
            val isStale = cacheManager.isCacheStale(user.account, "training_plan", 24 * 60 * 60 * 1000L) // 24 hours
            if (!isStale) {
                val cached = cacheManager.loadTrainingPlan(user.account)
                if (cached != null && cached.courses.isNotEmpty()) {
                    _state.value = UiState.Success(cached)
                    return
                }
            }
        }

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val freshUser = userRepository.getUser()
                val plan = trainingPlanApi.getTrainingPlan(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (plan != null) {
                    cacheManager.saveTrainingPlan(freshUser.account, plan)
                    _state.value = UiState.Success(plan)
                } else {
                    val cached = cacheManager.loadTrainingPlan(freshUser.account)
                    if (cached != null && cached.courses.isNotEmpty()) {
                        _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                    } else {
                        _state.value = UiState.Error("获取培养计划失败，请检查网络后重试")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AlertException) {
                val msg = if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "获取培养计划失败")
                _state.value = UiState.Error(msg)
            } catch (e: Exception) {
                val cached = cacheManager.loadTrainingPlan(userRepository.getUser().account)
                if (cached != null && cached.courses.isNotEmpty()) {
                    _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }
}

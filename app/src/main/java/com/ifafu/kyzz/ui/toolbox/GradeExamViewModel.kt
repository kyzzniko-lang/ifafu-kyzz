package com.ifafu.kyzz.ui.toolbox

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.TrainingPlanApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.GradeExam
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
            viewModelScope.launch {
                // SharedPreferences 首读 + Gson 反序列化挪到 IO：主线程直调在
                // 冷启动后首次进入该页时有可感知卡顿（与 MainViewModel 同口径）。
                val pair = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val isStale = cacheManager.isCacheStale(user.account, "grade_exams", 10 * 60 * 1000L)
                    val cached = cacheManager.loadGradeExams(user.account)
                    isStale to cached
                }
                val (isStale, cached) = pair
                if (cached != null && cached.isNotEmpty()) {
                    _state.value = UiState.Success(cached)
                    if (!isStale) return@launch
                }
                loadRemote()
            }
            return
        }

        viewModelScope.launch { loadRemote() }
    }

    private suspend fun loadRemote() {
        run {
            if (_state.value !is UiState.Success && _state.value !is UiState.Cached) {
                _state.value = UiState.Loading
            }
            try {
                val freshUser = userRepository.getUser()
                val exams = trainingPlanApi.getGradeExams(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (exams.isNotEmpty()) {
                    cacheManager.saveGradeExams(freshUser.account, exams)
                    _state.value = UiState.Success(exams)
                } else {
                    // API returned empty (likely error fallback) — try cache
                    val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cacheManager.loadGradeExams(freshUser.account)
                    }
                    if (cached != null && cached.isNotEmpty()) {
                        _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                    } else {
                        _state.value = UiState.Success(emptyList())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AlertException) {
                val msg = if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "获取等级考试失败")
                _state.value = UiState.Error(msg)
            } catch (e: Exception) {
                val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cacheManager.loadGradeExams(userRepository.getUser().account)
                }
                if (cached != null && cached.isNotEmpty()) {
                    _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }
}

package com.ifafu.kyzz.ui.syllabus

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.SyllabusApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyllabusViewModel @Inject constructor(
    private val syllabusApi: SyllabusApi,
    private val userRepository: UserRepository,
    private val cacheManager: CacheManager
) : ReloginViewModel() {

    private val _state = MutableLiveData<UiState<Syllabus>>()
    val state: LiveData<UiState<Syllabus>> = _state

    init {
        _state.value = UiState.Idle
    }

    fun loadSyllabus(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = UiState.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val cached = cacheManager.loadSyllabus(user.account)
            if (cached != null && cached.courses.isNotEmpty()) {
                _state.value = UiState.Success(cached)
                return
            }
        }

        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val freshUser = userRepository.getUser()
                val syllabus = syllabusApi.getSyllabus(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (syllabus != null) {
                    cacheManager.saveSyllabus(freshUser.account, syllabus)
                    _state.value = UiState.Success(syllabus)
                } else {
                    val cached = cacheManager.loadSyllabus(freshUser.account)
                    if (cached != null && cached.courses.isNotEmpty()) {
                        _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                    } else {
                        _state.value = UiState.Error("获取课表失败，请检查网络后重试")
                    }
                }
            } catch (e: Exception) {
                val cached = cacheManager.loadSyllabus(userRepository.getUser().account)
                if (cached != null && cached.courses.isNotEmpty()) {
                    _state.value = UiState.Cached(cached, "离线模式 · 显示缓存数据")
                } else {
                    _state.value = UiState.Error("网络异常，请稍后重试")
                }
            }
        }
    }
}

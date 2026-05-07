package com.ifafu.kyzz.ui.syllabus

import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.SyllabusApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
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

    private val _state = MutableLiveData<SyllabusState>()
    val state: LiveData<SyllabusState> = _state

    init {
        _state.value = SyllabusState.Idle
    }

    private var currentSyllabus: Syllabus? = null

    fun loadSyllabus(forceRefresh: Boolean = false) {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = SyllabusState.Error("未登录")
            return
        }

        if (!forceRefresh) {
            val cached = cacheManager.loadSyllabus(user.account)
            if (cached != null && cached.courses.isNotEmpty()) {
                currentSyllabus = cached
                _state.value = SyllabusState.Success(cached)
                return
            }
        }

        viewModelScope.launch {
            _state.value = SyllabusState.Loading
            try {
                val freshUser = userRepository.getUser()
                val syllabus = syllabusApi.getSyllabus(
                    userRepository.host, freshUser.token, freshUser.account, freshUser.name
                )
                if (syllabus != null) {
                    currentSyllabus = syllabus
                    cacheManager.saveSyllabus(freshUser.account, syllabus)
                    _state.value = SyllabusState.Success(syllabus)
                } else {
                    _state.value = SyllabusState.Error("获取课表失败，请检查网络后重试")
                }
            } catch (e: Exception) {
                _state.value = SyllabusState.Error("网络异常，请稍后重试")
            }
        }
    }

    sealed class SyllabusState {
        object Idle : SyllabusState()
        object Loading : SyllabusState()
        data class Success(val syllabus: Syllabus) : SyllabusState()
        data class Error(val message: String) : SyllabusState()
    }
}

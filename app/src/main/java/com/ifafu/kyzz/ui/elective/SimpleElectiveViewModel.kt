package com.ifafu.kyzz.ui.elective

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.SimpleElectiveApi
import com.ifafu.kyzz.data.model.SimpleCourse
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SimpleElectiveViewModel @Inject constructor(
    private val simpleElectiveApi: SimpleElectiveApi,
    private val userRepository: UserRepository
) : ReloginViewModel() {

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    private val _available = MutableLiveData<List<SimpleCourse>>()
    val available: LiveData<List<SimpleCourse>> = _available

    private val _selected = MutableLiveData<List<SimpleCourse>>()
    val selected: LiveData<List<SimpleCourse>> = _selected

    private var currentType = SimpleElectiveApi.TYPE_PROFESSIONAL

    fun loadCourses(type: String) {
        currentType = type
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = State.Error("未登录")
            return
        }
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val result = simpleElectiveApi.getCourses(
                    type, userRepository.host, user.token, user.account, user.name
                )
                if (result.success) {
                    if (result.notOpen) {
                        _state.value = State.NotOpen(result.message)
                    } else {
                        _available.value = result.available
                        _selected.value = result.selected
                        _state.value = State.Success(result.message)
                    }
                } else {
                    _state.value = State.Error(result.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "加载失败")
            }
        }
    }

    fun reload() {
        loadCourses(currentType)
    }

    sealed class State {
        object Idle : State()
        object Loading : State()
        data class Success(val message: String) : State()
        data class Error(val message: String) : State()
        data class NotOpen(val message: String) : State()
    }
}

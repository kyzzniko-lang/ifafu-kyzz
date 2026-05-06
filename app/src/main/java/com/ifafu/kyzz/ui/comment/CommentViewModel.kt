package com.ifafu.kyzz.ui.comment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.api.CommentTeacherApi
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommentViewModel @Inject constructor(
    private val commentTeacherApi: CommentTeacherApi,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableLiveData<CommentState>()
    val state: LiveData<CommentState> = _state

    init {
        _state.value = CommentState.Idle
    }

    fun startComment() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            _state.value = CommentState.Error("未登录")
            return
        }
        viewModelScope.launch {
            _state.value = CommentState.Loading
            try {
                val response = commentTeacherApi.commentAllTeachers(
                    userRepository.host, user.token, user.account, user.name
                )
                if (response.success) {
                    _state.value = CommentState.Success(response.message)
                } else {
                    _state.value = CommentState.Error(response.message)
                }
            } catch (e: Exception) {
                _state.value = CommentState.Error(e.message ?: "评教失败")
            }
        }
    }

    sealed class CommentState {
        object Idle : CommentState()
        object Loading : CommentState()
        data class Success(val message: String) : CommentState()
        data class Error(val message: String) : CommentState()
    }
}
